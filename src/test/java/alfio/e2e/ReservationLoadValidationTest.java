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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP against a separate JVM and a new synthetic PostgreSQL database per run. */
@EnabledIfEnvironmentVariable(named = "ALFIO_RUN_LOAD", matches = "true")
class ReservationLoadValidationTest {
    // SALEABLE is an inventory outcome only for our prevalidated public, currently on-sale fixture.
    private static final Set<String> INVENTORY_ERRORS = Set.of("error.STEP_1_NOT_ENOUGH_TICKETS",
        "error.STEP_1_TICKET_CATEGORY_MUST_BE_SALEABLE");
    private static final Map<String, Object> PAYMENT = Map.of("termAndConditionsAccepted", true,
        "privacyPolicyAccepted", true);

    record Response(int status, JsonNode body) { }
    record Attempt(String outcome, double millis, String reservation, String ticket, String failure) { }
    record Fixture(String slug, int eventId, int categoryId) { }
    record DbSnapshot(long complete, long acquired, long free, long pending, long unacknowledgedComplete) { }
    static final class UnexpectedResponse extends IllegalStateException {
        final int status;
        UnexpectedResponse(Response response) { super("HTTP " + response.status + "; codes=" + errors(response)); status = response.status; }
    }

    static final class Client implements AutoCloseable {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).version(HttpClient.Version.HTTP_1_1).build();
        private final String base;
        private String csrf;
        private String session;
        final AtomicLong requests = new AtomicLong();
        Client(String base) throws Exception {
            this.base = base;
            try { requireOk(request("events", null)); } catch (Exception e) { http.close(); throw e; }
        }
        Response request(String path, Object data) throws Exception {
            requests.incrementAndGet();
            var builder = HttpRequest.newBuilder(URI.create(base + "/api/v2/public/" + path))
                .header("X-Requested-With", "XMLHttpRequest").timeout(Duration.ofSeconds(10));
            if (session != null) { builder.header("X-Auth-Token", session); }
            if (data != null) {
                if (csrf == null) { throw new IllegalStateException("Missing CSRF token"); }
                builder.header("X-CSRF-TOKEN", csrf);
                builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.toJson(data)));
            }
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            response.headers().firstValue("XSRF-TOKEN").ifPresent(token -> csrf = token);
            response.headers().firstValue("X-Auth-Token").ifPresent(token -> session = token);
            JsonNode body;
            try { body = response.body().isBlank() ? Json.OBJECT_MAPPER.nullNode() : Json.OBJECT_MAPPER.readTree(response.body()); }
            catch (tools.jackson.core.JacksonException e) { throw new UnexpectedResponse(new Response(response.statusCode(), Json.OBJECT_MAPPER.nullNode())); }
            if (response.statusCode() == 404) {
                System.out.println("HTTP diagnostic: method=" + (data == null ? "GET" : "POST") + "; error=" + body.path("error").asString() + "; path=" + body.path("path").asString());
            }
            return new Response(response.statusCode(), body);
        }
        Response reserve(Fixture fixture, int amount) throws Exception {
            return request("event/" + fixture.slug + "/reserve-tickets?lang=en", Map.of("reservation",
                List.of(Map.of("ticketCategoryId", fixture.categoryId, "amount", amount))));
        }
        String complete(String reservation) throws Exception {
            var info = request("reservation/" + reservation, null);
            requireOk(info);
            var ticket = info.body.path("ticketsByCategory").get(0).path("tickets").get(0).path("uuid").asString();
            var attendee = Map.of("firstName", "Synthetic", "lastName", "Attendee", "email", "attendee@example.invalid", "userLanguage", "en");
            var form = new HashMap<String, Object>(attendee);
            form.put("tickets", Map.of(ticket, attendee));
            var validation = request("reservation/" + reservation + "/validate-to-overview?lang=en&ignoreWarnings=false", form);
            requireOk(validation);
            requireOk(request("reservation/" + reservation + "?lang=en", PAYMENT));
            return ticket;
        }
        @Override public void close() { http.close(); }
    }

    static final class Clients implements AutoCloseable {
        final List<Client> values = new ArrayList<>();
        Clients(String base, int count) throws Exception {
            try { for (int i = 0; i < count; i++) { values.add(new Client(base)); } }
            catch (Exception e) { close(); throw e; }
        }
        @Override public void close() {
            RuntimeException failure = null;
            for (var client : values) {
                try { client.close(); } catch (RuntimeException e) { failure = e; }
            }
            if (failure != null) { throw failure; }
        }
    }

    static Set<String> errors(Response response) {
        var result = new HashSet<String>();
        response.body.path("validationErrors").forEach(e -> result.add(e.path("code").asString()));
        return result;
    }

    static boolean inventoryRejection(Response response) {
        var errors = errors(response);
        return response.status == 422 && response.body.path("success").isBoolean() && !response.body.path("success").asBoolean()
            && response.body.path("errorCount").asInt() > 0
            && !errors.isEmpty() && INVENTORY_ERRORS.containsAll(errors);
    }

    static void requireOk(Response response) {
        if (response.status != 200 || (response.body.has("success") && !response.body.path("success").asBoolean())
            || !errors(response).isEmpty() || (response.body.has("errorCount") && response.body.path("errorCount").asInt() != 0)) {
            // Deliberately omit response bodies, identifiers and personal data from failures.
            throw new UnexpectedResponse(response);
        }
    }

    private static Fixture fixture(ValidationEnvironment environment, int capacity) throws Exception {
        var driver = ValidationBrowser.create();
        var slug = "load-" + UUID.randomUUID();
        try {
            var browser = new NormalFlowE2ETest.BrowserWebDriver(NormalFlowE2ETest.BrowserWebDriver.Browser.CHROME, driver);
            var admin = new AdminConsole(browser, environment.baseUrl(), "admin", "validation-only-password", null);
            admin.login();
            admin.createEvent(new AdminConsole.EventDefinition(slug, "Synthetic load event", "Synthetic online location",
                "Synthetic load fixture", environment.baseUrl(), environment.baseUrl(), environment.baseUrl(), capacity,
                "0", "CHF", "0", true, List.of(), "Standard"));
            admin.publishEvent(slug);
        } finally { driver.quit(); }
        try (var connection = environment.connection(); var statement = connection.prepareStatement(
            "select e.id, tc.id from event e join ticket_category tc on tc.event_id=e.id where e.short_name=?")) {
            statement.setString(1, slug);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                var fixture = new Fixture(slug, rows.getInt(1), rows.getInt(2));
                assertFalse(rows.next());
                assertEquals(capacity, count(connection, "select count(*) from ticket where event_id=" + fixture.eventId + " and status='FREE'"));
                assertEquals(1, count(connection, "select count(*) from event e join ticket_category tc on tc.event_id=e.id where e.id="
                    + fixture.eventId + " and e.status='PUBLIC' and tc.tc_status='ACTIVE' and not tc.access_restricted and tc.inception<=now() and tc.expiration>now()+interval '1 hour'"), "Fixture must remain valid throughout this bounded run");
                return fixture;
            }
        }
    }

    static long count(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static ValidationEnvironment start() throws Exception {
        var environment = new ValidationEnvironment();
        try {
            environment.startApp(Path.of(System.getenv("VALIDATION_APP_JAR")));
            environment.seedAdministrator();
            environment.assertNoDataMigrationFailure();
            return environment;
        } catch (Exception e) { environment.close(); throw e; }
    }

    private static DbSnapshot safety(ValidationEnvironment environment, Fixture fixture, List<Attempt> attempts, int capacity) throws Exception {
        try (var connection = environment.connection()) {
            // In error runs, wait for in-flight DB work before taking a consistent final snapshot.
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            int quiet = 0;
            while (quiet < 3 && System.nanoTime() < deadline) {
                long active = count(connection, "select count(*) from pg_stat_activity where datname=current_database() and usename='alfio_user' and state='active'");
                quiet = active == 0 ? quiet + 1 : 0;
                Thread.sleep(100);
            }
            assertEquals(3, quiet, "App DB work did not settle; final state cannot be claimed");
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            var successes = attempts.stream().filter(a -> a.outcome.equals("complete")).toList();
            assertEquals(successes.size(), successes.stream().map(Attempt::reservation).distinct().count(), "Duplicate reservation responses");
            assertEquals(successes.size(), successes.stream().map(Attempt::ticket).distinct().count(), "Duplicate ticket responses");
            var event = " event_id=" + fixture.eventId;
            assertEquals(capacity, count(connection, "select count(*) from ticket where" + event));
            long acquired = count(connection, "select count(*) from ticket where" + event + " and status='ACQUIRED'");
            long free = count(connection, "select count(*) from ticket where" + event + " and status='FREE'");
            long pending = count(connection, "select count(*) from ticket where" + event + " and status='PENDING'");
            long complete = count(connection, "select count(*) from tickets_reservation where event_id_fk=" + fixture.eventId + " and status='COMPLETE'");
            assertEquals(complete, acquired, "Every complete reservation must own exactly one acquired ticket");
            assertTrue(complete >= successes.size(), "Every acknowledged reservation must remain complete");
            assertTrue(acquired <= capacity && free >= 0 && acquired + free + pending == capacity);
            assertEquals(0, count(connection, "select count(*) from ticket t left join tickets_reservation r on r.id=t.tickets_reservation_id where t.event_id="
                + fixture.eventId + " and ((r.id is not null and r.event_id_fk<>t.event_id) or (t.status='FREE' and t.tickets_reservation_id is not null) or (t.status='ACQUIRED' and (r.id is null or r.status<>'COMPLETE')) or (t.status='PENDING' and (r.id is null or r.status not in ('PENDING','IN_PAYMENT'))))"));
            assertEquals(0, count(connection, "select count(*) from tickets_reservation r where r.event_id_fk=" + fixture.eventId
                + " and not exists(select 1 from ticket t where t.tickets_reservation_id=r.id)"), "Orphan reservations / failed transaction rollback");
            assertEquals(0, count(connection, "select count(*) from (select uuid from ticket where" + event + " group by uuid having count(*)>1) d"));
            assertEquals(0, count(connection, "select count(*) from (select public_uuid from ticket where" + event + " group by public_uuid having count(*)>1) d"));
            assertEquals(0, count(connection, "select count(*) from tickets_reservation r where r.event_id_fk=" + fixture.eventId
                + " and r.status='COMPLETE' and (select count(*) from ticket t where t.tickets_reservation_id=r.id and t.status='ACQUIRED')<>1"));
            for (var success : successes) {
                try (var statement = connection.prepareStatement("select count(*) from ticket t join tickets_reservation r on r.id=t.tickets_reservation_id where t.public_uuid=?::uuid and r.id=? and t.status='ACQUIRED' and r.status='COMPLETE'")) {
                    statement.setString(1, success.ticket); statement.setString(2, success.reservation);
                    try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
                }
            }
            System.out.printf(Locale.ROOT, "DB safety: capacity=%d complete=%d free=%d pending=%d%n", capacity, acquired, free, pending);
            return new DbSnapshot(complete, acquired, free, pending, complete - successes.size());
        }
    }

    private static Attempt attempt(Client client, Fixture fixture) {
        long start = System.nanoTime();
        String reservation = null;
        try {
            requireOk(client.request("event/" + fixture.slug + "/ticket-categories", null));
            var response = client.reserve(fixture, 1);
            if (inventoryRejection(response)) { return new Attempt("inventory_rejection", elapsed(start), null, null, String.join(";", errors(response))); }
            requireOk(response);
            reservation = response.body.path("value").asString();
            var ticket = client.complete(reservation);
            return new Attempt("complete", elapsed(start), reservation, ticket, "");
        } catch (HttpTimeoutException e) { return new Attempt("timeout", elapsed(start), reservation, null, "timeout");
        } catch (UnexpectedResponse e) { return new Attempt(e.status >= 500 ? "server_error" : "unexpected_response", elapsed(start), reservation, null, e.getMessage());
        } catch (Exception e) { return new Attempt("client_error", elapsed(start), reservation, null, e.getClass().getSimpleName()); }
    }

    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }

    /** Samples every 100ms. These are observed session waits, not cumulative wait durations. */
    static final class Observer implements AutoCloseable {
        final AtomicBoolean running = new AtomicBoolean(true);
        final Map<String, Long> waits = new TreeMap<>();
        final Thread thread;
        int samples;
        int peakActive;
        volatile Exception failure;
        Observer(ValidationEnvironment environment) {
            thread = Thread.ofPlatform().start(() -> {
                try (var connection = environment.connection(); var statement = connection.createStatement()) {
                    statement.setQueryTimeout(3);
                    while (running.get()) {
                        int active = 0;
                        try (var rows = statement.executeQuery("select state, wait_event_type, wait_event from pg_stat_activity where datname=current_database() and usename='alfio_user'")) {
                            while (rows.next()) {
                                if ("active".equals(rows.getString(1))) {
                                    active++;
                                    var type = rows.getString(2);
                                    if (type != null) { waits.merge(type + ":" + rows.getString(3), 1L, Long::sum); }
                                }
                            }
                        }
                        peakActive = Math.max(peakActive, active); samples++;
                        Thread.sleep(100);
                    }
                } catch (Exception e) { failure = e; }
            });
        }
        @Override public void close() throws Exception { running.set(false); thread.join(5000); assertFalse(thread.isAlive()); if (failure != null) { throw failure; } }
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void steppedLoad() throws Exception {
        String label = Objects.requireNonNullElse(System.getenv("ALFIO_LOAD_LABEL"), "baseline");
        assertTrue(label.matches("[a-zA-Z0-9_-]+"));
        var output = Path.of("build", "validation", "reservation-load-" + label + ".csv");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "concurrency,repeat,total,seconds,outcome,count,mean_ms,p95_ms,outcome_rps,total_rps,db_samples,peak_active,wait_samples,deadlocks,rollbacks,http_requests,app_cpu_seconds,db_complete,db_acquired,db_free,db_pending,unacknowledged_complete,success_window_seconds\n");
        String levels = Objects.requireNonNullElse(System.getenv("ALFIO_LOAD_LEVELS"), "1,5,10,20,40,80");
        int repeats = Integer.parseInt(Objects.requireNonNullElse(System.getenv("ALFIO_LOAD_REPEATS"), "2"));
        assertTrue(repeats >= 1 && repeats <= 20, "Repeat count must perform actual bounded measurements");
        for (var level : levels.split(",")) {
            int concurrency = Integer.parseInt(level);
            assertTrue(concurrency > 0 && concurrency <= 200);
            for (int repeat = 1; repeat <= repeats; repeat++) {
                try (var environment = start(); var client = new Client(environment.baseUrl())) {
                    var warmup = fixture(environment, 20);
                    for (int i = 0; i < 20; i++) { assertEquals("complete", attempt(client, warmup).outcome, "Warmup must succeed"); }
                    // Warmup event remains fixed at 20 acquired tickets in every fresh database.
                    var fixture = fixture(environment, 100);
                    try (var connection = environment.connection(); var statement = connection.createStatement()) {
                        statement.execute("select pg_stat_reset()");
                        try (var rows = statement.executeQuery("select name, setting, unit from pg_settings where name in ('max_connections','shared_buffers','work_mem','fsync','synchronous_commit','track_io_timing') order by name")) {
                            while (rows.next()) { System.out.println("DB setting: " + rows.getString(1) + "=" + rows.getString(2) + " " + rows.getString(3)); }
                        }
                    }
                    System.out.printf("Load start: concurrency=%d repeat=%d total=1000 capacity=100 warmup=20%n", concurrency, repeat);
                    client.requests.set(0);
                    var attempts = new ArrayList<Attempt>();
                    try (var clients = new Clients(environment.baseUrl(), concurrency)) {
                    var nextClient = new java.util.concurrent.atomic.AtomicInteger();
                    var workerClient = ThreadLocal.withInitial(() -> clients.values.get(nextClient.getAndIncrement()));
                    double cpuStart = environment.applicationCpuSeconds();
                    long start = System.nanoTime();
                    double seconds;
                    double cpuSeconds;
                    var lastSuccess = new java.util.concurrent.atomic.LongAccumulator(Long::max, 0);
                    var observer = new Observer(environment);
                    try (var executor = Executors.newFixedThreadPool(concurrency)) {
                        var jobs = new ArrayList<Callable<Attempt>>();
                        for (int i = 0; i < 1000; i++) { jobs.add(() -> {
                            var result = attempt(workerClient.get(), fixture);
                            if (result.outcome.equals("complete")) { lastSuccess.accumulate(System.nanoTime()); }
                            return result;
                        }); }
                        for (var result : executor.invokeAll(jobs)) { attempts.add(result.get()); }
                        seconds = elapsed(start) / 1000.0;
                        cpuSeconds = environment.applicationCpuSeconds() - cpuStart;
                    } finally { observer.close(); }
                    long deadlocks;
                    long rollbacks;
                    try (var connection = environment.connection()) {
                        deadlocks = count(connection, "select deadlocks from pg_stat_database where datname=current_database()");
                        rollbacks = count(connection, "select xact_rollback from pg_stat_database where datname=current_database()");
                    }
                    var snapshot = safety(environment, fixture, attempts, 100);
                    double successWindow = lastSuccess.get() == 0 ? Double.NaN : (lastSuccess.get() - start) / 1_000_000_000.0;
                    if (attempts.stream().allMatch(a -> Set.of("complete", "inventory_rejection").contains(a.outcome))) {
                        assertEquals(100, snapshot.complete, "No unexplained early inventory rejection");
                        assertEquals(0, snapshot.free);
                        assertEquals(0, snapshot.pending);
                    }
                    for (var outcome : List.of("complete", "inventory_rejection", "server_error", "unexpected_response", "client_error", "timeout")) {
                        var latencies = attempts.stream().filter(a -> a.outcome.equals(outcome)).mapToDouble(Attempt::millis).sorted().toArray();
                        double mean = Arrays.stream(latencies).average().orElse(Double.NaN);
                        double p95 = latencies.length == 0 ? Double.NaN : latencies[(int) Math.ceil(latencies.length * .95) - 1];
                        var row = String.format(Locale.ROOT, "%d,%d,1000,%.3f,%s,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%s,%d,%d,%d,%.3f,%d,%d,%d,%d,%d,%.3f%n",
                            concurrency, repeat, seconds, outcome, latencies.length, mean, p95, latencies.length / seconds, 1000 / seconds,
                            observer.samples, observer.peakActive, observer.waits.toString().replace(',', ';'), deadlocks, rollbacks,
                            clients.values.stream().mapToLong(worker -> worker.requests.get() - 1).sum(), cpuSeconds,
                            snapshot.complete, snapshot.acquired, snapshot.free, snapshot.pending, snapshot.unacknowledgedComplete, successWindow);
                        Files.writeString(output, row, java.nio.file.StandardOpenOption.APPEND); System.out.print(row);
                    }
                    attempts.stream().filter(a -> !Set.of("complete", "inventory_rejection").contains(a.outcome)).map(Attempt::failure).distinct().forEach(e -> System.out.println("Load failure: " + e));
                    environment.assertNoDataMigrationFailure();
                    if (attempts.stream().filter(a -> !Set.of("complete", "inventory_rejection").contains(a.outcome)).count() > 50) {
                        System.out.println("Escalation stopped: more than 5% unexpected failures; measured limit retained.");
                        return;
                    }
                    }
                }
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void inventoryBoundaryAndInvalidInput() throws Exception {
        try (var environment = start(); var client = new Client(environment.baseUrl())) {
            var fixture = fixture(environment, 1);
            var tooMany = client.reserve(fixture, 2);
            assertTrue(inventoryRejection(tooMany));
            try (var connection = environment.connection()) { assertEquals(1, count(connection, "select count(*) from ticket where status='FREE'")); assertEquals(0, count(connection, "select count(*) from tickets_reservation")); }
            var zero = client.reserve(fixture, 0);
            assertEquals(422, zero.status); assertTrue(errors(zero).contains("error.STEP_1_SELECT_AT_LEAST_ONE")); assertFalse(inventoryRejection(zero));
            var negative = client.reserve(fixture, -1);
            assertEquals(422, negative.status); assertTrue(errors(negative).contains("error.STEP_1_SELECT_AT_LEAST_ONE"));
            var success = attempt(client, fixture);
            assertEquals("complete", success.outcome);
            assertTrue(inventoryRejection(client.reserve(fixture, 1)));
            safety(environment, fixture, List.of(success), 1);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void retransmissionPreservesExistingContract() throws Exception {
        try (var environment = start(); var client = new Client(environment.baseUrl())) {
            var fixture = fixture(environment, 2);
            var first = client.reserve(fixture, 1); requireOk(first);
            var second = client.reserve(fixture, 1); requireOk(second);
            String firstId = first.body.path("value").asString();
            String secondId = second.body.path("value").asString();
            assertNotEquals(firstId, secondId, "Create API has no idempotency key; each submission is a new reservation");
            var firstTicket = client.complete(firstId);
            var replay = client.request("reservation/" + firstId + "?lang=en", PAYMENT);
            requireOk(replay); // A valid, already COMPLETE free reservation is a successful replay.
            System.out.println("Confirmation replay: HTTP " + replay.status + " codes=" + errors(replay));
            var secondTicket = client.complete(secondId);
            assertTrue(inventoryRejection(client.reserve(fixture, 1)));
            safety(environment, fixture, List.of(new Attempt("complete", 0, firstId, firstTicket, ""), new Attempt("complete", 0, secondId, secondTicket, "")), 2);
        }
    }
}
