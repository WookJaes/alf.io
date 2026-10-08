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

import alfio.util.Json;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MIGRATION_TEST", matches = "true")
class MigrationValidatorTest {
    static final String PREVIOUS_COMMIT = "2b4759f9136cf5c6f5cb7784c30c9a09da217151";

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void testMigration() throws Exception {
        var previousJar = Path.of(System.getenv("VALIDATION_PREVIOUS_JAR"));
        var currentJar = Path.of(System.getenv("VALIDATION_APP_JAR"));
        assertEquals(PREVIOUS_COMMIT, System.getenv("VALIDATION_PREVIOUS_COMMIT"), "Previous source must be pinned");
        assertEquals(System.getenv("VALIDATION_PREVIOUS_SHA256"), sha256(previousJar), "Previous build artifact must not change before execution");
        assertEquals("2.0-M5-2606", appProperties(previousJar).getProperty("alfio.version"));
        var currentProperties = appProperties(currentJar);
        System.out.println("Previous app: 2.0-M5-2606 / " + PREVIOUS_COMMIT + " / SHA-256 " + sha256(previousJar));
        System.out.println("Current app: " + currentProperties.getProperty("alfio.version") + " / SHA-256 " + sha256(currentJar));
        try (var environment = new ValidationEnvironment()) {
            environment.startApp(previousJar);
            environment.seedAdministrator();
            environment.assertLoginPageReady();
            var slug = "migration-" + UUID.randomUUID();
            preparePreviousFixture(environment.baseUrl(), slug);
            Map<String, List<List<String>>> before;
            List<List<String>> historyBefore;
            String reservationId;
            JsonNode publicBefore;
            try (var connection = environment.connection()) {
                before = snapshot(connection);
                assertEquals(1, before.get("events").size());
                assertEquals(1, before.get("categories").size());
                assertEquals(1, before.get("reservations").size());
                assertEquals(10, before.get("tickets").size());
                reservationId = before.get("reservations").getFirst().getFirst();
                assertEquals("COMPLETE", before.get("reservations").getFirst().get(2));
                assertEquals(1, scalar(connection, "select count(*) from ticket where status = 'ACQUIRED'"));
                assertEquals(9, scalar(connection, "select count(*) from ticket where status = 'FREE'"));
                historyBefore = history(connection);
                assertFalse(historyBefore.isEmpty());
                System.out.println("PostgreSQL server: " + rows(connection, "show server_version").getFirst().getFirst());
            }
            publicBefore = publicSnapshot(environment.baseUrl(), slug, reservationId);
            environment.assertNoDataMigrationFailure();
            // Upgrade only after the previous JVM has terminated. The database remains the same.
            environment.stopApp();
            environment.startApp(currentJar);
            environment.assertNoDataMigrationFailure();
            try (var connection = environment.connection()) {
                assertEquals(before, snapshot(connection), "Existing rows, identifiers, relationships and prices must survive");
                var after = history(connection);
                assertTrue(after.size() >= historyBefore.size());
                assertEquals(historyBefore, after.subList(0, historyBefore.size()), "Existing versioned migration history must survive");
                assertEquals(0, scalar(connection, "select count(*) from flyway_schema_history where not success"));
                assertEquals(0, scalar(connection, """
                    select count(*) from ticket t left join event e on e.id=t.event_id
                    left join ticket_category c on c.id=t.category_id
                    left join tickets_reservation r on r.id=t.tickets_reservation_id
                    where e.id is null or (t.category_id is not null and (c.id is null or c.event_id<>t.event_id))
                    or (t.tickets_reservation_id is not null and (r.id is null or r.event_id_fk<>t.event_id))
                    """));
                var dataMigration = rows(connection, "select current_version, status from event_migration");
                assertEquals(List.of(List.of(currentProperties.getProperty("alfio.version"), "COMPLETE")), dataMigration);
                var flyway = Flyway.configure().dataSource(connection.getMetaData().getURL(), "alfio_user", "password")
                    .locations("classpath:alfio/db/PGSQL/").outOfOrder(true).load();
                assertTrue(flyway.validateWithResult().validationSuccessful, "Current migration definitions must validate");
                assertEquals(0, flyway.info().pending().length, "No unapplied migration may remain");
                System.out.println("Versioned migrations before/after: " + historyBefore.size() + "/" + after.size()
                    + "; pending: 0; failed: 0; orphan relations: 0; event migration: COMPLETE");
                System.out.println("Preserved fixtures: 1 event, 1 category, 1 confirmed reservation, 10 tickets (1 acquired, 9 free)");
            }
            assertEquals(publicBefore, publicSnapshot(environment.baseUrl(), slug, reservationId), "Public event/category/reservation queries must preserve their results");
        }
    }

