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
import alfio.manager.CheckInManager;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.model.PromoCodeDiscount;
import alfio.model.TicketCategory;
import alfio.model.metadata.AlfioMetadata;
import alfio.model.modification.DateTimeModification;
import alfio.model.modification.TicketCategoryModification;
import alfio.model.system.ConfigurationKeys;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.util.BaseIntegrationTest;
import alfio.util.ErrorsCode;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static alfio.test.util.IntegrationTestUtil.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AlfioIntegrationTest
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class, ControllerConfiguration.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class ReservationFlowIntegrationTest extends BaseReservationFlowTest {

    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserManager userManager;
    @Autowired
    private CheckInManager checkInManager;

    private ReservationFlowContext createContext() {
        List<TicketCategoryModification> categories = Arrays.asList(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, AVAILABLE_SEATS,
                new DateTimeModification(LocalDate.now(clockProvider.getClock()).minusDays(1), LocalTime.now(clockProvider.getClock())),
                new DateTimeModification(LocalDate.now(clockProvider.getClock()).plusDays(1), LocalTime.now(clockProvider.getClock())),
                DESCRIPTION, BigDecimal.TEN, false, "", false, "PUBLIC_CODE", null, null, null, null, 0, null, null, AlfioMetadata.empty()),
            new TicketCategoryModification(null, "hidden", TicketCategory.TicketAccessType.INHERIT, 2,
                new DateTimeModification(LocalDate.now(clockProvider.getClock()).minusDays(1), LocalTime.now(clockProvider.getClock())),
                new DateTimeModification(LocalDate.now(clockProvider.getClock()).plusDays(1), LocalTime.now(clockProvider.getClock())),
                DESCRIPTION, BigDecimal.ONE, true, "", true, URL_CODE_HIDDEN, null, null, null, null, 0, null, null, AlfioMetadata.empty())
        );
        Pair<Event, String> eventAndUser = initEvent(categories, organizationRepository, userManager, eventManager, eventRepository);
        return new ReservationFlowContext(eventAndUser.getLeft(), owner(eventAndUser.getRight()));
    }

    @Test
    void inPersonEvent() throws Exception {
        super.testBasicFlow(this::createContext);
    }

    private ReservationFlowContext directLinkContext() {
        ensureMinimalConfiguration(configurationRepository);
        var context = createContext();
        eventManager.toggleActiveFlag(context.event.getId(), context.userId, true);
        specialPriceTokenGenerator.generatePendingCodes();
        return context;
    }

    private MockMvc directLinkMvc() {
        return MockMvcBuilders.standaloneSetup(eventApiV2Controller).build();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"1", "3", "5"})
    void publicDirectLinkReservesRequestedQuantity(String quantity) throws Exception {
        var context = directLinkContext();
        var request = get("/api/v2/public/event/{event}/code/PUBLIC_CODE", context.event.getShortName());
        if (quantity != null) {
            request.param("qty", quantity);
        }
        var result = directLinkMvc().perform(request).andExpect(status().isTemporaryRedirect()).andReturn();
        var location = result.getResponse().getHeader("Location");
        assertNotNull(location);
        var reservationId = location.substring(location.indexOf("/reservation/") + "/reservation/".length(), location.length() - "/book".length());
        var tickets = ticketRepository.findTicketsInReservation(reservationId);
        assertEquals(quantity == null ? 1 : Integer.parseInt(quantity), tickets.size());
        assertEquals("PUBLIC_CODE", ticketCategoryRepository.getById(tickets.getFirst().getCategoryId()).getCode());
        assertEquals(1, jdbcTemplate.queryForObject("select count(*) from tickets_reservation where event_id_fk = :eventId", Map.of("eventId", context.event.getId()), Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "", "2147483648", "-2147483649", "999999999999999999999"})
    void publicDirectLinkRejectsInvalidQuantityWithoutReservation(String quantity) throws Exception {
        var context = directLinkContext();
        assertDirectLinkError(context, quantity, ErrorsCode.STEP_1_SELECT_AT_LEAST_ONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"event", "category"})
    void publicDirectLinkHonorsPurchaseLimit(String scope) throws Exception {
        var context = directLinkContext();
        var event = context.event;
        var key = ConfigurationKeys.MAX_AMOUNT_OF_TICKETS_BY_RESERVATION.name();
        if (scope.equals("event")) {
            configurationRepository.insertEventLevel(event.getOrganizationId(), event.getId(), key, "2", "");
        } else {
            var category = ticketCategoryRepository.findCodeInEvent(event.getId(), "PUBLIC_CODE").orElseThrow();
            configurationRepository.insertTicketCategoryLevel(event.getOrganizationId(), event.getId(), category.getId(), key, "2", "");
        }
        var result = directLinkMvc().perform(get("/api/v2/public/event/{event}/code/PUBLIC_CODE", event.getShortName()).param("qty", "3"))
            .andExpect(status().isTemporaryRedirect())
            .andReturn();
        var location = result.getResponse().getHeader("Location");
        assertNotNull(location);
        var redirect = UriComponentsBuilder.fromUriString(location).build();
        assertEquals("/event/" + event.getShortName(), redirect.getPath());
        assertEquals(ErrorsCode.STEP_1_OVER_MAXIMUM, redirect.getQueryParams().getFirst("errors"));
        assertEquals("2", redirect.getQueryParams().getFirst("maxTickets"));
        assertNoDirectLinkReservation(context);
    }

    @Test
    void publicDirectLinkRejectsInsufficientStockWithoutPartialReservation() throws Exception {
        var context = directLinkContext();
        configurationRepository.insertEventLevel(context.event.getOrganizationId(), context.event.getId(),
            ConfigurationKeys.MAX_AMOUNT_OF_TICKETS_BY_RESERVATION.name(), "100", "");
        assertDirectLinkError(context, "21", ErrorsCode.STEP_1_NOT_ENOUGH_TICKETS);
    }

    private void assertDirectLinkError(ReservationFlowContext context, String quantity, String error) throws Exception {
        directLinkMvc().perform(get("/api/v2/public/event/{event}/code/PUBLIC_CODE", context.event.getShortName()).param("qty", quantity))
            .andExpect(status().isTemporaryRedirect())
            .andExpect(redirectedUrl("/event/" + context.event.getShortName() + "?errors=" + error));
        assertNoDirectLinkReservation(context);
    }

    private void assertNoDirectLinkReservation(ReservationFlowContext context) {
        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from tickets_reservation where event_id_fk = :eventId", Map.of("eventId", context.event.getId()), Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from ticket where event_id = :eventId and tickets_reservation_id is not null", Map.of("eventId", context.event.getId()), Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "invalid"})
    void restrictedAndSpecialPriceLinksStillReserveOneTicket(String quantity) throws Exception {
        var context = directLinkContext();
        var category = ticketCategoryRepository.findCodeInEvent(context.event.getId(), URL_CODE_HIDDEN).orElseThrow();
        var specialCode = specialPriceRepository.findActiveNotAssignedByCategoryId(category.getId(), 1).getFirst().getCode();
        for (String code : List.of(URL_CODE_HIDDEN, specialCode)) {
            var result = directLinkMvc().perform(get("/api/v2/public/event/{event}/code/{code}", context.event.getShortName(), code).param("qty", quantity))
                .andExpect(status().isTemporaryRedirect()).andReturn();
            var location = result.getResponse().getHeader("Location");
            assertNotNull(location);
            var reservationId = location.substring(location.indexOf("/reservation/") + "/reservation/".length(), location.length() - "/book".length());
            assertEquals(1, ticketRepository.findTicketsInReservation(reservationId).size());
            reservationApiV2Controller.cancelPendingReservation(reservationId);
            ticketReservationManager.revertTicketsToFreeIfAccessRestricted(context.event.getId());
        }
    }

    @Test
    void discountAndUnknownCodesKeepExistingRedirects() throws Exception {
        var context = directLinkContext();
        eventManager.addPromoCode(PROMO_CODE, context.event.getId(), null, ZonedDateTime.now(clockProvider.getClock()).minusDays(2),
            context.event.getEnd().plusDays(2), 10, PromoCodeDiscount.DiscountType.PERCENTAGE, null, 3,
            "description", "test@test.ch", PromoCodeDiscount.CodeType.DISCOUNT, null, null);
        var mvc = directLinkMvc();
        mvc.perform(get("/api/v2/public/event/{event}/code/{code}", context.event.getShortName(), PROMO_CODE).param("qty", "invalid"))
            .andExpect(redirectedUrl("/event/" + context.event.getShortName() + "?code=" + PROMO_CODE));
        mvc.perform(get("/api/v2/public/event/{event}/code/UNKNOWN", context.event.getShortName()).param("qty", "3"))
            .andExpect(redirectedUrl("/event/" + context.event.getShortName() + "?errors=" + ErrorsCode.STEP_1_CODE_NOT_FOUND));
        assertEquals(0, jdbcTemplate.queryForObject("select count(*) from tickets_reservation where event_id_fk = :eventId", Map.of("eventId", context.event.getId()), Integer.class));
    }

    @Override
    protected void performAdditionalTests(ReservationFlowContext context) {
        var event = context.event;
        BaseIntegrationTest.testTransferEventToAnotherOrg(event.getId(), event.getOrganizationId(), context.userId, jdbcTemplate);
    }

    @Override
    protected void validateCheckInData(ReservationFlowContext context) {
        var entries = checkInManager.retrieveLogEntries(context.event.getShortName(), context.userId);
        assertEquals(1, entries.size());
        var entry = entries.getFirst();
        assertNotNull(entry.getTicketId());
        assertNotNull(entry.getAttendeeData());
        assertNotNull(entry.getAttendeeData().getMetadata());
        assertNotNull(entry.getAudit());
        entry.getAudit().forEach(audit -> assertEquals(context.userId, audit.getUsername()));
    }
}
