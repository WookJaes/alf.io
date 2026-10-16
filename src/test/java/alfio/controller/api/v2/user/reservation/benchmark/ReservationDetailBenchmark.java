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
package alfio.controller.api.v2.user.reservation.benchmark;

import alfio.TestConfiguration;
import alfio.config.DataSourceConfiguration;
import alfio.config.Initializer;
import alfio.controller.api.ControllerConfiguration;
import alfio.manager.EventManager;
import alfio.manager.user.UserManager;
import alfio.model.*;
import alfio.model.metadata.AlfioMetadata;
import alfio.model.modification.DateTimeModification;
import alfio.model.modification.TicketCategoryModification;
import alfio.repository.*;
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.util.ClockProvider;
import alfio.util.Json;
import alfio.util.RefreshableDataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static alfio.model.system.ConfigurationKeys.*;
import static alfio.test.util.IntegrationTestUtil.*;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 HTTP 요청을 사용하는 선택 실행 벤치마크. 제품 코드에는 계측을 추가하지 않는다. */
@AlfioIntegrationTest
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class, ControllerConfiguration.class,
    ReservationDetailBenchmark.MeterConfiguration.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
@EnabledIfSystemProperty(named = "benchmark.mode", matches = "sql|time")
class ReservationDetailBenchmark {
    @LocalServerPort int port;
    @Autowired OrganizationRepository organizations;
    @Autowired UserManager users;
    @Autowired EventManager events;
    @Autowired EventRepository eventRepository;
    @Autowired TicketCategoryRepository categories;
    @Autowired TicketReservationRepository reservations;
    @Autowired ConfigurationRepository configuration;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired ClockProvider clock;
    @Autowired RefreshableDataSource dataSource;
    @Autowired TicketRepository tickets;
    @Autowired alfio.repository.user.UserRepository userRepository;
    @Autowired AdditionalServiceRepository services;
    @Autowired AdditionalServiceItemRepository items;
    @Autowired alfio.controller.api.v2.user.ReservationApiV2Controller controller;
    @Autowired alfio.controller.api.support.BookingInfoTicketLoader loader;

    static final List<String> SQL = new CopyOnWriteArrayList<>();
    static volatile boolean counting;
    record Fixture(Event event, String reservation, int tickets, int categoryCount) {}

