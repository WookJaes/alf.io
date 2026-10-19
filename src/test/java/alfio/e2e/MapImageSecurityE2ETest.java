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

import alfio.controller.api.admin.LocationApiControllerTest;
import alfio.manager.system.ConfigurationManager;
import alfio.model.system.ConfigurationKeys.GeoInfoProvider;
import alfio.util.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openqa.selenium.By;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@EnabledIfEnvironmentVariable(named = "ALFIO_RUN_E2E", matches = "true")
class MapImageSecurityE2ETest {
    private static final String ENDPOINT = "/admin/api/location/static-map-image";
    private static final String PAYLOAD = "<script>document.title='XSS_LOCAL_MARKER'</script>";
    // Diagnostic mode is only for measuring the unmodified controller, never a passing security gate.
    private static final boolean BASELINE = Boolean.getBoolean("map.validation.baseline");
    private static final String BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void mvcResponseBridgeInChromeWithoutSecurityFilters() throws Exception {
        var configuration = mock(ConfigurationManager.class);
        when(configuration.getFor(anySet(), any())).thenReturn(LocationApiControllerTest.settings("GOOGLE"));
        var mvc = LocationApiControllerTest.mvc(configuration);
        var response = mvc.perform(get(ENDPOINT).param("lat", PAYLOAD).param("lng", "1").accept("text/html")).andReturn().getResponse();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (response.getContentType() != null) {
                exchange.getResponseHeaders().set("Content-Type", response.getContentType());
            }
            var body = response.getContentAsByteArray();
            exchange.sendResponseHeaders(response.getStatus(), body.length == 0 ? -1 : body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var driver = ValidationBrowser.create();
            try {
                driver.get("http://127.0.0.1:" + server.getAddress().getPort() + "/");
                boolean executed = "XSS_LOCAL_MARKER".equals(driver.getTitle());
                assertEquals(BASELINE, executed);
                assertEquals(BASELINE ? 200 : 406, response.getStatus());
                System.out.println("Map MVC response bridge: status=" + response.getStatus() + ", contentType=" + response.getContentType()
                    + ", scriptExecuted=" + executed + "; no auth/security filters/CSP; not full application");
            } finally { driver.quit(); }
        } finally { server.stop(0); }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void authenticatedApplicationAndLegacyMapBindingInChrome() throws Exception {
        try (var environment = new ValidationEnvironment()) {
            boolean seeded = false;
            for (var provider : GeoInfoProvider.values()) {
                var base = environment.startMapApp(Path.of(System.getenv("VALIDATION_APP_JAR")), provider);
                if (!seeded) {
                    environment.seedAdministrator();
                    seeded = true;
                }
                environment.assertLoginPageReady();
                var driver = ValidationBrowser.create();
                try {
                    // Reject external map traffic: synthetic keys only validate URL construction and UI wiring.
                    driver.executeCdpCommand("Network.enable", Map.of());
                    driver.executeCdpCommand("Network.setBlockedURLs", Map.of("urls", List.of("*maps.googleapis.com*", "*hereapi.com*")));
                    var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + ENDPOINT + "?lat=1&lng=2")).build(), HttpResponse.BodyHandlers.ofString());
                    assertTrue(anonymous.statusCode() == 302 || anonymous.statusCode() == 401 || anonymous.statusCode() == 403);
                    var browser = new NormalFlowE2ETest.BrowserWebDriver(NormalFlowE2ETest.BrowserWebDriver.Browser.CHROME, driver);
                    new AdminConsole(browser, base, "admin", "validation-only-password", null).login();
                    new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> Boolean.TRUE.equals(driver.executeScript(
                        "return !!(window.angular && angular.element(document.body).injector())")));
                    var configuration = request(driver, base + "/admin/api/location/map-provider-client-api-key", "application/json");
                    assertEquals(200, configuration.statusCode());
                    assertEquals(provider.name(), Json.OBJECT_MAPPER.readTree(configuration.body()).get("provider").asText());
                    for (var accept : List.of("text/html", BROWSER_ACCEPT, "application/json, text/plain, */*", "text/plain")) {
                        for (var latitude : List.of("37.5", PAYLOAD)) {
                            var response = request(driver, url(base, latitude, "127.0"), accept);
                            var type = response.headers().firstValue("Content-Type").orElse("none");
                            boolean invalid = latitude.equals(PAYLOAD);
                            int expected = !BASELINE && accept.equals("text/html") ? 406 : !BASELINE && invalid ? 400 : 200;
                            assertEquals(expected, response.statusCode());
                            if (!BASELINE) {
                                assertFalse(type.contains("html"));
                                assertFalse(response.body().contains("<script>"));
                            }
                            if (!response.body().isEmpty()) {
                                assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
                            }
                            System.out.println("Map full app: provider=" + provider + ", input=" + (invalid ? "HTML" : "valid")
                                + ", accept=" + (accept.equals(BROWSER_ACCEPT) ? "Chrome navigation" : accept)
                                + ", status=" + response.statusCode() + ", contentType=" + type
                                + ", rawHtml=" + response.body().contains("<script>")
                                + ", CSP=" + response.headers().firstValue("Content-Security-Policy").isPresent() + ", nosniff=" + response.headers().firstValue("X-Content-Type-Options").orElse("absent-empty-body"));
                        }
                    }
                    if (!BASELINE) {
                        for (var bad : List.of("NaN", "Infinity", "-Infinity", "1e999", "90.01", "-90.01", "north", "")) {
                            assertEquals(400, request(driver, url(base, bad, "1"), "application/json").statusCode());
                        }
                        for (var lng : List.of("180.01", "-180.01", "NaN", "Infinity")) {
                            assertEquals(400, request(driver, url(base, "1", lng), "text/plain").statusCode());
                        }
                        assertEquals(400, request(driver, base + ENDPOINT, "application/json").statusCode());
                        for (var latitude : List.of("90", "-90")) {
                            for (var longitude : List.of("180", "-180")) {
                                assertEquals(200, request(driver, url(base, latitude, longitude), "text/plain").statusCode());
                            }
                        }
                    }
                    // Run the existing Angular LocationService and compile its actual map image template.
                    Object binding = driver.executeAsyncScript("""
                        var done=arguments[arguments.length-1];
                        var i=angular.element(document.body).injector();
                        i.get('LocationService').getMapUrl('37.5','127.0').then(function(url) {
                            var scope=i.get('$rootScope').$new();
                            scope.obj={location:'Synthetic map',geolocation:{mapUrl:url}};
                            scope.loadingMap=false;
                            i.get('$http').get(window.ALFIO_CONTEXT_PATH+'/resources/angular-templates/admin/partials/event/fragment/edit-event-header.html').then(function(r) {
                                var container=document.createElement('div');container.innerHTML=r.data;
                                var source=container.querySelector('img.map-image');
                                if(!source){done({error:'missing actual map template'});return;}
                                var image=i.get('$compile')(source.outerHTML)(scope);
                                angular.element(document.body).append(image);
                                i.get('$timeout')(function(){done({url:url,type:typeof url,src:document.querySelector('img.map-image') ? document.querySelector('img.map-image').getAttribute('src') : null});},0);
                            },function(){done({error:'template request failed'});});
                        },function(){done({error:'map request failed'});});
                        """);
                    var bound = (Map<?, ?>) binding;
                    assertFalse(bound.containsKey("error"), bound.toString());
                    assertEquals("string", bound.get("type"));
                    var mapUrl = bound.get("url").toString();
                    if (provider == GeoInfoProvider.NONE) {
                        assertEquals("", mapUrl);
                    } else {
                        assertTrue(mapUrl.contains(provider == GeoInfoProvider.GOOGLE ? "maps.googleapis.com" : "hereapi.com"));
                        assertEquals(mapUrl, bound.get("src"));
                    }
                    System.out.println("Map full app legacy Angular service/template: provider=" + provider + ", string=true, URL binding verified; external map network blocked");
                    var navigation = request(driver, url(base, PAYLOAD, "1"), BROWSER_ACCEPT);
                    driver.get(url(base, PAYLOAD, "1"));
                    boolean executed = "XSS_LOCAL_MARKER".equals(driver.getTitle());
                    if (!BASELINE) { assertFalse(executed); }
                    System.out.println("Map full app Chrome navigation: provider=" + provider + ", scriptExecuted=" + executed
                        + ", status=" + navigation.statusCode() + ", contentType=" + navigation.headers().firstValue("Content-Type").orElse("none"));
                    environment.assertNoDataMigrationFailure();
                } finally { driver.quit(); }
                environment.stopApp();
            }
        }
    }

    private static String url(String base, String lat, String lng) {
        return base + ENDPOINT + "?lat=" + URLEncoder.encode(lat, StandardCharsets.UTF_8) + "&lng=" + URLEncoder.encode(lng, StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> request(ChromeDriver driver, String url, String accept) throws Exception {
        var cookies = driver.manage().getCookies().stream().map(c -> c.getName() + "=" + c.getValue()).collect(Collectors.joining("; "));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
            .header("Cookie", cookies).header("Accept", accept).build(), HttpResponse.BodyHandlers.ofString());
    }
}
