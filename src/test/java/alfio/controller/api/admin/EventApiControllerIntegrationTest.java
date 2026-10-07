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

import alfio.TestConfiguration;
import alfio.config.DataSourceConfiguration;
import alfio.config.Initializer;
import alfio.controller.api.ControllerConfiguration;
import alfio.manager.AdminReservationRequestManager;
import alfio.manager.EventManager;
import alfio.manager.payment.custom.offline.CustomOfflineConfigurationManager.CustomOfflinePaymentMethodAlreadyExistsException;
import alfio.manager.payment.custom.offline.CustomOfflineConfigurationManager.CustomOfflinePaymentMethodDoesNotExistException;
import alfio.manager.payment.custom.offline.CustomOfflineConfigurationManager;
import alfio.manager.support.AccessDeniedException;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.model.PurchaseContextFieldConfiguration;
import alfio.model.TicketCategory;
import alfio.model.metadata.AlfioMetadata;
import alfio.model.modification.AdminReservationModification;
import alfio.model.modification.DateTimeModification;
import alfio.model.modification.TicketCategoryModification;
import alfio.model.transaction.UserDefinedOfflinePaymentMethod;
import alfio.repository.EventDeleterRepository;
import alfio.repository.EventRepository;
import alfio.repository.PurchaseContextFieldRepository;
import alfio.repository.TicketCategoryRepository;
import alfio.repository.TicketRepository;
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.toolkit.PromoCodeDiscountIntegrationTestingToolkit;
import alfio.test.util.AlfioIntegrationTest;
import alfio.test.util.IntegrationTestUtil;
import alfio.util.ClockProvider;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.w3c.dom.Element;