    @Test
    void measureReservationDetails() throws Exception {
        ensureMinimalConfiguration(configuration);
        var pool = (com.zaxxer.hikari.HikariDataSource) dataSource.getTargetDataSource();
        var environment = new LinkedHashMap<String, Object>();
        environment.put("java", System.getProperty("java.runtime.version"));
        environment.put("jvm", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .filter(a -> a.startsWith("-X") || a.equals("-ea") || a.startsWith("-Duser.timezone=") || a.startsWith("-Dfile.encoding=")).toList());
        environment.put("postgres", jdbc.queryForMap("select version() as version, current_setting('shared_buffers') as shared_buffers, current_setting('max_connections') as max_connections, current_setting('jit') as jit, current_setting('fsync') as fsync, current_setting('synchronous_commit') as synchronous_commit, current_setting('timezone') as timezone", Map.of()));
        environment.put("hikari", Map.of("maxPoolSize", pool.getMaximumPoolSize(), "minIdle", pool.getMinimumIdle(), "connectionTimeoutMs", pool.getConnectionTimeout(), "idleTimeoutMs", pool.getIdleTimeout(), "maxLifetimeMs", pool.getMaxLifetime(), "autoCommit", pool.isAutoCommit()));
        Files.writeString(Path.of("build/issue35-environment-"+System.getProperty("benchmark.phase")+"-"+System.getProperty("benchmark.mode")+".json"), Json.OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(environment));
        var fixtures = new ArrayList<Fixture>();
        for (int[] condition : new int[][]{{1,1},{5,1},{20,1},{20,2},{20,4}}) {
            fixtures.add(fixture(condition[0], condition[1]));
        }
        String phase = System.getProperty("benchmark.phase");
        boolean sql = "sql".equals(System.getProperty("benchmark.mode"));
        var results = new ArrayList<Map<String, Object>>();
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            for (var f : fixtures) {
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v2/public/reservation/" + f.reservation))
                    .timeout(Duration.ofSeconds(30)).GET().build();
                for (int i = 0; i < 10; i++) validate(client.send(request, HttpResponse.BodyHandlers.ofString()), f);
                var row = new LinkedHashMap<String, Object>();
                row.put("tickets", f.tickets); row.put("categories", f.categoryCount);
                if (sql) {
                    SQL.clear(); counting = true;
                    HttpResponse<String> response;
                    try { response = client.send(request, HttpResponse.BodyHandlers.ofString()); }
                    finally { counting = false; }
                    validate(response, f);
                    row.put("totalSql", SQL.size());
                    row.put("configurationSql", SQL.stream().filter(s -> s.contains("configuration_ticket_category")).count());
                    row.put("categorySql", SQL.stream().filter(s -> s.startsWith("select * from ticket_category_with_currency where id =") && s.contains("tc_status")).count());
                    row.put("eventCommonSql", SQL.stream().filter(s -> s.startsWith("select count(*) from ticket_category_with_currency") && s.contains("bounded = false")).count());
                    var snapshot = Path.of("src/test/resources/benchmark/issue35/" + f.tickets + "-" + f.categoryCount + ".json");
                    // 예약/티켓 UUID를 fixture에서 고정했으므로 전체 JSON을 그대로 비교한다.
                    var body = Json.OBJECT_MAPPER.readTree(response.body());
                    if ("baseline".equals(phase)) Files.writeString(snapshot, Json.OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(body) + "\n");
                    else assertEquals(Json.OBJECT_MAPPER.readTree(Files.readString(snapshot)), body);
                } else {
                    var times = new ArrayList<Double>();
                    int failures = 0;
                    var failureKinds = new TreeMap<String, Integer>();
                    for (int i = 0; i < 100; i++) {
                        // 요청 생성·fixture·로그인·JSON 검증은 측정 구간 밖이다.
                        long start = System.nanoTime();
                        try {
                            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                            double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                            validate(response, f);
                            times.add(elapsed);
                        } catch (Exception | AssertionError failure) {
                            failures++;
                            failureKinds.merge(failure.getClass().getSimpleName(), 1, Integer::sum);
                        }
                    }
                    Collections.sort(times);
                    row.put("successes", times.size()); row.put("failures", failures); row.put("failureKinds", failureKinds);
                    if (!times.isEmpty()) {
                        int n = times.size();
                        row.put("medianMs", n % 2 == 0 ? (times.get(n/2-1)+times.get(n/2))/2 : times.get(n/2));
                        row.put("p95Ms", times.get((int)Math.ceil(.95*n)-1));
                    }
                }
                results.add(row);
            }
        }
        if (sql) verifyPolicies(fixtures.getLast(), phase);
        var output = Path.of("build/issue35-" + phase + "-" + System.getProperty("benchmark.mode") + ".json");
        Files.writeString(output, Json.OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        assertTrue(results.stream().allMatch(r -> !r.containsKey("failures") || r.get("failures").equals(0)), "실패 요청 원인 확인 필요");
    }

