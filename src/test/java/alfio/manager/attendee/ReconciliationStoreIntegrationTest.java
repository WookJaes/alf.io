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

import alfio.TestConfiguration;
import alfio.config.DataSourceConfiguration;
import alfio.config.Initializer;
import alfio.manager.EventManager;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.model.attendee.Reconciliation.*;
import alfio.repository.*;
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.test.util.IntegrationTestUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import static org.junit.jupiter.api.Assertions.*;

@AlfioIntegrationTest
@ContextConfiguration(classes={DataSourceConfiguration.class,TestConfiguration.class})
@ActiveProfiles({Initializer.PROFILE_DEV,Initializer.PROFILE_DISABLE_JOBS,Initializer.PROFILE_INTEGRATION_TEST})
@TestPropertySource(properties="alfio.attendee-assistant.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class ReconciliationStoreIntegrationTest {
    @Autowired private ConfigurationRepository configuration;
    @Autowired private OrganizationRepository organizations;
    @Autowired private UserManager users;
    @Autowired private EventManager events;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventDeleterRepository deleter;
    @Autowired private ReconciliationStore store;
    @Autowired private AttendeeReconciliationRepository repository;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    private Event event;
    private String owner;
    private final Result result=new AttendeeComparator().compare(List.of(new AttendeeCsvParser.Row(2,"10000000-0000-0000-0000-000000000001","synthetic@example.invalid")),List.of());

    @BeforeEach void setup() {
        IntegrationTestUtil.ensureMinimalConfiguration(configuration);
        var created=IntegrationTestUtil.initEvent(List.of(),organizations,users,events,eventRepository);
        event=created.getLeft();owner=IntegrationTestUtil.owner(created.getRight());
    }
    @AfterEach void cleanup() { if(event!=null) deleter.deleteAllForEvent(event.getId()); }

    @Test void storesEncryptedEvidenceSeparatesUserAndEventAndExpiresThenDeletes() {
        var stored=store.save(event.getId(),owner,result);
        assertFalse(stored.encryptedResult().contains("synthetic@example.invalid"));
        assertEquals(result,store.decode(store.find(stored.id(),event.getId(),owner),event.getId(),owner));
        assertEquals(1,store.list(event.getId(),owner).size());
        assertEquals("",store.list(event.getId(),owner).getFirst().encryptedResult());
        assertThrows(ReconciliationException.class,()->store.find(stored.id(),event.getId(),"other-user"));
        assertThrows(ReconciliationException.class,()->store.find(stored.id(),-1,owner));
        assertEquals(1,jdbc.update("update attendee_reconciliation set expires_at=current_timestamp - interval '1 second' where id=:id",Map.of("id",stored.id())));
        assertThrows(ReconciliationException.class,()->store.find(stored.id(),event.getId(),owner));
        assertTrue(store.list(event.getId(),owner).isEmpty());assertTrue(repository.deleteExpired()>=1);
        assertEquals(0,jdbc.queryForObject("select count(*) from attendee_reconciliation where id=:id",Map.of("id",stored.id()),Integer.class));
    }
    @Test void limitsHistoryAndAllowsExplicitDeletionAndEventCascade() {
        String first=null;
        for(int i=0;i<10;i++) {var saved=store.save(event.getId(),owner,result);if(first==null)first=saved.id();}
        assertEquals("STORAGE_LIMIT",assertThrows(ReconciliationException.class,()->store.save(event.getId(),owner,result)).getMessage());
        store.delete(first,event.getId(),"other-user");assertEquals(10,store.list(event.getId(),owner).size());
        store.delete(first,event.getId(),owner);assertEquals(9,store.list(event.getId(),owner).size());
        store.save(event.getId(),owner,result);assertEquals(10,store.list(event.getId(),owner).size());
        deleter.deleteAllForEvent(event.getId());
        assertEquals(0,jdbc.queryForObject("select count(*) from attendee_reconciliation where event_id_fk=:event",Map.of("event",event.getId()),Integer.class));event=null;
    }
    @Test void persistsCallQuotaAndRollsBackBusyLeaseWhenQuotaIsRejected() {
        var saved=store.save(event.getId(),owner,result);
        for(int i=0;i<10;i++){store.reserveAi(saved.id(),event.getId(),owner);store.releaseAi(saved.id(),event.getId(),owner);}
        assertEquals("AI_RATE_LIMIT",assertThrows(ReconciliationException.class,()->store.reserveAi(saved.id(),event.getId(),owner)).getMessage());
        assertNull(jdbc.queryForObject("select ai_locked_until from attendee_reconciliation where id=:id",Map.of("id",saved.id()),java.sql.Timestamp.class));
    }
    @Test void concurrentRequestsAcquireOnlyOneLeaseAcrossTransactions() throws Exception {
        var saved=store.save(event.getId(),owner,result);
        try(var executor=Executors.newFixedThreadPool(4)) {
            List<Callable<String>> attempts=java.util.stream.IntStream.range(0,4).mapToObj(i -> (Callable<String>)()->{
                try{store.reserveAi(saved.id(),event.getId(),owner);return "acquired";}catch(ReconciliationException e){return e.getMessage();}
            }).toList();
            var values=executor.invokeAll(attempts).stream().map(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).toList();
            assertEquals(1,values.stream().filter("acquired"::equals).count());assertEquals(3,values.stream().filter("AI_BUSY"::equals).count());
        }finally{store.releaseAi(saved.id(),event.getId(),owner);}
    }
}
