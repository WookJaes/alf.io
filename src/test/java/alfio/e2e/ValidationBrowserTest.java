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

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openqa.selenium.By;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openqa.selenium.support.ui.ExpectedConditions.visibilityOfElementLocated;

@EnabledIfEnvironmentVariable(named = "ALFIO_RUN_E2E", matches = "true")
class ValidationBrowserTest {
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void loginFormIsUsableBeforeUnrelatedResourceCompletes() throws Exception {
        var releaseResource = new CountDownLatch(1);
        var resourceRequested = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/authentication", exchange -> {
            var body = "<html><body><input id='username'><img src='/delayed-image'></body></html>"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/delayed-image", exchange -> {
            resourceRequested.countDown();
            try {
                releaseResource.await(40, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var driver = ValidationBrowser.create();
            try {
                driver.get("http://127.0.0.1:" + server.getAddress().getPort() + "/authentication");
                assertTrue(resourceRequested.await(5, TimeUnit.SECONDS));
                assertTrue(new org.openqa.selenium.support.ui.WebDriverWait(driver, java.time.Duration.ofSeconds(5))
                    .until(visibilityOfElementLocated(By.id("username"))).isDisplayed());
                System.out.println("Browser navigation regression: login DOM usable while image response is pending");
            } finally {
                releaseResource.countDown();
                driver.quit();
            }
        } finally {
            releaseResource.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
