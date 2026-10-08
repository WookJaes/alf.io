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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.openqa.selenium.support.ui.ExpectedConditions.*;
import static alfio.e2e.E2EUtils.*;

@EnabledIfEnvironmentVariable(named = "ALFIO_RUN_E2E", matches = "true")
class NormalFlowE2ETest {
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void testFlow() throws Exception {
        try (var environment = new ValidationEnvironment()) {
            var baseUrl = environment.startApp(Path.of(System.getenv("VALIDATION_APP_JAR")));
            environment.seedAdministrator();
            var options = new ChromeOptions();
            options.addArguments("--headless=new", "--window-size=1440,1200", "--disable-dev-shm-usage", "--no-sandbox", "--lang=en", "--disable-crashpad-for-testing");
            var driver = new ChromeDriver(options);
            try {
                var browser = new BrowserWebDriver(BrowserWebDriver.Browser.CHROME, driver);
                var slug = "e2e-" + UUID.randomUUID();
                // The isolated database contains only synthetic administrator and organization data.
                var admin = new AdminConsole(browser, baseUrl, "admin", "validation-only-password", null);
                admin.login();
                admin.createEvent(new AdminConsole.EventDefinition(slug, "Synthetic validation event", "Synthetic online location",
                    "Synthetic event description", baseUrl, baseUrl, baseUrl, 10, "0", "CHF", "0", true, List.of(), "Standard"));
                admin.publishEvent(slug);
                admin.logout();
                reserveFreeTicket(browser, baseUrl, slug);
                try (var connection = environment.connection();
                     var query = connection.prepareStatement("""
                         select r.status, t.status, t.first_name, t.last_name, t.email_address
                         from tickets_reservation r join ticket t on t.tickets_reservation_id = r.id
                         join event e on e.id = t.event_id where e.short_name = ? and r.status = 'COMPLETE'
                         """)) {
                    query.setString(1, slug);
                    try (var result = query.executeQuery()) {
                        assertTrue(result.next(), "One confirmed reservation must exist");
                        assertEquals("COMPLETE", result.getString(1));
                        assertEquals("ACQUIRED", result.getString(2));
                        assertEquals("Synthetic", result.getString(3));
                        assertEquals("Attendee", result.getString(4));
                        assertEquals("attendee@example.invalid", result.getString(5));
                        assertFalse(result.next(), "Exactly one ticket must be confirmed");
                    }
                }
            } catch (Exception | AssertionError e) {
                java.nio.file.Files.createDirectories(Path.of("build", "validation"));
                java.nio.file.Files.writeString(Path.of("build", "validation", "e2e-failure.html"), driver.getPageSource());
                throw e;
            } finally {
                driver.quit();
            }
        }
    }

    static void reserveFreeTicket(BrowserWebDriver browser, String baseUrl, String slug) {
        var driver = browser.driver;
        var wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        driver.navigate().to(baseUrl + "/event/" + slug);
        var amount = wait.until(elementToBeClickable(By.cssSelector("select[formcontrolname=amount]")));
        new Select(amount).selectByVisibleText("1");
        wait.until(elementToBeClickable(By.id("show-event-continue"))).click();
        wait.until(elementToBeClickable(By.id("first-name"))).sendKeys("Synthetic");
        driver.findElement(By.id("last-name")).sendKeys("Attendee");
        driver.findElement(By.id("email")).sendKeys("attendee@example.invalid");
        driver.findElement(By.id("email")).sendKeys(org.openqa.selenium.Keys.TAB);
        for (var field : java.util.Map.of("firstName", "Synthetic", "lastName", "Attendee", "email", "attendee@example.invalid").entrySet()) {
            var input = wait.until(elementToBeClickable(By.cssSelector("app-ticket-form input[formcontrolname=" + field.getKey() + "]")));
            input.clear();
            input.sendKeys(field.getValue());
        }
        clickWithJs(driver, wait.until(elementToBeClickable(By.cssSelector("button[translate='reservation-page.continue']"))));
        wait.until(visibilityOfElementLocated(By.cssSelector("h2[translate='reservation-page.title']")));
        driver.findElements(By.id("privacy-policy-label")).forEach(e -> selectElement(e, browser));
        selectElement(driver.findElement(By.id("terms-conditions-label")), browser);
        clickWithJs(driver, wait.until(elementToBeClickable(By.cssSelector("button[type=submit].btn-success"))));
        wait.until(urlContains("/success"));
        assertTrue(wait.until(visibilityOfElementLocated(By.cssSelector("app-success .attendees-data"))).getText().contains("Synthetic"));
    }

    static class BrowserWebDriver {
        enum Browser { IE, CHROME, FIREFOX, SAFARI }
        final Browser browser;
        final WebDriver driver;
        BrowserWebDriver(Browser browser, WebDriver driver) {
            this.browser = browser;
            this.driver = driver;
        }
    }
}
