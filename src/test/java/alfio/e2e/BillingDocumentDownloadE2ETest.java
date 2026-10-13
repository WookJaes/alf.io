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

import alfio.test.util.XlsxTestUtil;
import alfio.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openqa.selenium.By;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.openqa.selenium.support.ui.ExpectedConditions.*;

@EnabledIfEnvironmentVariable(named = "ALFIO_RUN_E2E", matches = "true")
class BillingDocumentDownloadE2ETest {
    private static final List<String> HEADER = List.of("Reservation ID", "Type", "Number", "To", "Tax ID",
        "Total Before Tax", "Tax", "Total", "Currency", "Payment Method", "Generated on", "STATUS", "CONFIRMATION_DATE");

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void ownerDownloadsFromMenuAndOtherOrganizerIsDenied() throws Exception {
        var downloads = Files.createTempDirectory("alfio-billing-download-");
        try (var environment = new ValidationEnvironment()) {
            var baseUrl = environment.startApp(Path.of(System.getenv("VALIDATION_APP_JAR")));
            environment.seedAdministrator();
            environment.assertLoginPageReady();
            var driver = ValidationBrowser.create();
            try {
                driver.executeCdpCommand("Browser.setDownloadBehavior", Map.of("behavior", "allow", "downloadPath", downloads.toString()));
                var browser = new NormalFlowE2ETest.BrowserWebDriver(NormalFlowE2ETest.BrowserWebDriver.Browser.CHROME, driver);
                var admin = new AdminConsole(browser, baseUrl, "admin", "validation-only-password", null);
                var slug = "billing-" + UUID.randomUUID();
                admin.login();
                admin.createEvent(new AdminConsole.EventDefinition(slug, "Synthetic billing event", "Synthetic online location",
                    "Synthetic billing description", baseUrl, baseUrl, baseUrl, 10, "0", "CHF", "0", true, List.of(), "Standard"));
                seed(environment, slug);
                admin.logout();
                var owner = new AdminConsole(browser, baseUrl, "billing-owner", "validation-only-password", null);
                owner.login();
                assertEquals(200, request(driver, baseUrl + "/admin/api/events").statusCode(), "Actual authenticated admin API access");
                driver.get(baseUrl + "/admin#/events/" + slug + "/detail");
                var wait = new WebDriverWait(driver, Duration.ofSeconds(30));
                wait.until(elementToBeClickable(By.id("download-dpdwn"))).click();
                wait.until(d -> d.findElements(By.xpath("//a[contains(., 'all Billing Documents (Excel)')]"))
                    .stream().filter(e -> e.isDisplayed() && e.isEnabled()).findFirst().orElse(null)).click();
                var file = downloads.resolve(slug + "-billing-documents.xlsx");
                wait.until(d -> Files.isRegularFile(file) && !Files.exists(downloads.resolve(file.getFileName() + ".crdownload")));
                var workbook = Files.readAllBytes(file);
                var artifactDirectory = System.getProperty("billing.e2e.artifacts");
                if (artifactDirectory != null) {
                    Files.createDirectories(Path.of(artifactDirectory));
                    Files.write(Path.of(artifactDirectory, "owner.xlsx"), workbook);
                }
                var rows = XlsxTestUtil.rows(workbook);
                assertEquals(HEADER, rows.getFirst());
                assertEquals(4, rows.size(), "Exactly three current documents, excluding superseded/invalid invoices");
                var byNumber = rows.subList(1, rows.size()).stream().collect(Collectors.toMap(row -> row.get(2), row -> row));
                checkRow(byNumber.get("pending"), "OFFLINE_PAYMENT", "");
                checkRow(byNumber.get("complete"), "COMPLETE", "2026-01-01 00:30");
                checkRow(byNumber.get("cancelled"), "CANCELLED", "2026-01-01 00:30");
                for (var row : byNumber.values()) {
                    assertEquals(List.of("Synthetic customer", "SYNTHETIC-TAX", "10.00", "0.00", "10.00", "CHF", "OFFLINE", "2025-12-31 09:00"), row.subList(3, 11));
                    assertEquals("INVOICE", row.get(1));
                }
                var endpoint = baseUrl + "/admin/api/events/" + slug + "/all-documents-xls";
                var response = request(driver, endpoint);
                assertEquals(200, response.statusCode());
                assertEquals("attachment; filename=" + file.getFileName(), response.headers().firstValue("Content-Disposition").orElseThrow());
                assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.headers().firstValue("Content-Type").orElseThrow().split(";")[0]);
                assertEquals(rows, XlsxTestUtil.rows(response.body()));
                System.out.println("Billing owner: actual form login, menu XLSX, HTTP200/attachment; 13 columns, 3 rows; blank confirmation, CANCELLED, Asia/Seoul year boundary; DB 3 reservations/5 documents");
                owner.logout();
                Files.delete(file);
                var outsider = new AdminConsole(browser, baseUrl, "billing-outsider", "validation-only-password", null);
                outsider.login();
                var events = request(driver, baseUrl + "/admin/api/events");
                assertEquals(200, events.statusCode(), "Outsider must successfully authenticate and access admin API");
                assertFalse(new String(events.body(), StandardCharsets.UTF_8).contains(slug), "Other organizer cannot list this event");
                var denied = request(driver, endpoint);
                assertEquals(403, denied.statusCode());
                var denialBody = new String(denied.body(), StandardCharsets.UTF_8);
                assertEquals("Access to resource has been denied", denialBody.startsWith("\"")
                    ? Json.fromJson(denialBody, String.class) : denialBody);
                assertTrue(denied.headers().firstValue("Content-Disposition").isEmpty());
                assertFalse(denied.headers().firstValue("Content-Type").orElse("").contains("spreadsheet"));
                driver.get(endpoint);
                wait.until(d -> d.findElement(By.tagName("body")).getText().contains("Access to resource has been denied"));
                try (var files = Files.list(downloads)) {
                    assertEquals(0, files.count(), "Denied navigation creates no file, including partial downloads");
                }
                System.out.println("Billing outsider: actual form login, admin API HTTP200, target HTTP" + denied.statusCode()
                    + "; exact denial body, no document data/attachment/XLSX/partial file");
                environment.assertNoDataMigrationFailure();
            } catch (Exception | AssertionError e) {
                Files.createDirectories(Path.of("build", "validation"));
                Files.writeString(Path.of("build", "validation", "billing-e2e-failure.html"), driver.getPageSource());
                throw e;
            } finally {
                driver.quit();
            }
        } finally {
            try (var files = Files.walk(downloads)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static void checkRow(List<String> row, String status, String confirmation) {
        assertNotNull(row);
        assertEquals(13, row.size());
        assertEquals(status, row.get(11));
        assertEquals(confirmation, row.get(12));
    }

    // Cookies come exclusively from the browser's real Spring Security form login.
    private static HttpResponse<byte[]> request(ChromeDriver driver, String url) throws Exception {
        var cookies = driver.manage().getCookies().stream().map(c -> c.getName() + "=" + c.getValue()).collect(Collectors.joining("; "));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("Cookie", cookies).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    // Only synthetic records in this test's disposable database; no product schema or policy changes.
    private static void seed(ValidationEnvironment environment, String slug) throws Exception {
        try (var connection = environment.connection()) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement();
                 var event = connection.prepareStatement("select id, org_id from event where short_name = ?")) {
                event.setString(1, slug);
                int eventId;
                int organizationId;
                try (var result = event.executeQuery()) {
                    assertTrue(result.next());
                    eventId = result.getInt(1);
                    organizationId = result.getInt(2);
                    assertFalse(result.next());
                }
                assertEquals(1, sql.executeUpdate("update event set time_zone = 'Asia/Seoul' where id = " + eventId));
                sql.executeUpdate("insert into organization(name, description, email, slug) values ('Other synthetic organization', 'Fixture', 'other@example.invalid', 'billing-other')");
                for (var username : List.of("billing-owner", "billing-outsider")) {
                    try (var user = connection.prepareStatement("insert into ba_user(username,password,first_name,last_name,email_address,enabled) values (?,?,'Synthetic','Organizer',?,true)")) {
                        user.setString(1, username);
                        user.setString(2, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("validation-only-password"));
                        user.setString(3, username + "@example.invalid");
                        assertEquals(1, user.executeUpdate());
                    }
                    sql.executeUpdate("insert into authority(username,role) values ('" + username + "','ROLE_OWNER')");
                    var org = username.equals("billing-owner") ? String.valueOf(organizationId) : "(select id from organization where slug='billing-other')";
                    sql.executeUpdate("insert into j_user_organization(user_id,org_id) select id," + org + " from ba_user where username='" + username + "'");
                }
                var states = Map.of("pending", "OFFLINE_PAYMENT", "complete", "COMPLETE", "cancelled", "CANCELLED");
                for (var entry : states.entrySet()) {
                    var reservationId = UUID.randomUUID().toString();
                    try (var reservation = connection.prepareStatement("""
                        insert into tickets_reservation(id,validity,status,billing_address,vat_nr,payment_method,currency_code,event_id_fk,organization_id_fk,confirmation_ts)
                        values (?,now()+interval '1 day',?,'Synthetic customer','SYNTHETIC-TAX','OFFLINE','CHF',?,?,?)
                        """)) {
                        reservation.setString(1, reservationId);
                        reservation.setString(2, entry.getValue());
                        reservation.setInt(3, eventId);
                        reservation.setInt(4, organizationId);
                        reservation.setObject(5, entry.getKey().equals("pending") ? null : java.time.OffsetDateTime.parse("2025-12-31T15:30:00Z"));
                        assertEquals(1, reservation.executeUpdate());
                    }
                    try (var document = connection.prepareStatement("""
                        insert into billing_document(event_id_fk,number,reservation_id_fk,type,model,generation_ts,status,organization_id_fk)
                        values (?,?,?,'INVOICE',?, '2025-12-31T00:00:00Z','VALID',?)
                        """)) {
                        document.setInt(1, eventId);
                        document.setString(2, entry.getKey());
                        document.setString(3, reservationId);
                        document.setString(4, "{\"orderSummary\":{\"totalNetPrice\":\"10.00\",\"totalVAT\":\"0.00\",\"totalPrice\":\"10.00\"}}");
                        document.setInt(5, organizationId);
                        assertEquals(1, document.executeUpdate());
                    }
                    if (entry.getKey().equals("complete")) {
                        assertEquals(2, sql.executeUpdate("insert into billing_document(event_id_fk,number,reservation_id_fk,type,model,generation_ts,status,organization_id_fk) select event_id_fk,number||'-old',reservation_id_fk,type,model,generation_ts-interval '1 day',status,organization_id_fk from billing_document where reservation_id_fk='" + reservationId + "' union all select event_id_fk,number||'-invalid',reservation_id_fk,type,model,generation_ts+interval '1 day','NOT_VALID',organization_id_fk from billing_document where reservation_id_fk='" + reservationId + "'"));
                    }
                }
                try (var result = sql.executeQuery("select (select count(*) from tickets_reservation where event_id_fk=" + eventId + "), (select count(*) from billing_document where event_id_fk=" + eventId + ")")) {
                    assertTrue(result.next());
                    assertEquals(3, result.getInt(1));
                    assertEquals(5, result.getInt(2));
                }
            }
            connection.commit();
        }
    }
}
