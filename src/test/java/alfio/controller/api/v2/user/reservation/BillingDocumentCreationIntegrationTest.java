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
package alfio.controller.api.v2.user.reservation;

import alfio.TestConfiguration;
import alfio.config.DataSourceConfiguration;
import alfio.config.Initializer;
import alfio.controller.api.ControllerConfiguration;
import alfio.controller.api.admin.AdminReservationApiController;
import alfio.controller.api.v2.user.EventApiV2Controller;
import alfio.controller.api.v2.user.ReservationApiV2Controller;
import alfio.controller.form.ContactAndTicketsForm;
import alfio.controller.form.PaymentForm;
import alfio.controller.form.ReservationForm;
import alfio.controller.form.UpdateTicketOwnerForm;
import alfio.manager.AdminReservationManager;
import alfio.manager.EventManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.user.UserManager;
import alfio.model.*;
import alfio.model.metadata.AlfioMetadata;
import alfio.model.modification.DateTimeModification;
import alfio.model.modification.TicketCategoryModification;
import alfio.model.modification.TicketReservationModification;
import alfio.model.system.ConfigurationKeys;
import alfio.model.transaction.PaymentProxy;
import alfio.model.transaction.StaticPaymentMethods;
import alfio.repository.BillingDocumentRepository;
import alfio.repository.EventRepository;
import alfio.repository.TicketCategoryRepository;
import alfio.repository.TicketRepository;
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.test.util.IntegrationTestUtil;
import alfio.util.BaseIntegrationTest;
import alfio.util.ClockProvider;
import alfio.test.util.XlsxTestUtil;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.context.request.ServletWebRequest;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static alfio.test.util.IntegrationTestUtil.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@AlfioIntegrationTest
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class, ControllerConfiguration.class, BillingDocumentCreationIntegrationTest.DownloadConfiguration.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class BillingDocumentCreationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ConfigurationRepository configurationRepository;
    @Autowired
    private ClockProvider clockProvider;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserManager userManager;
    @Autowired
    private EventManager eventManager;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private TicketCategoryRepository ticketCategoryRepository;
    @Autowired
    private EventApiV2Controller eventApiV2Controller;
    @Autowired
    private ReservationApiV2Controller reservationApiV2Controller;
    @Autowired
    private BillingDocumentRepository billingDocumentRepository;
    @Autowired
    private TicketReservationManager ticketReservationManager;
    @Autowired
    private AdminReservationManager adminReservationManager;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private AdminReservationApiController adminReservationApiController;


    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;
    @LocalServerPort
    private int port;

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class DownloadConfiguration {
        @Bean
        ServletRegistrationBean<DispatcherServlet> downloadDispatcher(WebApplicationContext context) {
            return new ServletRegistrationBean<>(new DispatcherServlet(context), "/");
        }

        @Bean
        FilterRegistrationBean<Filter> downloadPrincipalFilter() {
            // Supply a synthetic identity only for this test's real HTTP download.
            // Production login is outside this fixture; AccessService is still real.
            Filter filter = (request, response, chain) -> {
                var httpRequest = (HttpServletRequest) request;
                var username = httpRequest.getHeader("X-Test-Download-User");
                if (username == null || !httpRequest.getRequestURI().endsWith("/all-documents-xls")) {
                    chain.doFilter(request, response);
                    return;
                }
                chain.doFilter(new HttpServletRequestWrapper(httpRequest) {
                    @Override
                    public Principal getUserPrincipal() {
                        return username == null ? null : () -> username;
                    }
                }, response);
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns("/admin/api/events/*");
            registration.setMatchAfter(true);
            return registration;
        }
    }

    private Event event;
    private String username;

    @BeforeEach
    void setUp() {
        IntegrationTestUtil.ensureMinimalConfiguration(configurationRepository);
        List<TicketCategoryModification> categories = List.of(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.IN_PERSON, AVAILABLE_SEATS,
                new DateTimeModification(LocalDate.now(clockProvider.getClock()).minusDays(1), LocalTime.now(clockProvider.getClock())),
                new DateTimeModification(LocalDate.now(clockProvider.getClock()).plusDays(1), LocalTime.now(clockProvider.getClock())),
                DESCRIPTION, BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty())
        );
        Pair<Event, String> eventAndUser = initEvent(categories, organizationRepository, userManager, eventManager, eventRepository, null, Event.EventFormat.HYBRID);
        event = eventAndUser.getLeft();
        username = eventAndUser.getRight();
    }

    @Test
    void requestInvoice() {
        var reservationId = createReservation(form -> {
            form.setInvoiceRequested(true);
            form.setVatCountryCode("CH");
            form.setBillingAddressLine1("LINE 1");
            form.setBillingAddressCity("CITY");
            form.setBillingAddressZip("ZIP");
        });
        expectInvoiceToBeGenerated(reservationId);
    }

    @Test
    void invoiceAlwaysGenerated() {
        configurationRepository.insert(ConfigurationKeys.GENERATE_ONLY_INVOICE.getValue(), "true", "");
        var reservationId = createReservation(form -> {
            form.setVatCountryCode("CH");
            form.setBillingAddressLine1("LINE 1");
            form.setBillingAddressCity("CITY");
            form.setBillingAddressZip("ZIP");
        });
        expectInvoiceToBeGenerated(reservationId);
    }

    @Test
    void invoiceAlwaysGeneratedWhenItalyEInvoicingIsActive() {
        configurationRepository.insert(ConfigurationKeys.ENABLE_ITALY_E_INVOICING.getValue(), "true", "");
        var reservationId = createReservation(form -> {
            form.setVatCountryCode("CH");
            form.setBillingAddressLine1("LINE 1");
            form.setBillingAddressCity("CITY");
            form.setBillingAddressZip("ZIP");
            form.setItalyEInvoicingFiscalCode("ABCD");
        });
        expectInvoiceToBeGenerated(reservationId);
    }

    @Test
    void invoiceNotRequested() {
        var reservationId = createReservation(form -> form.setInvoiceRequested(false));
        assertNotNull(reservationId);
        var billingDocuments = billingDocumentRepository.findAllByReservationId(reservationId);
        assertEquals(0, billingDocuments.size());
        confirmPaymentForReservation(reservationId);

        billingDocuments = billingDocumentRepository.findAllByReservationId(reservationId);
        assertEquals(1, billingDocuments.size());
        assertEquals(BillingDocument.Type.RECEIPT, billingDocuments.getFirst().getType());
    }

    @Test
    void cancelReservationGeneratesCreditNote() throws Exception {
        var reservationId = createReservation(form -> {
            form.setInvoiceRequested(true);
            form.setVatCountryCode("CH");
            form.setBillingAddressLine1("LINE 1");
            form.setBillingAddressCity("CITY");
            form.setBillingAddressZip("ZIP");
        });
        assertNotNull(reservationId);
        confirmPaymentForReservation(reservationId);
        adminReservationManager.removeReservation(PurchaseContext.PurchaseContextType.event, event.getShortName(), reservationId, false, false, true, username);

        var billingDocuments = billingDocumentRepository.findAllByReservationId(reservationId);
        assertEquals(3, billingDocuments.size());
        assertTrue(billingDocuments.stream().allMatch(bd -> bd.getStatus() == BillingDocument.Status.VALID));

        assertEquals(BillingDocument.Type.CREDIT_NOTE, billingDocuments.getFirst().getType());
        var rows = download("credit-note");
        assertEquals(4, rows.size());
        assertTrue(rows.subList(1, rows.size()).stream().allMatch(row -> row.get(11).equals("CREDIT_NOTE_ISSUED")));
    }

    @Test
    void creditReservationGeneratesCreditNote() {
        var reservationId = createReservation(form -> {
            form.setInvoiceRequested(true);
            form.setVatCountryCode("CH");
            form.setBillingAddressLine1("LINE 1");
            form.setBillingAddressCity("CITY");
            form.setBillingAddressZip("ZIP");
        });
        assertNotNull(reservationId);
        adminReservationManager.creditReservation(PurchaseContext.PurchaseContextType.event, event.getShortName(), reservationId, false, false, username);

        var billingDocuments = billingDocumentRepository.findAllByReservationId(reservationId);
        assertEquals(2, billingDocuments.size());
        assertTrue(billingDocuments.stream().allMatch(bd -> bd.getStatus() == BillingDocument.Status.VALID));

        assertEquals(BillingDocument.Type.CREDIT_NOTE, billingDocuments.getFirst().getType());
    }

    @Test
    void deleteTicketGeneratesCreditNote() {
        var reservationId = createReservation(form -> {
            form.setInvoiceRequested(true);
            form.setVatCountryCode("CH");
            form.setBillingAddressLine1("LINE 1");
            form.setBillingAddressCity("CITY");
            form.setBillingAddressZip("ZIP");
        });
        assertNotNull(reservationId);
        var tickets = ticketRepository.findTicketsInReservation(reservationId);
        var ticketIds = tickets.stream().map(Ticket::getId).collect(Collectors.toList());
        var principal = Mockito.mock(Principal.class);
        when(principal.getName()).thenReturn(owner(username));
        var modification = new AdminReservationApiController.RemoveTicketsModification(
            ticketIds,
            Map.of(),
            false,
            true);
        var result = adminReservationApiController.removeTickets(event.getShortName(), reservationId, modification,principal);
        assertTrue(result.isSuccess());
        assertTrue(result.getData().isCreditNoteGenerated());
        var billingDocuments = billingDocumentRepository.findAllByReservationId(reservationId);
        assertEquals(2, billingDocuments.size());
        assertEquals(2, ticketReservationManager.countBillingDocuments(event.getId()));
        assertTrue(billingDocuments.stream().allMatch(bd -> bd.getStatus() == BillingDocument.Status.VALID));

        //
        assertEquals(2, billingDocumentRepository.findByIdsAndEvent(billingDocuments.stream().map(BillingDocument::getId).collect(Collectors.toList()), event.getId()).size());

        assertEquals(BillingDocument.Type.CREDIT_NOTE, billingDocuments.getFirst().getType());
    }

    @Test
    void downloadsDocumentsWithCurrentStatusAndEventConfirmationDate() throws Exception {
        var reservationId = createReservation(form -> {
            form.setInvoiceRequested(true);
            form.setVatCountryCode("CH");
            form.setBillingAddressLine1("LINE 1");
            form.setBillingAddressCity("CITY");
            form.setBillingAddressZip("ZIP");
        });
        var pending = download("pending");
        assertEquals(2, pending.size());
        assertEquals("OFFLINE_PAYMENT", pending.get(1).get(11));
        assertEquals("", pending.get(1).get(12));

        confirmPaymentForReservation(reservationId);
        var timestamp = ZonedDateTime.parse("2026-10-12T23:30:00Z");
        assertEquals(1, jdbcTemplate.update("update tickets_reservation set confirmation_ts = :timestamp where id = :id",
            Map.of("timestamp", timestamp.toOffsetDateTime(), "id", reservationId)));
        var expected = timestamp.withZoneSameInstant(event.getZoneId()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        var completed = download("completed");
        assertEquals(3, completed.size());
        for (var row : completed.subList(1, completed.size())) {
            assertEquals(reservationId, row.getFirst());
            assertEquals("COMPLETE", row.get(11));
            assertEquals(expected, row.get(12));
            assertNotEquals(row.get(10), row.get(12));
        }

        adminReservationManager.removeReservation(PurchaseContext.PurchaseContextType.event,
            event.getShortName(), reservationId, false, false, false, username);
        var cancelled = download("cancelled");
        var status = jdbcTemplate.queryForObject("select status from tickets_reservation where id = :id", Map.of("id", reservationId), String.class);
        assertEquals("CANCELLED", status);
        assertEquals(ticketReservationManager.countBillingDocuments(event.getId()) + 1, cancelled.size());
        assertEquals(3, cancelled.size());
        assertFalse(cancelled.subList(1, cancelled.size()).stream().anyMatch(row -> row.get(1).equals("CREDIT_NOTE")));
        for (var row : cancelled.subList(1, cancelled.size())) {
            assertEquals(status, row.get(11));
            assertEquals(expected, row.get(12));
        }
        var denied = requestDownload("not-an-event-owner");
        assertTrue(denied.statusCode() >= 400);
        assertEquals("Access to resource has been denied", new String(denied.body(), java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(denied.headers().firstValue("Content-Disposition").isPresent());
    }

    private HttpResponse<byte[]> requestDownload(String identity) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/api/events/"
            + event.getShortName() + "/all-documents-xls"))
            .header("X-Test-Download-User", identity).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private List<List<String>> download(String stage) throws Exception {
        var response = requestDownload(owner(username));
        assertEquals(200, response.statusCode());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("attachment; filename=" + event.getShortName() + "-billing-documents.xlsx",
            response.headers().firstValue("Content-Disposition").orElseThrow());
        var rows = XlsxTestUtil.rows(response.body());
        assertEquals(List.of("STATUS", "CONFIRMATION_DATE"), rows.getFirst().subList(11, 13));
        var artifactDirectory = System.getProperty("billing.download.artifacts");
        if (artifactDirectory != null) {
            var directory = Path.of(artifactDirectory);
            Files.createDirectories(directory);
            Files.write(directory.resolve(stage + ".xlsx"), response.body());
        }
        return rows;
    }

    private String createReservation(Consumer<ContactAndTicketsForm> formCustomizer) {
        var categories = ticketCategoryRepository.findAllTicketCategories(event.getId());
        assertFalse(categories.isEmpty());
        int categoryId = categories.getFirst().getId();
        var form = new ReservationForm();
        var ticketReservation = new TicketReservationModification();
        ticketReservation.setQuantity(1);
        ticketReservation.setTicketCategoryId(categoryId);
        form.setReservation(List.of(ticketReservation));
        var res = eventApiV2Controller.reserveTickets(event.getShortName(), "en", form, new BeanPropertyBindingResult(form, "reservation"), new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse()), null);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        var resBody = res.getBody();
        assertNotNull(resBody);
        assertTrue(resBody.isSuccess());
        assertEquals(0, resBody.getErrorCount());
        var reservationId = resBody.getValue();

        // reservation is now in PENDING status

        var resInfoRes = reservationApiV2Controller.getReservationInfo(reservationId, null);
        assertEquals(HttpStatus.OK, resInfoRes.getStatusCode());
        assertNotNull(resInfoRes.getBody());
        var ticketsByCat = resInfoRes.getBody().getTicketsByCategory();
        assertEquals(1, ticketsByCat.size());
        assertEquals(1, ticketsByCat.getFirst().getTickets().size());
        var ticket = ticketsByCat.getFirst().getTickets().getFirst();

        var contactForm = new ContactAndTicketsForm();

        contactForm.setAddCompanyBillingDetails(true);
        contactForm.setSkipVatNr(false);
        contactForm.setInvoiceRequested(true);
        contactForm.setEmail("test@test.com");
        contactForm.setFirstName("full");
        contactForm.setLastName("name");
        var ticketForm1 = new UpdateTicketOwnerForm();
        ticketForm1.setFirstName("ticketfull");
        ticketForm1.setLastName("ticketname");
        ticketForm1.setEmail("tickettest@test.com");

        formCustomizer.accept(contactForm);

        contactForm.setTickets(Map.of(ticket.getUuid(), ticketForm1));

        var success = reservationApiV2Controller.validateToOverview(reservationId, "en", false, contactForm, new BeanPropertyBindingResult(contactForm, "paymentForm"), null);
        assertEquals(HttpStatus.OK, success.getStatusCode());

        var paymentForm = new PaymentForm();
        paymentForm.setPrivacyPolicyAccepted(true);
        paymentForm.setTermAndConditionsAccepted(true);
        paymentForm.setPaymentProxy(PaymentProxy.OFFLINE);
        paymentForm.setSelectedPaymentMethod(StaticPaymentMethods.BANK_TRANSFER);

        var handleRes = reservationApiV2Controller.confirmOverview(reservationId, "en", paymentForm, new BeanPropertyBindingResult(paymentForm, "paymentForm"),
            new MockHttpServletRequest(), null);
        assertEquals(HttpStatus.OK, handleRes.getStatusCode());

        return reservationId;
    }

    private void confirmPaymentForReservation(String reservationId) {
        ticketReservationManager.confirmOfflinePayment(event, reservationId, null, username);
    }

    private void expectInvoiceToBeGenerated(String reservationId) {
        assertNotNull(reservationId);
        var billingDocuments = billingDocumentRepository.findAllByReservationId(reservationId);
        assertEquals(1, billingDocuments.size());
        assertEquals(BillingDocument.Type.INVOICE, billingDocuments.getFirst().getType());

        confirmPaymentForReservation(reservationId);

        billingDocuments = billingDocumentRepository.findAllByReservationId(reservationId);
        assertEquals(2, billingDocuments.size());
        var documentsByStatus = billingDocuments.stream().collect(Collectors.partitioningBy(bd -> bd.getStatus() == BillingDocument.Status.VALID));
        var activeList = documentsByStatus.get(true);
        assertNotNull(activeList);
        assertEquals(2, activeList.size());
        var notActiveList = documentsByStatus.get(false);
        assertNotNull(notActiveList);
        assertEquals(0, notActiveList.size());

        var first = activeList.getFirst();
        var second = activeList.get(1);

        assertEquals(second.getModel().get("confirmationDate"), first.getModel().get("confirmationDate"));
    }
}