    Fixture fixture(int ticketCount, int categoryCount) {
        var now = ZonedDateTime.now(clock.getClock());
        var mods = new ArrayList<TicketCategoryModification>();
        for (int i = 0; i < categoryCount; i++) {
            mods.add(new TicketCategoryModification(null, "category-" + i, TicketCategory.TicketAccessType.INHERIT, 20/categoryCount,
                new DateTimeModification(now.toLocalDate().minusDays(1), now.toLocalTime()),
                new DateTimeModification(now.toLocalDate().plusDays(1), now.toLocalTime()),
                DESCRIPTION, BigDecimal.ZERO, i % 2 == 1, "", true, null, null, null, null, null, i, null, null, AlfioMetadata.empty()));
        }
        var event = initEvent(mods, organizations, users, events, eventRepository).getLeft();
        // 응답에 사용되는 시간/UUID를 고정하고 구매 제한을 거치지 않는 합성 예약을 만든다.
        String reservation = UUID.nameUUIDFromBytes(("issue35-"+ticketCount+"-"+categoryCount).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        reservations.createNewReservation(reservation, now, java.util.Date.from(Instant.parse("2026-10-17T10:00:00Z")), null, "en", event.getId(), BigDecimal.ONE, true, "CHF", event.getOrganizationId(), null);
        reservations.updateReservationStatus(reservation, "COMPLETE");
        configuration.insertOrganizationLevel(event.getOrganizationId(), ALLOW_FREE_TICKETS_CANCELLATION.name(), "true", "benchmark");
        configuration.insertEventLevel(event.getOrganizationId(), event.getId(), SEND_TICKETS_AUTOMATICALLY.name(), "true", "benchmark");
        configuration.insertEventLevel(event.getOrganizationId(), event.getId(), ALLOW_TICKET_DOWNLOAD.name(), "true", "benchmark");
        var cats = categories.findAllTicketCategories(event.getId());
        for (int i = 0; i < cats.size(); i++) {
            var category = cats.get(i);
            if (i % 2 == 1) {
                configuration.insertTicketCategoryLevel(event.getOrganizationId(), event.getId(), category.getId(), SEND_TICKETS_AUTOMATICALLY.name(), "false", "benchmark");
                configuration.insertTicketCategoryLevel(event.getOrganizationId(), event.getId(), category.getId(), ALLOW_TICKET_DOWNLOAD.name(), "false", "benchmark");
            }
            var ids = jdbc.queryForList("select id from ticket where category_id=:cat order by id limit :n", Map.of("cat", category.getId(), "n", ticketCount/categoryCount), Integer.class);
            for (int j = 0; j < ids.size(); j++) {
                jdbc.update("update ticket set tickets_reservation_id=:res, status='ACQUIRED', final_price_cts=0, src_price_cts=0, uuid=:uuid, public_uuid=:uuid::uuid, user_language='en' where id=:id",
                    Map.of("res", reservation, "id", ids.get(j), "uuid", UUID.nameUUIDFromBytes((reservation+"-"+i+"-"+j).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()));
            }
        }
        return new Fixture(event, reservation, ticketCount, categoryCount);
    }

    void validate(HttpResponse<String> response, Fixture fixture) {
        assertEquals(200, response.statusCode());
        var body = Json.OBJECT_MAPPER.readTree(response.body());
        assertEquals(fixture.reservation, body.get("id").asText());
        int count = 0;
        for (var category : body.get("ticketsByCategory")) {
            int index = Integer.parseInt(category.get("name").asText().substring("category-".length()));
            for (var ticket : category.get("tickets")) {
                count++;
                assertEquals(index % 2 == 0, ticket.get("cancellationEnabled").asBoolean());
                assertEquals(index % 2 == 0, ticket.get("sendMailEnabled").asBoolean());
                assertEquals(index % 2 == 0, ticket.get("downloadEnabled").asBoolean());
                assertTrue(ticket.get("acquired").asBoolean());
            }
        }
        assertEquals(fixture.tickets, count);
    }

    void verifyPolicies(Fixture f, String phase) throws Exception {
        var snapshots = new LinkedHashMap<String, Object>();
        var event = f.event;
        var publicCategory = categories.findAllTicketCategories(event.getId()).getFirst();
        Map<String, Object> args = Map.of("res", f.reservation, "event", event.getId(), "cat", publicCategory.getId());
        checkPolicy(f, "free-bounded", i -> i % 2 == 0, snapshots);
        jdbc.update("update ticket set final_price_cts=100, src_price_cts=100, vat_status='INCLUDED' where tickets_reservation_id=:res", args);
        checkPolicy(f, "paid-ticket", i -> false, snapshots);
        jdbc.update("update ticket set final_price_cts=0, status='CHECKED_IN' where tickets_reservation_id=:res", args);
        checkPolicy(f, "checked-in", i -> false, snapshots);
        jdbc.update("update ticket set status='TO_BE_PAID' where tickets_reservation_id=:res", args);
        checkPolicy(f, "to-be-paid", i -> false, snapshots);
        jdbc.update("update ticket set status='ACQUIRED' where tickets_reservation_id=:res", args);
        jdbc.update("update ticket_category set bounded=false where id=:cat", args);
        checkPolicy(f, "unbounded-present", i -> true, snapshots);
        // 같은 요청/로더에서 설정을 공유해도 다음 요청의 수정 값이 보이는지 확인한다.
        configuration.insertTicketCategoryLevel(event.getOrganizationId(), event.getId(), publicCategory.getId(), ALLOW_FREE_TICKETS_CANCELLATION.name(), "false", "benchmark");
        checkPolicy(f, "category-cancellation-override", i -> i != 0, snapshots);
        jdbc.update("delete from configuration_ticket_category where ticket_category_id_fk=:cat and c_key='ALLOW_FREE_TICKETS_CANCELLATION'", args);
        var now = ZonedDateTime.now(clock.getClock());
        int service = services.insert(event.getId(), 100, true, 0, 20, 1, now.minusDays(1), now.plusDays(1), BigDecimal.ZERO,
            AdditionalService.VatType.NONE, AdditionalService.AdditionalServiceType.SUPPLEMENT,
            AdditionalService.SupplementPolicy.OPTIONAL_MAX_AMOUNT_PER_RESERVATION, null, null).getKey();
        for (String locale : List.of("en", "de", "it")) {
            jdbc.update("insert into additional_service_description(additional_service_id_fk, locale, type, value) values(:id,:locale,'TITLE','synthetic supplement')", Map.of("id", service, "locale", locale));
        }
        items.insert(UUID.nameUUIDFromBytes("issue35-supplement".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), now, f.reservation,
            service, AdditionalServiceItem.AdditionalServiceItemStatus.ACQUIRED, event.getId(), 100, 100, 0, 0, "CHF");
        checkPolicy(f, "paid-supplement", i -> false, snapshots);
        var ticket = tickets.findTicketsInReservation(f.reservation).getFirst();
        assertFalse(loader.toBookingInfoTicket(ticket, event, Set.of(PurchaseContextFieldConfiguration.Context.ATTENDEE)).isCancellationEnabled());
        jdbc.update("update additional_service_item set final_price_cts=0 where tickets_reservation_uuid=:res", args);
        checkPolicy(f, "free-supplement", i -> true, snapshots);
        assertTrue(loader.toBookingInfoTicket(ticket, event, Set.of(PurchaseContextFieldConfiguration.Context.ATTENDEE)).isCancellationEnabled());
        jdbc.update("update event set end_ts=:end where id=:id", Map.of("id", event.getId(), "end", java.sql.Timestamp.from(now.minusDays(2).toInstant())));
        checkPolicy(f, "expired-event", i -> false, snapshots);
        // 익명·소유자·잘못된 사용자 접근은 기존 검사로 처리한다.
        int owner = jdbc.queryForObject("select id from ba_user order by id limit 1", Map.of(), Integer.class);
        String ownerName = jdbc.queryForObject("select username from ba_user where id=:id", Map.of("id", owner), String.class);
        reservations.setReservationOwner(f.reservation, owner);
        assertThrows(alfio.controller.api.v2.user.support.ReservationAccessDenied.class, () -> controller.getReservationInfo(f.reservation, null));
        assertThrows(alfio.controller.api.v2.user.support.ReservationAccessDenied.class, () -> controller.getReservationInfo(f.reservation, () -> "unknown-synthetic-user"));
        assertEquals(200, controller.getReservationInfo(f.reservation, () -> ownerName).getStatusCode().value());
        var path = Path.of("src/test/resources/benchmark/issue35/policies.json");
        var tree = Json.OBJECT_MAPPER.valueToTree(snapshots);
        if ("baseline".equals(phase)) Files.writeString(path, Json.OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree)+"\n");
        else assertEquals(Json.OBJECT_MAPPER.readTree(Files.readString(path)), tree);
    }

    void checkPolicy(Fixture f, String name, java.util.function.IntPredicate cancellation, Map<String, Object> snapshots) {
        var response = controller.getReservationInfo(f.reservation, null);
        assertEquals(200, response.getStatusCode().value());
        var body = Json.OBJECT_MAPPER.valueToTree(response.getBody());
        int count = 0;
        for (var category : body.get("ticketsByCategory")) {
            int index = Integer.parseInt(category.get("name").asText().substring("category-".length()));
            for (var ticket : category.get("tickets")) {
                count++;
                assertEquals(cancellation.test(index), ticket.get("cancellationEnabled").asBoolean(), name);
                assertEquals(index % 2 == 0, ticket.get("sendMailEnabled").asBoolean(), name);
                assertEquals(index % 2 == 0, ticket.get("downloadEnabled").asBoolean(), name);
            }
        }
        assertEquals(f.tickets, count);
        snapshots.put(name, body);
    }

    @Configuration(proxyBeanMethods = false)
    static class MeterConfiguration {
        @Bean static BeanPostProcessor jdbcMeter() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof RefreshableDataSource) || !"sql".equals(System.getProperty("benchmark.mode"))) return bean;
                    var factory = new ProxyFactory(bean);
                    factory.setProxyTargetClass(true);
                    factory.addAdvice((MethodInterceptor) invocation -> {
                        var result = invocation.proceed();
                        return result instanceof Connection c && counting ? connection(c) : result;
                    });
                    return factory.getProxy();
                }
            };
        }
    }
    static Object call(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    static Connection connection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p,m,a) -> {
            var result = call(connection,m,a);
            if (result instanceof Statement statement) {
                String sql = a != null && a.length > 0 && a[0] instanceof String s ? s : "";
                Class<?> type = statement instanceof CallableStatement ? CallableStatement.class : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (sp,sm,sa) -> {
                    if (counting && sm.getName().startsWith("execute")) {
                        String text = sql.isEmpty() && sa != null && sa.length > 0 && sa[0] instanceof String s ? s : sql;
                        SQL.add(text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim());
                    }
                    return call(statement,sm,sa);
                });
            }
            return result;
        });
    }
}
