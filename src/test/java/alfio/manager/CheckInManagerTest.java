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
package alfio.manager;

import alfio.manager.support.CheckInStatistics;
import alfio.manager.system.ConfigurationLevel;
import alfio.manager.system.ConfigurationManager;
import alfio.model.Event;
import alfio.model.Ticket;
import alfio.model.TicketReservation;
import alfio.model.transaction.PaymentProxy;
import alfio.model.Audit;
import alfio.model.audit.ScanAudit;
import alfio.repository.TicketRepository;
import alfio.repository.TicketReservationRepository;
import alfio.repository.audit.ScanAuditRepository;
import alfio.repository.AuditingRepository;
import alfio.repository.user.UserRepository;
import alfio.util.ClockProvider;
import alfio.model.system.ConfigurationKeyValuePathLevel;
import alfio.model.user.Organization;
import alfio.repository.EventRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.TestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Optional;

import static alfio.model.system.ConfigurationKeys.CHECK_IN_STATS;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static alfio.manager.support.CheckInStatus.*;
import static org.mockito.Mockito.*;

class CheckInManagerTest {

    private EventRepository eventRepository;
    private ConfigurationManager configurationManager;
    private CheckInManager checkInManager;

    private static final String EVENT_NAME = "eventName";
    private static final String USERNAME = "username";
    private static final int EVENT_ID = 0;
    private static final int ORG_ID = 1;


    @BeforeEach
    public void setUp() {
        eventRepository = mock(EventRepository.class);
        configurationManager = mock(ConfigurationManager.class);
        OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
        Event event = mock(Event.class);
        Organization organization = mock(Organization.class);
        ConfigurationLevel cl = ConfigurationLevel.event(event);
        when(event.getConfigurationLevel()).thenReturn(cl);
        when(eventRepository.findOptionalByShortName(EVENT_NAME)).thenReturn(Optional.of(event));
        when(event.getId()).thenReturn(EVENT_ID);
        when(event.getOrganizationId()).thenReturn(ORG_ID);
        when(organizationRepository.findOrganizationForUser(USERNAME, ORG_ID)).thenReturn(Optional.of(organization));
        when(organization.getId()).thenReturn(ORG_ID);
        when(eventRepository.retrieveCheckInStatisticsForEvent(eq(EVENT_ID), isNull())).thenReturn(new CheckInStatistics(0, 0, new Date()));
        checkInManager = new CheckInManager(null, eventRepository, null, null, null, null,
            null, configurationManager, organizationRepository, null, null, null, null, null, TestUtil.clockProvider(), null);
    }

    @Test
    void getStatistics() {
        when(configurationManager.getFor(eq(CHECK_IN_STATS), any(ConfigurationLevel.class)))
            .thenReturn(new ConfigurationManager.MaybeConfiguration(CHECK_IN_STATS, new ConfigurationKeyValuePathLevel(null, "true", null)));
        CheckInStatistics statistics = checkInManager.getStatistics(EVENT_NAME, null, USERNAME);
        assertNotNull(statistics);
        verify(eventRepository).retrieveCheckInStatisticsForEvent(eq(EVENT_ID), isNull());
    }

    @Test
    void getStatisticsDisabled() {
        when(configurationManager.getFor(eq(CHECK_IN_STATS), any(ConfigurationLevel.class)))
            .thenReturn(new ConfigurationManager.MaybeConfiguration(CHECK_IN_STATS, new ConfigurationKeyValuePathLevel(null, "false", null)));
        CheckInStatistics statistics = checkInManager.getStatistics(EVENT_NAME, null, USERNAME);
        assertNull(statistics);
        verify(eventRepository, never()).retrieveCheckInStatisticsForEvent(eq(EVENT_ID), isNull());
    }


    @Test
    void manualCheckInUsesTheSameFixedInstantForBothAuditRecords() {
        verifyAuditClock(false);
    }

    @Test
    void revertCheckInUsesTheSameFixedInstantForBothAuditRecords() {
        verifyAuditClock(true);
    }

    private void verifyAuditClock(boolean revert) {
        var fixedClock = Clock.fixed(Instant.parse("2024-01-01T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        var clockProvider = mock(ClockProvider.class);
        when(clockProvider.getClock()).thenReturn(fixedClock);
        var ticketRepository = mock(TicketRepository.class);
        var reservationRepository = mock(TicketReservationRepository.class);
        var scanAuditRepository = mock(ScanAuditRepository.class);
        var auditingRepository = mock(AuditingRepository.class);
        var userRepository = mock(UserRepository.class);
        var extensionManager = mock(ExtensionManager.class);
        var event = mock(Event.class);
        var ticket = mock(Ticket.class);
        when(ticket.getUuid()).thenReturn("ticket");
        when(ticket.getId()).thenReturn(1);
        when(ticket.getCategoryId()).thenReturn(1);
        when(ticket.getEventId()).thenReturn(EVENT_ID);
        when(ticket.getTicketsReservationId()).thenReturn("reservation");
        when(ticket.getStatus()).thenReturn(revert ? Ticket.TicketStatus.CHECKED_IN : Ticket.TicketStatus.ACQUIRED);
        when(ticketRepository.findByUUIDForUpdate("ticket")).thenReturn(Optional.of(ticket));
        when(ticketRepository.findByUUID("ticket")).thenReturn(ticket);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(userRepository.findIdByUserName(USERNAME)).thenReturn(Optional.empty());
        var reservation = mock(TicketReservation.class);
        when(reservation.getPaymentMethod()).thenReturn(PaymentProxy.OFFLINE);
        when(reservationRepository.findReservationById("reservation")).thenReturn(reservation);
        var manager = new CheckInManager(ticketRepository, eventRepository, reservationRepository, null, null,
            scanAuditRepository, auditingRepository, configurationManager, null, userRepository, null,
            extensionManager, null, null, clockProvider, null);

        assertTrue(revert ? manager.revertCheckIn(EVENT_ID, "ticket", USERNAME)
                          : manager.manualCheckIn(EVENT_ID, "ticket", USERNAME));
        var expectedStatus = revert ? OK_READY_TO_BE_CHECKED_IN : SUCCESS;
        var operation = revert ? ScanAudit.Operation.REVERT : ScanAudit.Operation.SCAN;
        var auditType = revert ? Audit.EventType.REVERT_CHECK_IN : Audit.EventType.MANUAL_CHECK_IN;
        verify(scanAuditRepository).insert("ticket", EVENT_ID, ZonedDateTime.now(fixedClock), USERNAME, expectedStatus, operation);
        verify(auditingRepository).insert("reservation", null, EVENT_ID, auditType,
            Date.from(fixedClock.instant()), Audit.EntityType.TICKET, "1");
    }

}