import tools.jackson.dataformat.csv.CsvMapper;
import tools.jackson.dataformat.csv.CsvReadFeature;
import tools.jackson.dataformat.csv.CsvSchema;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static alfio.controller.api.admin.EventApiController.FIXED_FIELDS;
import static alfio.test.toolkit.PromoCodeDiscountIntegrationTestingToolkit.TEST_PROMO_CODE;
import static alfio.test.util.IntegrationTestUtil.AVAILABLE_SEATS;
import static alfio.test.util.IntegrationTestUtil.DESCRIPTION;
import static alfio.test.util.IntegrationTestUtil.initEvent;
import static alfio.test.util.IntegrationTestUtil.owner;
import static alfio.test.util.TestUtil.clockProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@AlfioIntegrationTest
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class, ControllerConfiguration.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class EventApiControllerIntegrationTest {

    @Autowired
    private ConfigurationRepository configurationRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserManager userManager;
    @Autowired
    private EventManager eventManager;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private EventDeleterRepository eventDeleterRepository;
    @Autowired
    private EventApiController eventApiController;
    @Autowired
    private AttendeeBulkImportApiController attendeeBulkImportApiController;
    @Autowired
    private AdminReservationRequestManager adminReservationRequestManager;
    @Autowired
    private TicketCategoryRepository ticketCategoryRepository;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private PromoCodeDiscountIntegrationTestingToolkit promoCodeDiscountIntegrationTestingToolkit;
    @Autowired
    private CustomOfflineConfigurationManager customOfflineConfigurationManager;

    @Autowired
    private PurchaseContextFieldRepository purchaseContextFieldRepository;

    private Event event;
    private static final String TEST_ATTENDEE_EXTERNAL_REFERENCE = "123";
    private static final String TEST_ATTENDEE_USER_LANGUAGE = "en";
    private static final String TEST_ATTENDEE_FIRST_NAME = "Attendee";
    private static final String TEST_ATTENDEE_LAST_NAME = "Test";
    private static final String TEST_RESERVATION_EMAIL = "integration-test@test.ch";
    private static final String TEST_ATTENDEE_EMAIL = "attendee@test.com";

    @Test
    void getAllEventsForExternalInPerson() {
        var eventAndUser = createEvent(Event.EventFormat.IN_PERSON);
        event = eventAndUser.getKey();
        var principal = Mockito.mock(Authentication.class);
        when(principal.getName()).thenReturn(eventAndUser.getValue());
        var events = eventApiController.getAllEventsForExternal(principal, new MockHttpServletRequest(), false);

        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals(event.getShortName(), events.getFirst().getKey());
    }

    @Test
    void getAllEventsForExternalHybrid() {
        var eventAndUser = createEvent(Event.EventFormat.HYBRID);
        event = eventAndUser.getKey();
        var principal = Mockito.mock(Authentication.class);
        when(principal.getName()).thenReturn(eventAndUser.getValue());
        var events = eventApiController.getAllEventsForExternal(principal, new MockHttpServletRequest(), false);

        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals(event.getShortName(), events.getFirst().getKey());
    }

    @Test
    void getAllEventsForExternalOnline() {
        var eventAndUser = createEvent(Event.EventFormat.ONLINE);
        event = eventAndUser.getKey();
        var principal = Mockito.mock(Authentication.class);
        when(principal.getName()).thenReturn(eventAndUser.getValue());
        var events = eventApiController.getAllEventsForExternal(principal, new MockHttpServletRequest(), false);

        assertNotNull(events);
        assertEquals(0, events.size());

        events = eventApiController.getAllEventsForExternal(principal, new MockHttpServletRequest(), true);
        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals(event.getShortName(), events.getFirst().getKey());
    }

    @Test
    void testGivenListOfAttendeesWithFieldsUploadThenSameFieldsAvailableOnCsvDownload() throws IOException {
        // GIVEN - creation of event and registration of attendees
        var eventAndUser = createEvent(Event.EventFormat.HYBRID);
        event = eventAndUser.getKey();
        var principal = Mockito.mock(Authentication.class);
        when(principal.getName()).thenReturn(owner(eventAndUser.getValue()));
        var modification = getTestAdminReservationModification();
        var result = this.attendeeBulkImportApiController.createReservations(eventAndUser.getKey().getShortName(), modification, false, principal);
        var organizationId = organizationRepository.findAllForUser(eventAndUser.getRight()).getFirst().getId();

        // GIVEN - invocation of async processing job
        var requestStatus = this.attendeeBulkImportApiController.getRequestsStatus(eventAndUser.getKey().getShortName(), result.getData(), principal);
        assertEquals(1, requestStatus.getData().getCountPending());

        // WHEN - processing of pending reservations completes
        this.adminReservationRequestManager.processPendingReservations();
        promoCodeDiscountIntegrationTestingToolkit.createPromoCodeDiscount(event.getId(), organizationId, modification.getCustomerData()
                                                                                                                      .getEmailAddress());
        // THEN - assert correctness of data persisted
        var tickets = this.ticketRepository.findAllConfirmedForCSV(event.getId());
        assertEquals(1, tickets.size());
        var foundTicket = tickets.getFirst().getTicket();
        assertEquals(TEST_ATTENDEE_EXTERNAL_REFERENCE, foundTicket.getExtReference());
        assertEquals(TEST_ATTENDEE_FIRST_NAME, foundTicket.getFirstName());
        assertEquals(TEST_ATTENDEE_LAST_NAME, foundTicket.getLastName());
        assertEquals(TEST_ATTENDEE_EMAIL, foundTicket.getEmail());
        assertEquals(TEST_ATTENDEE_USER_LANGUAGE, foundTicket.getUserLanguage());

        // THEN - assert correct order of CSV fields upon download
        MockHttpServletRequest mockRequest = new MockHttpServletRequest();
        mockRequest.addParameter("fields", FIXED_FIELDS.toArray(new String[0]));
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        this.eventApiController.downloadAllTicketsCSV(event.getShortName(), "csv", mockRequest, mockResponse, principal);
        String expectedTestAttendeeCsvLine = "\""+foundTicket.getUuid()+"\""+",default,"+"\""+event.getShortName()+"\""+",ACQUIRED,0,0,0,0,"+"\""+foundTicket.getTicketsReservationId()+"\""+",\""+TEST_ATTENDEE_FIRST_NAME+" "+TEST_ATTENDEE_LAST_NAME+"\","+TEST_ATTENDEE_FIRST_NAME+","+TEST_ATTENDEE_LAST_NAME+","+TEST_ATTENDEE_EMAIL+",false,"+TEST_ATTENDEE_USER_LANGUAGE;
        String returnedCsvContent = mockResponse.getContentAsString().trim().replace("\uFEFF", ""); // remove BOM
        assertTrue(returnedCsvContent.startsWith(getExpectedHeaderCsvLine() + "\n" + expectedTestAttendeeCsvLine));
        assertTrue(returnedCsvContent.endsWith("\"Billing Address\",,"+TEST_PROMO_CODE+",,," + TEST_ATTENDEE_EXTERNAL_REFERENCE + "," + TEST_RESERVATION_EMAIL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "excel"})
    void exportIncludesReservationEmailForEachTicketAndCustomFields(String format) throws Exception {
        var principal = createConfirmedReservationWithTwoAttendees();
        var fieldId = purchaseContextFieldRepository.insertConfiguration(event.getId(), event.getOrganizationId(), null,
            "Company", 0, "text", null, 100, 0, false, PurchaseContextFieldConfiguration.Context.ATTENDEE,
            -1, null, false).getKey();
        var tickets = ticketRepository.findAllConfirmedForCSV(event.getId());
        for (var ticket : tickets) {
            purchaseContextFieldRepository.insertValue(ticket.getTicket().getId(), null, event.getOrganizationId(), fieldId,
                "Example Company", PurchaseContextFieldConfiguration.Context.ATTENDEE);
        }
        var fields = List.of("ID", "Full Name", "E-Mail", "Reservation E-Mail", "custom:Company");
        var response = downloadAttendees(format, fields, principal);
        var rows = readExportRows(format, response);
        assertEquals(List.of("ID", "Full Name", "E-Mail", "Reservation E-Mail", "Company"), rows.getFirst());
        assertEquals(3, rows.size());
        assertEquals(1, tickets.stream().map(t -> t.getTicket().getTicketsReservationId()).distinct().count());
        for (int i = 1; i < rows.size(); i++) {
            assertEquals(List.of(tickets.get(i - 1).getTicket().getUuid(), TEST_ATTENDEE_FIRST_NAME + " " + TEST_ATTENDEE_LAST_NAME,
                TEST_ATTENDEE_EMAIL, TEST_RESERVATION_EMAIL, "Example Company"), rows.get(i));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "excel"})
    void exportKeepsLegacySelectionWithoutReservationEmail(String format) throws Exception {
        var principal = createConfirmedReservationWithTwoAttendees();
        var rows = readExportRows(format, downloadAttendees(format, List.of("E-Mail"), principal));
        assertEquals(List.of("E-Mail"), rows.getFirst());
        assertEquals(3, rows.size());
        rows.stream().skip(1).forEach(row -> assertEquals(List.of(TEST_ATTENDEE_EMAIL), row));
    }

    @Test
    void exportFieldsIncludeSeparateReservationEmail() {
        var eventAndUser = createEvent(Event.EventFormat.HYBRID);
        event = eventAndUser.getKey();
        var fields = eventApiController.getAllFields(event.getShortName(), () -> owner(eventAndUser.getValue()));
        assertTrue(fields.stream().anyMatch(field -> field.getKey().equals("Reservation E-Mail") && field.getValue().equals("Reservation E-Mail")));
        assertTrue(fields.stream().anyMatch(field -> field.getKey().equals("E-Mail")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "excel"})
    void exportRejectsUnauthorizedUserBeforeWritingData(String format) {
        var eventAndUser = createEvent(Event.EventFormat.HYBRID);
        event = eventAndUser.getKey();
        var request = new MockHttpServletRequest();
        request.addParameter("fields", "Reservation E-Mail");
        var response = new MockHttpServletResponse();
        assertThrows(AccessDeniedException.class, () -> eventApiController.downloadAllTicketsCSV(event.getShortName(), format,
            request, response, () -> "unknown-export-user"));
        assertEquals(0, response.getContentAsByteArray().length);
        assertFalse(response.containsHeader("Content-Disposition"));
    }

    private Authentication createConfirmedReservationWithTwoAttendees() {
        var eventAndUser = createEvent(Event.EventFormat.HYBRID);
        event = eventAndUser.getKey();
        var principal = Mockito.mock(Authentication.class);
        when(principal.getName()).thenReturn(owner(eventAndUser.getValue()));
        var modification = getTestAdminReservationModification();
        var category = modification.getTicketsInfo().getFirst().getCategory();
        var twoAttendees = new AdminReservationModification(modification.getExpiration(), modification.getCustomerData(),
            List.of(new AdminReservationModification.TicketsInfo(category, List.of(generateTestAttendee(), new AdminReservationModification.Attendee(null, TEST_ATTENDEE_FIRST_NAME, TEST_ATTENDEE_LAST_NAME,
                TEST_ATTENDEE_EMAIL, TEST_ATTENDEE_USER_LANGUAGE, false, "456", null, Collections.emptyMap(), null)), true, false)),
            "en", false, false, null, null, null, null);
        var result = attendeeBulkImportApiController.createReservations(event.getShortName(), twoAttendees, false, principal);
        assertTrue(result.isSuccess());
        adminReservationRequestManager.processPendingReservations();
        assertEquals(2, ticketRepository.findAllConfirmedForCSV(event.getId()).size());
        return principal;
    }

    private MockHttpServletResponse downloadAttendees(String format, List<String> fields, Authentication principal) throws IOException {
        var request = new MockHttpServletRequest();
        request.addParameter("fields", fields.toArray(String[]::new));
        var response = new MockHttpServletResponse();
        eventApiController.downloadAllTicketsCSV(event.getShortName(), format, request, response, principal);
        return response;
    }

    private List<List<String>> readExportRows(String format, MockHttpServletResponse response) throws Exception {
        if ("csv".equals(format)) {
            assertEquals("text/csv;charset=UTF-8", response.getContentType());
            var content = response.getContentAsString().replace("\uFEFF", "");
            return new CsvMapper().readerForListOf(String.class).with(CsvSchema.emptySchema()).with(CsvReadFeature.WRAP_AS_ARRAY).<List<String>>readValues(content).readAll();
        }
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.getContentType());
        try (var zip = new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    var factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes()));
                    var rows = new ArrayList<List<String>>();
                    var elements = document.getElementsByTagName("row");
                    for (int i = 0; i < elements.getLength(); i++) {
                        var cells = ((Element) elements.item(i)).getElementsByTagName("t");
                        var values = new ArrayList<String>();
                        for (int j = 0; j < cells.getLength(); j++) {
                            values.add(cells.item(j).getTextContent());
                        }
                        rows.add(values);
                    }
                    return rows;
                }
            }
        }
        throw new AssertionError("Excel worksheet missing");
    }

    @Test
    void testCanGetDeniedCustomPaymentMethods() throws CustomOfflinePaymentMethodAlreadyExistsException, PassedIdDoesNotExistException, CustomOfflinePaymentMethodDoesNotExistException {
        var eventAndUser = createEvent(Event.EventFormat.ONLINE);
        event = eventAndUser.getKey();
        var principal = Mockito.mock(Authentication.class);
        when(principal.getName()).thenReturn(owner(eventAndUser.getValue()));
        var organizationId = organizationRepository.findAllForUser(eventAndUser.getRight()).getFirst().getId();
        var ticketCategoryList = this.ticketCategoryRepository.findAllTicketCategories(event.getId());

        assertEquals(1, ticketCategoryList.size());

        var ticketCategory = ticketCategoryList.getFirst();

        var paymentMethods = List.of(
            new UserDefinedOfflinePaymentMethod(
                "15146df3-2436-4d2e-90b9-0d6cb273e291",
                Map.of(
                    "en", new UserDefinedOfflinePaymentMethod.Localization(
                        "Interac E-Transfer",
                        "Instant bank transfer from any Canadian account.",
                        "Send the payment to `payments@example.com`."
                    )
                )
            ),
            new UserDefinedOfflinePaymentMethod(
                "ec6c5268-4122-4b27-98ee-fa070df11c5b",
                Map.of(
                    "en", new UserDefinedOfflinePaymentMethod.Localization(
                        "Venmo",
                        "Instant money transfers via the Venmo app.",
                        "Send the payment to user `exampleco` on Venmo."
                    )
                )
            )
        );

        for(var pm : paymentMethods) {
            customOfflineConfigurationManager.createOrganizationCustomOfflinePaymentMethod(organizationId, pm);
        }
        customOfflineConfigurationManager.setDeniedPaymentMethodsByTicketCategory(
            event,
            ticketCategory,
            List.of(paymentMethods.getFirst())
        );

        var response = eventApiController.getDeniedCustomPaymentMethods(
            event.getId(),
            ticketCategory.getId(),
            principal
        );

        var deniedMethodIds = response.getBody();

        assertEquals(1, deniedMethodIds.size());
        assertTrue(deniedMethodIds.stream().allMatch(blItem ->
            paymentMethods.stream().anyMatch(pmItem -> blItem.equals(pmItem.getPaymentMethodId())))
        );
    }

    @Test
    void testCanSetDeniedCustomPaymentMethods() throws PassedIdDoesNotExistException, CustomOfflinePaymentMethodAlreadyExistsException, CustomOfflinePaymentMethodDoesNotExistException {
        var eventAndUser = createEvent(Event.EventFormat.ONLINE);
        event = eventAndUser.getKey();
        var principal = Mockito.mock(Authentication.class);
        when(principal.getName()).thenReturn(owner(eventAndUser.getValue()));
        var ticketCategoryList = this.ticketCategoryRepository.findAllTicketCategories(event.getId());

        assertEquals(1, ticketCategoryList.size());

        var ticketCategory = ticketCategoryList.getFirst();

        var paymentMethods = List.of(
            new UserDefinedOfflinePaymentMethod(
                "15146df3-2436-4d2e-90b9-0d6cb273e291",
                Map.of(
                    "en", new UserDefinedOfflinePaymentMethod.Localization(
                        "Interac E-Transfer",
                        "Instant bank transfer from any Canadian account.",
                        "Send the payment to `payments@example.com`."
                    )
                )
            ),
            new UserDefinedOfflinePaymentMethod(
                "ec6c5268-4122-4b27-98ee-fa070df11c5b",
                Map.of(
                    "en", new UserDefinedOfflinePaymentMethod.Localization(
                        "Venmo",
                        "Instant money transfers via the Venmo app.",
                        "Send the payment to user `exampleco` on Venmo."
                    )
                )
            )
        );

        for(var pm : paymentMethods) {
            customOfflineConfigurationManager.createOrganizationCustomOfflinePaymentMethod(event.getOrganizationId(), pm);
        }

        eventApiController.setDeniedCustomPaymentMethods(
            event.getId(),
            ticketCategory.getId(),
            List.of(paymentMethods.getFirst().getPaymentMethodId()),
            principal
        );

        var storedDeniedPaymentMethods = customOfflineConfigurationManager.getDeniedPaymentMethodsByTicketCategory(
            event,
            ticketCategory
        );
        assertEquals(1, storedDeniedPaymentMethods.size());

        assertTrue(storedDeniedPaymentMethods.stream().allMatch(
            blItem -> paymentMethods.stream().anyMatch(pmItem -> blItem.getPaymentMethodId().equals(pmItem.getPaymentMethodId())))
        );
    }

    private AdminReservationModification getTestAdminReservationModification() {
        DateTimeModification expiration = DateTimeModification.fromZonedDateTime(ZonedDateTime.now(ClockProvider.clock()).plusDays(1));
        AdminReservationModification.CustomerData customerData = new AdminReservationModification.CustomerData("Integration", "Test", TEST_RESERVATION_EMAIL, "Billing Address", "reference", "en", "1234", "CH", null);
        var ticketCategoryList = this.ticketCategoryRepository.findAllTicketCategories(event.getId());
        AdminReservationModification.Category category = new AdminReservationModification.Category(ticketCategoryList.getFirst().getId(), "name", new BigDecimal("100.00"), null);
        List<AdminReservationModification.TicketsInfo> ticketsInfoList = Collections.singletonList(new AdminReservationModification.TicketsInfo(category, Collections.singletonList(generateTestAttendee()), true, false));
        return new AdminReservationModification(expiration, customerData, ticketsInfoList, "en", false, false, null, null, null, null);
    }

    private Pair<Event,String> createEvent(Event.EventFormat format) {
        IntegrationTestUtil.ensureMinimalConfiguration(configurationRepository);
        List<TicketCategoryModification> categories = List.of(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, AVAILABLE_SEATS,
                new DateTimeModification(LocalDate.now(clockProvider().getClock()).minusDays(1), LocalTime.now(clockProvider().getClock())),
                new DateTimeModification(LocalDate.now(clockProvider().getClock()).plusDays(1), LocalTime.now(clockProvider().getClock())),
                DESCRIPTION, BigDecimal.ZERO, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty())
        );
        return initEvent(categories, organizationRepository, userManager, eventManager, eventRepository, List.of(), format);

    }

    private String getExpectedHeaderCsvLine() {
        String expectedHeaderCsvLine = String.join(",", FIXED_FIELDS);
        expectedHeaderCsvLine = expectedHeaderCsvLine.replaceAll("Full Name", "\"Full Name\"");
        expectedHeaderCsvLine = expectedHeaderCsvLine.replaceAll("First Name", "\"First Name\"");
        expectedHeaderCsvLine = expectedHeaderCsvLine.replaceAll("Last Name", "\"Last Name\"");
        expectedHeaderCsvLine = expectedHeaderCsvLine.replaceAll("Billing Address", "\"Billing Address\"");
        expectedHeaderCsvLine = expectedHeaderCsvLine.replaceAll("Country Code", "\"Country Code\"");
        expectedHeaderCsvLine = expectedHeaderCsvLine.replaceAll("Promo Code", "\"Promo Code\"");
        expectedHeaderCsvLine = expectedHeaderCsvLine.replaceAll("Payment ID", "\"Payment ID\"");
        expectedHeaderCsvLine = expectedHeaderCsvLine.replaceAll("Payment Method", "\"Payment Method\"");
        expectedHeaderCsvLine = expectedHeaderCsvLine.replaceAll("External Reference", "\"External Reference\"");
        expectedHeaderCsvLine = expectedHeaderCsvLine.replaceAll("Reservation E-Mail", "\"Reservation E-Mail\"");
        return expectedHeaderCsvLine;
    }

    private AdminReservationModification.Attendee generateTestAttendee() {
        return new AdminReservationModification.Attendee(null, TEST_ATTENDEE_FIRST_NAME, TEST_ATTENDEE_LAST_NAME, TEST_ATTENDEE_EMAIL, TEST_ATTENDEE_USER_LANGUAGE,false, TEST_ATTENDEE_EXTERNAL_REFERENCE, null, Collections.emptyMap(), null);
    }

    @AfterEach
    void tearDown() {
        eventDeleterRepository.deleteAllForEvent(event.getId());
    }
}