    private static void preparePreviousFixture(String baseUrl, String slug) throws Exception {
        var driver = ValidationBrowser.create();
        try {
            var browser = new NormalFlowE2ETest.BrowserWebDriver(NormalFlowE2ETest.BrowserWebDriver.Browser.CHROME, driver);
            var admin = new AdminConsole(browser, baseUrl, "admin", "validation-only-password", null);
            admin.login();
            admin.createEvent(new AdminConsole.EventDefinition(slug, "Synthetic migration event", "Synthetic online location",
                "Synthetic migration description", baseUrl, baseUrl, baseUrl, 10, "0", "CHF", "0", true, List.of(), "Standard"));
            admin.publishEvent(slug);
            admin.logout();
            NormalFlowE2ETest.reserveFreeTicket(browser, baseUrl, slug);
        } catch (Exception | AssertionError e) {
            Files.createDirectories(Path.of("build", "validation"));
            Files.writeString(Path.of("build", "validation", "migration-fixture-failure.html"), driver.getPageSource());
            throw e;
        } finally {
            driver.quit();
        }
    }

    private static Map<String, List<List<String>>> snapshot(Connection connection) throws Exception {
        var result = new LinkedHashMap<String, List<List<String>>>();
        result.put("organizations", rows(connection, "select id, name, description, email, slug from organization order by id"));
        result.put("events", rows(connection, "select id, short_name, display_name, org_id, status, start_ts, end_ts, time_zone, src_price_cts, currency from event order by id"));
        result.put("descriptions", rows(connection, "select * from event_description_text order by event_id_fk, locale, type"));
        result.put("categories", rows(connection, "select id, event_id, name, max_tickets, src_price_cts, tc_status, inception, expiration from ticket_category order by id"));
        result.put("reservations", rows(connection, "select id, event_id_fk, status, first_name, last_name, email_address, src_price_cts, final_price_cts, vat_cts, discount_cts, payment_method, user_language from tickets_reservation order by id"));
        result.put("tickets", rows(connection, "select id, uuid, public_uuid, event_id, category_id, tickets_reservation_id, status, first_name, last_name, email_address, src_price_cts, final_price_cts, vat_cts, discount_cts from ticket order by id"));
        return result;
    }

    private static List<List<String>> history(Connection connection) throws Exception {
        return rows(connection, "select installed_rank, version, type, script, checksum, success from flyway_schema_history where version is not null order by installed_rank");
    }

    private static List<List<String>> rows(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            var rows = new ArrayList<List<String>>();
            while (result.next()) {
                var row = new ArrayList<String>();
                for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                    row.add(result.getString(i));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private static int scalar(Connection connection, String sql) throws Exception {
        return Integer.parseInt(rows(connection, sql).getFirst().getFirst());
    }

    private static JsonNode publicSnapshot(String baseUrl, String slug, String reservationId) throws Exception {
        var result = Json.OBJECT_MAPPER.createObjectNode();
        var event = getJson(baseUrl + "/api/v2/public/event/" + slug);
        assertEquals(slug, event.path("shortName").asString());
        for (var field : List.of("shortName", "displayName", "description", "organizationName", "organizationEmail", "availableTicketsCount")) {
            assertNotNull(event.get(field), "Expected public event field: " + field);
            result.set(field, event.get(field));
        }
        result.set("categories", getJson(baseUrl + "/api/v2/public/event/" + slug + "/ticket-categories"));
        var reservation = getJson(baseUrl + "/api/v2/public/reservation/" + reservationId);
        assertEquals(reservationId, reservation.path("id").asString());
        assertEquals("COMPLETE", reservation.path("status").asString());
        for (var field : List.of("id", "status", "firstName", "lastName", "email", "ticketsByCategory", "orderSummary")) {
            assertNotNull(reservation.get(field), "Expected reservation field: " + field);
            result.set(field, reservation.get(field));
        }
        return result;
    }

    private static JsonNode getJson(String url) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Public query must succeed: " + url);
        return Json.OBJECT_MAPPER.readTree(response.body());
    }

    private static Properties appProperties(Path jarPath) throws Exception {
        try (var jar = new JarFile(jarPath.toFile()); var input = jar.getInputStream(jar.getJarEntry("BOOT-INF/classes/application.properties"))) {
            var properties = new Properties();
            properties.load(input);
            return properties;
        }
    }

    private static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
