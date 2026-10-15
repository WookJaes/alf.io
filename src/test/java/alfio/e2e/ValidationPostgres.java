/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.e2e;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Always creates a new database. Never accepts a URL or an existing data directory. */
final class ValidationPostgres implements AutoCloseable {
    private PostgreSQLContainer container;
    private Path bin;
    private Path data;
    private int port;
    private boolean startAttempted;
    private Process activeCommand;
    private final CommandRunner commandRunner;
    private final ProcessStarter processStarter;

    ValidationPostgres() {
        this((ProcessStarter) command -> new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start());
    }

    ValidationPostgres(ProcessStarter processStarter) {
        this.processStarter = processStarter;
        commandRunner = this::runProcess;
    }

    ValidationPostgres(CommandRunner commandRunner) {
        this.commandRunner = commandRunner;
        processStarter = null;
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    @FunctionalInterface
    interface CommandRunner {
        int run(List<String> command) throws Exception;
    }

    void start(Path temporaryDirectory) throws Exception {
        var portable = System.getenv("ALFIO_VALIDATION_POSTGRES_BIN");
        if (portable == null || portable.isBlank()) {
            container = new PostgreSQLContainer(ValidationEnvironment.POSTGRES_IMAGE)
                .withDatabaseName("alfio_validation").withInitScript("init-db-user.sql");
            container.start();
            return;
        }
        startPortableServer(temporaryDirectory, Path.of(portable));
        try (var connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + port + "/postgres", "postgres", "");
             var statement = connection.createStatement()) {
            statement.execute("create database alfio_validation");
        }
        try (var connection = DriverManager.getConnection(getJdbcUrl(), getUsername(), getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create role alfio_user with NOSUPERUSER NOINHERIT LOGIN ENCRYPTED PASSWORD 'password'");
            statement.execute("grant usage, create on schema public to alfio_user");
        }
    }

    void startPortableServer(Path temporaryDirectory, Path portable) throws Exception {
        bin = portable.toAbsolutePath();
        data = temporaryDirectory.resolve("postgres-data");
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        run(List.of(executable("initdb"), "-D", data.toString(), "-U", "postgres", "-A", "trust", "--encoding=UTF8", "--locale=C"));
        Files.writeString(data.resolve("pg_hba.conf"), "local all all trust\nhost all all 127.0.0.1/32 trust\n");
        Files.writeString(data.resolve("postgresql.auto.conf"), "listen_addresses='127.0.0.1'\nport=" + port
            + "\nmax_connections=100\nshared_buffers='128MB'\ntrack_io_timing=on\nlog_lock_waits=on\ndeadlock_timeout='100ms'\n");
        // pg_ctl can launch postgres and then fail or time out while waiting for readiness.
        // From this point, cleanup must verify the owned server even if start never returns.
        startAttempted = true;
        run(List.of(executable("pg_ctl"), "-D", data.toString(), "-l", temporaryDirectory.resolve("postgres.log").toString(), "-w", "start"));
    }

    private String executable(String name) {
        var executable = bin.resolve(name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));
        if (!Files.isRegularFile(executable)) {
            throw new IllegalArgumentException("Missing PostgreSQL executable: " + name);
        }
        return executable.toString();
    }

    private void run(List<String> command) throws Exception {
        int exit = commandRunner.run(command);
        if (exit != 0) {
            throw new IOException("PostgreSQL command failed: " + Path.of(command.getFirst()).getFileName() + " (" + exit + ")");
        }
    }

    private int runProcess(List<String> command) throws Exception {
        // Also guard each subsequent cleanup command: stop itself may resist termination.
        if (activeCommand != null && activeCommand.isAlive()) {
            throw new IOException("Owned PostgreSQL command is still running; preserve its data directory");
        }
        var process = processStarter.start(command);
        activeCommand = process;
        try {
            if (!process.waitFor(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    throw new IOException("PostgreSQL command did not terminate; preserve its data directory");
                }
                throw new IOException("PostgreSQL command timed out: " + Path.of(command.getFirst()).getFileName());
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (!process.isAlive()) {
                activeCommand = null;
            }
        }
    }

    String getJdbcUrl() {
        return container != null ? container.getJdbcUrl() : "jdbc:postgresql://127.0.0.1:" + port + "/alfio_validation";
    }

    String getUsername() {
        return container != null ? container.getUsername() : "postgres";
    }

    String getPassword() {
        return container != null ? container.getPassword() : "";
    }

    @Override
    public void close() {
        // A still-running start command could launch postgres after status reports "absent".
        if (activeCommand != null && activeCommand.isAlive()) {
            throw new IllegalStateException("Owned PostgreSQL command is still running; preserve its data directory");
        }
        if (container != null) {
            container.stop();
        } else if (startAttempted) {
            try {
                // status=3 means this data directory has no running server. Any other
                // result (including an unknown status) requires stop + confirmation.
                if (serverStopped()) {
                    startAttempted = false;
                    return;
                }
                Exception stopFailure = null;
                try {
                    run(List.of(executable("pg_ctl"), "-D", data.toString(), "-m", "fast", "-w", "stop"));
                } catch (Exception e) {
                    stopFailure = e;
                }
                if (!serverStopped()) {
                    throw new IllegalStateException("Owned PostgreSQL stop could not be confirmed", stopFailure);
                }
                startAttempted = false;
            } catch (Exception e) {
                throw new IllegalStateException("Owned PostgreSQL did not stop; preserve its data directory", e);
            }
        }
    }

    private boolean serverStopped() throws Exception {
        return commandRunner.run(List.of(executable("pg_ctl"), "-D", data.toString(), "status")) == 3;
    }
}
