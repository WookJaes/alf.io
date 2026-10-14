/*
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
package alfio.manager.attendee;

import alfio.model.attendee.Reconciliation.*;
import alfio.util.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiDraftClientTest {
    private HttpServer server;
    private int status = 200;
    private String response;
    private long delay;
    private final AtomicReference<String> request = new AtomicReference<>();
    private final List<Item> items = List.of(new Item("F-1","10000000-0000-0000-0000-000000000001",
        List.of(new CsvRow(2,"private@example.invalid")),new SystemTicket("uuid","other@example.invalid","ACQUIRED","secret-reservation"),
        List.of(Finding.DUPLICATE,Finding.EMAIL_MISMATCH), false));
    private java.util.concurrent.ExecutorService executor;
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/responses", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(status,body.length); exchange.getResponseBody().write(body); }
            finally { exchange.close(); }
        }); server.start();
        response = valid("F-1",List.of("EMAIL_MISMATCH","DUPLICATE"));
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    private String valid(String reference,List<String> findings) {
        String text = Json.toJson(Map.of("entries",List.of(Map.of("reference",reference,"findingOrder",findings,"style","POLITE","detail","DETAILED"))));
        return Json.toJson(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",text)))),
            "usage",Map.of("input_tokens",100,"output_tokens",50,"input_tokens_details",Map.of("cached_tokens",20))));
    }
    private OpenAiDraftClient client(Duration timeout) {
        var settings = new AttendeeAssistantSettings(new MockEnvironment().withProperty("alfio.attendee-assistant.openai-api-key","synthetic-key"));
        return new OpenAiDraftClient(HttpClient.newHttpClient(),settings, URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/responses"),timeout);
    }
    @Test void sendsOnlyVerifiedTypesAndRendersGroundedEditableDraft() throws Exception {
        var result = client(Duration.ofSeconds(2)).generate(items);
        assertEquals("F-1",result.entries().getFirst().reference());

        assertTrue(result.entries().getFirst().draft().contains("이메일"));
        assertEquals(100,result.inputTokens()); assertEquals(20,result.cachedInputTokens()); assertEquals(50,result.outputTokens());
        String body = request.get();
        assertFalse(body.contains("private@example.invalid")); assertFalse(body.contains("other@example.invalid"));
        assertFalse(body.contains("secret-reservation")); assertFalse(body.contains(items.getFirst().uuid()));
        var payload = Json.OBJECT_MAPPER.readTree(body); assertFalse(payload.path("store").asBoolean());
        assertTrue(payload.path("text").path("format").path("strict").asBoolean());
    }
    @Test void rejectsUnknownReferenceAddedFindingAndMissingFinding() {
        response = valid("F-999",List.of("DUPLICATE","EMAIL_MISMATCH"));
        assertEquals("AI_INVALID_RESPONSE",assertThrows(ReconciliationException.class,()->client(Duration.ofSeconds(2)).generate(items)).getMessage());
        response = valid("F-1",List.of("DUPLICATE","EMAIL_MISMATCH","CSV_ONLY"));
        assertThrows(ReconciliationException.class,()->client(Duration.ofSeconds(2)).generate(items));
        response = valid("F-1",List.of("DUPLICATE"));
        assertThrows(ReconciliationException.class,()->client(Duration.ofSeconds(2)).generate(items));
    }
    @ParameterizedTest @ValueSource(ints={400,401,429,500})
    void hidesUpstreamErrorBodies(int code) {
        status = code; response = "private-response-token";
        var error = assertThrows(ReconciliationException.class,()->client(Duration.ofSeconds(2)).generate(items));
        assertEquals(code==429?"AI_RATE_LIMIT":"AI_FAILED",error.getMessage()); assertNull(error.getCause());
    }
    @Test void rejectsRefusalIncompleteInvalidJsonAndOversizedResponse() {
        for (String value : List.of("{}","not-json", "{\"status\":\"incomplete\"}", "x".repeat(65537))) {
            response=value; assertThrows(ReconciliationException.class,()->client(Duration.ofSeconds(2)).generate(items));
        }
    }
    @Test void timesOutWithoutLeakingBody() {
        delay=500;
        assertEquals("AI_TIMEOUT",assertThrows(ReconciliationException.class,()->client(Duration.ofMillis(100)).generate(items)).getMessage());
    }
}
