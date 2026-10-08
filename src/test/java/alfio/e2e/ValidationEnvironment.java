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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/** Owns only this run's database, application processes and temporary files. */
public final class ValidationEnvironment implements AutoCloseable {
    public static final String POSTGRES_IMAGE = "postgres:16.13";
    private final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
        .withDatabaseName("alfio_validation").withInitScript("init-db-user.sql");
    private final Path temporaryDirectory;
    private Process app;
    private String baseUrl;

    public ValidationEnvironment() throws IOException {
        temporaryDirectory = Files.createTempDirectory("alfio-validation-");
        try {
            postgres.start();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    public String startApp(Path jar) throws Exception {
        if (app != null) {
            throw new IllegalStateException("Stop the previous app before upgrading");
        }
        if (!Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("Missing application jar: " + jar);
        }
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        baseUrl = "http://127.0.0.1:" + port;
        var log = temporaryDirectory.resolve("app-" + System.nanoTime() + ".log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx768m", "-jar", jar.toAbsolutePath().toString(),
            "--server.address=127.0.0.1", "--server.port=" + port,
            "--spring.profiles.active=dev,disable-jobs",
            "--datasource.url=" + postgres.getJdbcUrl(),
            "--datasource.username=alfio_user", "--datasource.password=password",
            "--alfio.override.system.settings[BASE_URL]=" + baseUrl,
            "--alfio.override.system.settings[MAILER_TYPE]=disabled",
            "--alfio.override.system.settings[MAPS_PROVIDER]=NONE")
            .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.directory(temporaryDirectory.toFile());
        // Isolate local Spring configuration files and external provider/database credentials.
        builder.environment().clear();
        for (var key : java.util.List.of("PATH", "HOME", "TMPDIR", "LANG", "TZ")) {
            if (System.getenv(key) != null) {
                builder.environment().put(key, System.getenv(key));
            }
        }
        app = builder.start();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/healthz")).timeout(Duration.ofSeconds(3)).build();
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < deadline && app.isAlive()) {
            try {
                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    System.out.println("Validation app: " + jar.getFileName() + ", PostgreSQL: " + POSTGRES_IMAGE);
                    return baseUrl;
                }
            } catch (IOException ignored) {
                // Retry only readiness, never test failures.
            }
            Thread.sleep(500);
        }
        Files.createDirectories(Path.of("build", "validation"));
        Files.copy(log, Path.of("build", "validation", "readiness-failure.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        throw new IllegalStateException("Application failed readiness. Temporary log: " + log);
    }

    public void seedAdministrator() throws Exception {
        try (var connection = connection();
             var update = connection.prepareStatement("update ba_user set password = ?, first_name = 'Synthetic', last_name = 'Administrator', email_address = 'admin@example.invalid' where username = 'admin'");
             var statement = connection.createStatement()) {
            update.setString(1, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("validation-only-password"));
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Expected one initialized administrator");
            }
            statement.executeUpdate("insert into organization(name, description, email, slug) values ('Synthetic organization', 'Validation fixture', 'organizer@example.invalid', 'validation-org')");
        }
    }

    public Connection connection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    public String baseUrl() {
        return baseUrl;
    }

    public void stopApp() throws InterruptedException {
        if (app != null) {
            app.destroy();
            if (!app.waitFor(20, TimeUnit.SECONDS)) {
                app.destroyForcibly();
                if (!app.waitFor(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Application process did not stop");
                }
            }
            app = null;
        }
    }

    @Override
    public void close() {
        try {
            stopApp();
        } catch (InterruptedException e) {
            if (app != null) {
                app.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during cleanup", e);
        } finally {
            try {
                postgres.stop();
            } finally {
                try (var files = Files.walk(temporaryDirectory)) {
                    for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Temporary files could not be removed", e);
                }
            }
        }
    }
}
