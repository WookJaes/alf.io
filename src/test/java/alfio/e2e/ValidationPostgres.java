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
    private boolean started;

    void start(Path temporaryDirectory) throws Exception {
        var portable = System.getenv("ALFIO_VALIDATION_POSTGRES_BIN");
        if (portable == null || portable.isBlank()) {
            container = new PostgreSQLContainer(ValidationEnvironment.POSTGRES_IMAGE)
                .withDatabaseName("alfio_validation").withInitScript("init-db-user.sql");
            container.start();
            return;
        }
        bin = Path.of(portable).toAbsolutePath();
        data = temporaryDirectory.resolve("postgres-data");
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        run(List.of(executable("initdb"), "-D", data.toString(), "-U", "postgres", "-A", "trust", "--encoding=UTF8", "--locale=C"));
        Files.writeString(data.resolve("pg_hba.conf"), "local all all trust\nhost all all 127.0.0.1/32 trust\n");
        Files.writeString(data.resolve("postgresql.auto.conf"), "listen_addresses='127.0.0.1'\nport=" + port
            + "\nmax_connections=100\nshared_buffers='128MB'\ntrack_io_timing=on\nlog_lock_waits=on\ndeadlock_timeout='100ms'\n");
        run(List.of(executable("pg_ctl"), "-D", data.toString(), "-l", temporaryDirectory.resolve("postgres.log").toString(), "-w", "start"));
        started = true;
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

    private String executable(String name) {
        var executable = bin.resolve(name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));
        if (!Files.isRegularFile(executable)) {
            throw new IllegalArgumentException("Missing PostgreSQL executable: " + name);
        }
        return executable.toString();
    }

    private static void run(List<String> command) throws Exception {
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("PostgreSQL command timed out: " + Path.of(command.getFirst()).getFileName());
        }
        if (process.exitValue() != 0) {
            throw new IOException("PostgreSQL command failed: " + Path.of(command.getFirst()).getFileName() + " (" + process.exitValue() + ")");
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
        if (container != null) {
            container.stop();
        } else if (started) {
            try {
                run(List.of(executable("pg_ctl"), "-D", data.toString(), "-m", "fast", "-w", "stop"));
                started = false;
            } catch (Exception e) {
                throw new IllegalStateException("Owned PostgreSQL did not stop; preserve its data directory", e);
            }
        }
    }
}
