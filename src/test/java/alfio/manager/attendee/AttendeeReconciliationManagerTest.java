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
import alfio.repository.AttendeeReconciliationRepository;
import alfio.repository.AttendeeReconciliationRepository.Stored;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.time.ZonedDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttendeeReconciliationManagerTest {
    private final ReconciliationStore store=mock(ReconciliationStore.class);
    private final AttendeeAssistantSettings settings=mock(AttendeeAssistantSettings.class);
    private final OpenAiDraftClient ai=mock(OpenAiDraftClient.class);
    private final AttendeeReconciliationRepository repository=mock(AttendeeReconciliationRepository.class);
    private final Result result=new AttendeeComparator().compare(
        List.of(new AttendeeCsvParser.Row(2,"10000000-0000-0000-0000-000000000001","synthetic@example.invalid")),List.of());
    private final Stored stored=new Stored("id",ZonedDateTime.now(),ZonedDateTime.now().plusDays(1),"encrypted","{}");
    private AttendeeReconciliationManager manager;
    @BeforeEach void setup() {
        manager=new AttendeeReconciliationManager(new AttendeeCsvParser(),new AttendeeComparator(),repository,store,mock(ReconciliationCipher.class),settings,ai);
        when(settings.aiAvailable()).thenReturn(true);
        when(store.find("id",42,"owner")).thenReturn(stored);
        when(store.decode(stored,42,"owner")).thenReturn(result);
    }
    @Test void rejectsUnverifiedReferencesDuplicatesAndEmptySelectionBeforeSpendingQuota() {
        for(var references:List.of(List.<String>of(),List.of("F-999"),List.of("F-1","F-1"))) {
            assertEquals("INVALID_SELECTION",assertThrows(ReconciliationException.class,()->manager.draft("id",42,"owner",new DraftRequest(references))).getMessage());
        }
        verify(store,never()).reserveAi(anyString(),anyInt(),anyString());verifyNoInteractions(ai);
    }
    @Test void preservesStoredComparisonAndReleasesLeaseOnTimeout() {
        when(ai.generate(any())).thenThrow(new ReconciliationException(HttpStatus.GATEWAY_TIMEOUT,"AI_TIMEOUT"));
        assertEquals("AI_TIMEOUT",assertThrows(ReconciliationException.class,()->manager.draft("id",42,"owner",new DraftRequest(List.of("F-1")))).getMessage());
        verify(store).reserveAi("id",42,"owner");verify(store).releaseAi("id",42,"owner");
        verify(store,never()).delete(anyString(),anyInt(),anyString());
        assertEquals(result.summary(),manager.page("id",42,"owner",0,50,"ALL").header().summary());
    }
    @Test void rejectsInvalidPagingAndFiltersAndSupportsEmptyPages() {
        assertThrows(ReconciliationException.class,()->manager.page("id",42,"owner",-1,50,"ALL"));
        assertThrows(ReconciliationException.class,()->manager.page("id",42,"owner",0,101,"ALL"));
        assertThrows(ReconciliationException.class,()->manager.page("id",42,"owner",0,50,"not-a-filter"));
        assertTrue(manager.page("id",42,"owner",Integer.MAX_VALUE,100,"ALL").items().isEmpty());
        assertEquals(1,manager.page("id",42,"owner",0,50,"CSV_ONLY").total());
        assertEquals(0,manager.page("id",42,"owner",0,50,"MATCHED").total());
    }
    @Test void disabledAiDoesNotReadOrModifyComparison() {
        when(settings.aiAvailable()).thenReturn(false);
        assertEquals("AI_NOT_CONFIGURED",assertThrows(ReconciliationException.class,()->manager.draft("id",42,"owner",new DraftRequest(List.of("F-1")))).getMessage());
        verifyNoInteractions(store,ai);
    }
}
