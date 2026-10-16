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
package alfio.controller.api.support;

import alfio.manager.EventManager;
import alfio.manager.system.ConfigurationLevel;
import alfio.manager.system.ConfigurationManager;
import alfio.model.Event;
import alfio.model.PurchaseContextFieldConfiguration;
import alfio.model.Ticket;
import alfio.model.system.ConfigurationKeys;
import alfio.util.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static alfio.model.system.ConfigurationKeys.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingInfoTicketLoaderTest {
    private final EventManager events = mock(EventManager.class);
    private final ConfigurationManager configuration = mock(ConfigurationManager.class);
    private final Event event = mock(Event.class);
    private final Validator.AdditionalFieldsFilterer fields = mock(Validator.AdditionalFieldsFilterer.class);
    private final BookingInfoTicketLoader loader = new BookingInfoTicketLoader(events, configuration, null, null, null, null, null);

    @BeforeEach
    void setup() {
        when(events.checkTicketCancellationPrerequisites()).thenReturn(t -> t.getStatus() == Ticket.TicketStatus.ACQUIRED);
        when(configuration.getFor(anyCollection(), any(ConfigurationLevel.class))).thenAnswer(invocation -> {
            ConfigurationLevel level = invocation.getArgument(1);
            boolean enabled = level.getTicketCategoryId().orElseThrow() == 1;
            var value = mock(ConfigurationManager.MaybeConfiguration.class);
            when(value.getValueAsBooleanOrDefault()).thenReturn(enabled);
            return Map.of(ALLOW_FREE_TICKETS_CANCELLATION, value, SEND_TICKETS_AUTOMATICALLY, value, ALLOW_TICKET_DOWNLOAD, value);
        });
    }

    private Ticket ticket(int category, int price, Ticket.TicketStatus status) {
        var ticket = mock(Ticket.class);
        when(ticket.getPublicUuid()).thenReturn(UUID.randomUUID());
        when(ticket.getCategoryId()).thenReturn(category);
        when(ticket.getFinalPriceCts()).thenReturn(price);
        when(ticket.getStatus()).thenReturn(status);
        return ticket;
    }

    private Function<Ticket, BookingInfoTicket> mapper(boolean paidSupplement) {
        return loader.bookingInfoTicketMapper(paidSupplement, event, fields, Map.of(), Map.of(), Map.of(), false,
            Set.of(PurchaseContextFieldConfiguration.Context.ATTENDEE));
    }

    @Test
    void reusesSettingsPerCategoryWithoutMixingPolicies() {
        var mapper = mapper(false);
        for (int i = 0; i < 20; i++) {
            int category = i % 2 + 1;
            var result = mapper.apply(ticket(category, 0, Ticket.TicketStatus.ACQUIRED));
            assertEquals(category == 1, result.isCancellationEnabled());
            assertEquals(category == 1, result.isSendMailEnabled());
            assertEquals(category == 1, result.isDownloadEnabled());
        }
        verify(configuration, times(2)).getFor(eq(EnumSet.of(ALLOW_FREE_TICKETS_CANCELLATION, SEND_TICKETS_AUTOMATICALLY, ALLOW_TICKET_DOWNLOAD)), any(ConfigurationLevel.class));
        verify(events, times(1)).checkTicketCancellationPrerequisites();
        verify(fields, times(20)).getFieldsForTicket(any(), anySet());
    }

    @Test
    void checksPriceAndStatusForEachTicketWithinTheSameCategory() {
        var mapper = mapper(false);
        assertTrue(mapper.apply(ticket(1, 0, Ticket.TicketStatus.ACQUIRED)).isCancellationEnabled());
        assertFalse(mapper.apply(ticket(1, 100, Ticket.TicketStatus.ACQUIRED)).isCancellationEnabled());
        assertFalse(mapper.apply(ticket(1, 0, Ticket.TicketStatus.CHECKED_IN)).isCancellationEnabled());
        assertTrue(mapper.apply(ticket(1, 0, Ticket.TicketStatus.ACQUIRED)).isCancellationEnabled());
        verify(configuration, times(1)).getFor(anyCollection(), any(ConfigurationLevel.class));
    }

    @Test
    void respectsPaidSupplementsAndEventExpiration() {
        var ticket = ticket(1, 0, Ticket.TicketStatus.ACQUIRED);
        assertFalse(mapper(true).apply(ticket).isCancellationEnabled());
        when(event.expired()).thenReturn(true);
        assertFalse(mapper(false).apply(ticket).isCancellationEnabled());
    }

    @Test
    void newRequestReadsSettingsAgain() {
        var ticket = ticket(1, 0, Ticket.TicketStatus.ACQUIRED);
        assertTrue(mapper(false).apply(ticket).isCancellationEnabled());
        var disabled = mock(ConfigurationManager.MaybeConfiguration.class);
        when(configuration.getFor(anyCollection(), any(ConfigurationLevel.class))).thenReturn(Map.of(
            ALLOW_FREE_TICKETS_CANCELLATION, disabled, SEND_TICKETS_AUTOMATICALLY, disabled, ALLOW_TICKET_DOWNLOAD, disabled));
        assertFalse(mapper(false).apply(ticket).isCancellationEnabled());
        verify(configuration, times(2)).getFor(anyCollection(), any(ConfigurationLevel.class));
    }

    @Test
    void preservesExistingSingleTicketOverload() {
        var ticket = ticket(1, 0, Ticket.TicketStatus.ACQUIRED);
        assertTrue(loader.toBookingInfoTicket(ticket, false, event, fields, Map.of(), Map.of(), Map.of(), false,
            Set.of(PurchaseContextFieldConfiguration.Context.ATTENDEE)).isCancellationEnabled());
    }
}
