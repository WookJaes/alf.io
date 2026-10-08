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

import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.time.Duration;

/** Waits for the DOM, then lets each flow assert its required elements explicitly. */
final class ValidationBrowser {
    private ValidationBrowser() {
    }

    static ChromeDriver create() {
        var options = new ChromeOptions();
        options.addArguments("--headless=new", "--window-size=1440,1200", "--disable-dev-shm-usage",
            "--no-sandbox", "--lang=en");
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        var driver = new ChromeDriver(options);
        try {
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
            System.out.println("Validation browser: " + driver.getCapabilities().getBrowserName()
                + " " + driver.getCapabilities().getBrowserVersion() + "; page load: eager / 30 seconds");
            return driver;
        } catch (RuntimeException e) {
            driver.quit();
            throw e;
        }
    }
}
