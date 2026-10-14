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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenAiDraftClientTest {
    private HttpServer server;
    private int status = 200;
    private String response;
    private long delay;
    private long bodyDelay;
    private final CountDownLatch firstByteSent = new CountDownLatch(1);
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
            try {
                exchange.sendResponseHeaders(status,body.length);
                exchange.getResponseBody().write(body,0,1);
                exchange.getResponseBody().flush();
                firstByteSent.countDown();
                try { Thread.sleep(bodyDelay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                exchange.getResponseBody().write(body,1,body.length-1);
            }
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
    @Test void timesOutWhileBodyIsDelayedAfterHeadersAndFirstByte() {
        bodyDelay = 2500;
        var error = assertTimeoutPreemptively(Duration.ofMillis(1800), () ->
            assertThrows(ReconciliationException.class, () -> client(Duration.ofMillis(500)).generate(items)));
        assertEquals(0, firstByteSent.getCount(), "Response headers and partial body must arrive before timeout");
        assertEquals("AI_TIMEOUT", error.getMessage());
        assertEquals(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, error.status());
        assertNull(error.getCause());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cancelsPendingRequestAndBodySubscriptionIncludingLateSubscription(boolean subscribeLate) {
        var http = mock(HttpClient.class);
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        var subscription = mock(Flow.Subscription.class);
        var subscriber = new AtomicReference<HttpResponse.BodySubscriber<byte[]>>();
        when(http.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
            .thenAnswer(invocation -> {
                HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                subscriber.set(handler.apply(null));
                if (!subscribeLate) subscriber.get().onSubscribe(subscription);
                return pending;
            });
        var settings = new AttendeeAssistantSettings(new MockEnvironment().withProperty("alfio.attendee-assistant.openai-api-key","synthetic-key"));
        var ai = new OpenAiDraftClient(http,settings,URI.create("http://127.0.0.1/responses"),Duration.ofMillis(100));
        assertEquals("AI_TIMEOUT", assertThrows(ReconciliationException.class, () -> ai.generate(items)).getMessage());
        if (subscribeLate) subscriber.get().onSubscribe(subscription);
        assertTrue(pending.isCancelled());
        verify(subscription).cancel();
        if (subscribeLate) verify(subscription, never()).request(anyLong());
        assertTrue(subscriber.get().getBody().toCompletableFuture().isCancelled());
    }
}
