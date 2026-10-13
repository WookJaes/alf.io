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
package alfio.controller.api.admin;

import alfio.manager.AccessService;
import alfio.manager.support.AccessDeniedException;
import alfio.manager.EventManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.system.ConfigurationManager;
import alfio.model.*;
import alfio.model.transaction.PaymentProxy;
import alfio.test.util.XlsxTestUtil;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.Principal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventApiControllerDocumentsTest {

    private final EventManager eventManager = mock(EventManager.class);
    private final TicketReservationManager reservationManager = mock(TicketReservationManager.class);
    private final ConfigurationManager configurationManager = mock(ConfigurationManager.class);
    private final AccessService accessService = mock(AccessService.class);
    private final Event event = mock(Event.class);
    private final TicketReservation reservation = mock(TicketReservation.class);
    private final TicketReservationWithTransaction entry = mock(TicketReservationWithTransaction.class);
    private final Principal principal = () -> "owner";
    private EventApiController controller;

    @BeforeEach
    void setUp() {
        controller = new EventApiController(eventManager, null, null, reservationManager, null, null, null,
            null, null, null, null, null, null, configurationManager, null, null, accessService, null);
        when(eventManager.getOptionalByName("example", "owner")).thenReturn(Optional.of(event));
        when(event.getId()).thenReturn(42);
        when(event.getShortName()).thenReturn("example");
        when(event.getZoneId()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(entry.getTicketReservation()).thenReturn(reservation);
        when(reservation.getId()).thenReturn("reservation");
        when(reservation.getLineSplittedBillingAddress()).thenReturn(List.of("Synthetic Company"));
        when(reservation.getVatNr()).thenReturn("TEST-TAX-ID");
        when(reservation.getCurrencyCode()).thenReturn("CHF");
        when(reservation.getPaymentMethod()).thenReturn(PaymentProxy.OFFLINE);
        when(reservation.getStatus()).thenReturn(TicketReservation.TicketReservationStatus.COMPLETE);
        when(reservation.getConfirmationTimestamp()).thenReturn(ZonedDateTime.parse("2026-10-12T15:05:00Z"));
    }

    @ParameterizedTest
    @EnumSource(TicketReservation.TicketReservationStatus.class)
    void exportsCurrentReservationStatusAndEmptyConfirmation(TicketReservation.TicketReservationStatus status) throws Exception {
        when(reservation.getStatus()).thenReturn(status);
        when(reservation.getConfirmationTimestamp()).thenReturn(null);
        var rows = export(false);
        assertEquals(status.name(), rows.get(1).get(11));
        assertEquals("", rows.get(1).get(12));
    }

    @ParameterizedTest
    @CsvSource({
        "Asia/Seoul, 2026-10-12T14:59:00Z, 2026-10-12 23:59",
        "Asia/Seoul, 2026-10-12T15:00:00Z, 2026-10-13 00:00",
        "America/Los_Angeles, 2026-01-01T07:59:00Z, 2025-12-31 23:59",
        "America/Los_Angeles, 2026-01-01T08:00:00Z, 2026-01-01 00:00",
        "Europe/Zurich, 2026-03-29T00:59:00Z, 2026-03-29 01:59",
        "Europe/Zurich, 2026-03-29T01:00:00Z, 2026-03-29 03:00"
    })
    void convertsConfirmationToEventTimeZone(String zone, String timestamp, String expected) throws Exception {
        when(event.getZoneId()).thenReturn(ZoneId.of(zone));
        when(reservation.getConfirmationTimestamp()).thenReturn(ZonedDateTime.parse(timestamp));
        assertEquals(expected, export(false).get(1).get(12));
    }

    @Test
    void preservesExistingColumnsAndValues() throws Exception {
        var rows = export(false);
        assertEquals(List.of("Reservation ID", "Type", "Number", "To", "Tax ID", "Total Before Tax", "Tax",
            "Total", "Currency", "Payment Method", "Generated on", "STATUS", "CONFIRMATION_DATE"), rows.getFirst());
        assertEquals(List.of("reservation", "INVOICE", "INV-1", "Synthetic Company", "TEST-TAX-ID", "10.00",
            "1.00", "11.00", "CHF", "OFFLINE", "2026-10-11 09:00", "COMPLETE", "2026-10-13 00:05"), rows.get(1));
        InOrder order = inOrder(accessService, eventManager, reservationManager);
        order.verify(accessService).checkEventOwnership(principal, "example");
        order.verify(eventManager).getOptionalByName("example", "owner");
        order.verify(reservationManager).streamAllDocumentsFor(42);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void appendsColumnsAfterItalianInvoicingFields(boolean populated) throws Exception {
        var details = mock(BillingDetails.class);
        when(entry.getBillingDetails()).thenReturn(details);
        when(details.getInvoicingAdditionalInfo()).thenReturn(new TicketReservationInvoicingAdditionalInfo(populated
            ? new TicketReservationInvoicingAdditionalInfo.ItalianEInvoicing("FISCAL-TEST",
                TicketReservationInvoicingAdditionalInfo.ItalianEInvoicing.ReferenceType.ADDRESSEE_CODE,
                "CODE-TEST", null, true)
            : null));
        var rows = export(true);
        assertEquals(List.of("Reservation ID", "Type", "Number", "To", "Tax ID", "Fiscal Code", "Reference Type",
            "Reference", "Split Payment", "Total Before Tax", "Tax", "Total", "Currency", "Payment Method",
            "Generated on", "STATUS", "CONFIRMATION_DATE"), rows.getFirst());
        assertEquals(populated ? List.of("FISCAL-TEST", "ADDRESSEE_CODE", "CODE-TEST", "true") : List.of("", "", "", ""),
            rows.get(1).subList(5, 9));
        assertEquals("COMPLETE", rows.get(1).get(15));
        assertEquals("2026-10-13 00:05", rows.get(1).get(16));
    }

    @Test
    void returnsForbiddenWithoutReadingOrAttachingDocuments() throws Exception {
        doThrow(new AccessDeniedException()).when(accessService).checkEventOwnership(principal, "example");
        var response = new MockHttpServletResponse();
        controller.getAllDocumentsXls("example", response, principal);
        assertEquals(403, response.getStatus());
        assertEquals("Access to resource has been denied", response.getContentAsString());
        assertNull(response.getHeader("Content-Disposition"));
        assertEquals("text/plain;charset=UTF-8", response.getContentType());
        verifyNoInteractions(eventManager, reservationManager, configurationManager);
    }

    @Test
    void rejectsUnauthorizedEventBeforeReadingDocuments() throws Exception {
        doThrow(new IllegalArgumentException("access denied")).when(accessService).checkEventOwnership(principal, "example");
        var response = new MockHttpServletResponse();
        assertThrows(IllegalArgumentException.class, () -> controller.getAllDocumentsXls("example", response, principal));
        verifyNoInteractions(eventManager, reservationManager);
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void exportsHeaderForEmptyDocumentSet() throws Exception {
        when(reservationManager.streamAllDocumentsFor(42)).thenReturn(Stream.empty());
        var response = new MockHttpServletResponse();
        controller.getAllDocumentsXls("example", response, principal);
        var rows = XlsxTestUtil.rows(response.getContentAsByteArray());
        assertEquals(1, rows.size());
        assertEquals(List.of("STATUS", "CONFIRMATION_DATE"), rows.getFirst().subList(11, 13));
    }

    private List<List<String>> export(boolean italianInvoicing) throws Exception {
        when(configurationManager.isItalianEInvoicingEnabled(event)).thenReturn(italianInvoicing);
        var document = new BillingDocument(1, 42, "reservation", "INV-1", BillingDocument.Type.INVOICE,
            "{\"orderSummary\":{\"totalNetPrice\":\"10.00\",\"totalVAT\":\"1.00\",\"totalPrice\":\"11.00\"}}",
            ZonedDateTime.parse("2026-10-11T00:00:00Z"), BillingDocument.Status.VALID, null);
        when(reservationManager.streamAllDocumentsFor(42)).thenReturn(Stream.of(Pair.of(entry, List.of(document))));
        var response = new MockHttpServletResponse();
        controller.getAllDocumentsXls("example", response, principal);
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.getContentType());
        assertEquals("attachment; filename=example-billing-documents.xlsx", response.getHeader("Content-Disposition"));
        return XlsxTestUtil.rows(response.getContentAsByteArray());
    }
}
