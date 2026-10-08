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

import alfio.TestConfiguration;
import alfio.config.DataSourceConfiguration;
import alfio.config.Initializer;
import alfio.manager.user.UserManager;
import alfio.model.*;
import alfio.model.PurchaseContext.PurchaseContextType;
import alfio.model.metadata.AlfioMetadata;
import alfio.model.modification.*;
import alfio.model.modification.AdminReservationModification.*;
import alfio.model.result.Result;
import alfio.repository.*;
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.test.util.IntegrationTestUtil;
import alfio.util.BaseIntegrationTest;
import alfio.util.ClockProvider;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.IntStream;

import static alfio.model.modification.AdminReservationModification.Notification.EMPTY;
import static alfio.test.util.IntegrationTestUtil.*;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.*;


@AlfioIntegrationTest
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class AdminReservationManagerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AdminReservationManager adminReservationManager;
    @Autowired
    private EventManager eventManager;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private UserManager userManager;
    @Autowired
    private TicketCategoryRepository ticketCategoryRepository;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private EmailMessageRepository emailMessageRepository;
    @Autowired
    private TicketReservationManager ticketReservationManager;
    @Autowired
    private SpecialPriceRepository specialPriceRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private ConfigurationRepository configurationRepository;

    @Autowired
    private AdditionalServiceItemRepository additionalServiceItemRepository;
    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @CsvSource({
        "MANDATORY_ONE_FOR_TICKET, 1", "MANDATORY_ONE_FOR_TICKET, 2",
        "MANDATORY_PERCENTAGE_FOR_TICKET, 1", "MANDATORY_PERCENTAGE_FOR_TICKET, 2",
        "MANDATORY_PERCENTAGE_RESERVATION, 1", "MANDATORY_PERCENTAGE_RESERVATION, 2"
    })
    void mandatoryFeesInAdminReservation(AdditionalService.SupplementPolicy policy, int quantity) {
        var now = ZonedDateTime.now(ClockProvider.clock());
        var eventAndUser = initFeeEvent(List.of(
            fee(policy, now.minusDays(1), now.plusDays(1), 1)), false);
        var event = eventAndUser.getLeft();
        var reservation = createFeeReservation(eventAndUser, quantity);
        var items = additionalServiceItemRepository.findByReservationUuid(event.getId(), reservation.getId());
        int expectedItems = policy == AdditionalService.SupplementPolicy.MANDATORY_ONE_FOR_TICKET ? quantity : 1;
        assertEquals(expectedItems, items.size());
        assertEquals(quantity * 1000, items.stream().mapToInt(AdditionalServiceItem::getFinalPriceCts).sum());
        assertTrue(items.stream().allMatch(item -> item.getStatus() == AdditionalServiceItem.AdditionalServiceItemStatus.PENDING));
        var ticketIds = ticketRepository.findTicketIdsInReservation(reservation.getId());
        assertTrue(items.stream().allMatch(item -> ticketIds.contains(item.getTicketId())));
        assertEquals(quantity * 11000, ticketReservationManager.totalReservationCostWithVAT(reservation.getId()).getLeft().getPriceWithVAT());
        assertEquals(quantity * 11000, ticketReservationManager.orderSummaryForReservationId(reservation.getId(), event).getPriceInCents());
    }

    @Test
    void optionalAndUnavailableFeesAreNotAdded() {
        var now = ZonedDateTime.now(ClockProvider.clock());
        var eventAndUser = initFeeEvent(List.of(
            fee(AdditionalService.SupplementPolicy.OPTIONAL_UNLIMITED_AMOUNT, now.minusDays(1), now.plusDays(1), 1),
            fee(AdditionalService.SupplementPolicy.MANDATORY_ONE_FOR_TICKET, now.minusDays(2), now.minusDays(1), 2),
            fee(AdditionalService.SupplementPolicy.MANDATORY_PERCENTAGE_FOR_TICKET, now.plusDays(1), now.plusDays(2), 3)), false);
        var reservation = createFeeReservation(eventAndUser, 2);
        assertTrue(additionalServiceItemRepository.findByReservationUuid(eventAndUser.getLeft().getId(), reservation.getId()).isEmpty());
        assertEquals(20000, ticketReservationManager.totalReservationCostWithVAT(reservation.getId()).getLeft().getPriceWithVAT());
    }

    @Test
    void percentageFeeIncludesAllCategoriesOnce() {
        var now = ZonedDateTime.now(ClockProvider.clock());
        var eventAndUser = initFeeEvent(List.of(
            fee(AdditionalService.SupplementPolicy.MANDATORY_PERCENTAGE_FOR_TICKET, now.minusDays(1), now.plusDays(1), 1)), true);
        var reservation = createFeeReservation(eventAndUser, 1);
        var items = additionalServiceItemRepository.findByReservationUuid(eventAndUser.getLeft().getId(), reservation.getId());
        assertEquals(1, items.size());
        assertEquals(1500, items.getFirst().getFinalPriceCts());
        assertEquals(16500, ticketReservationManager.totalReservationCostWithVAT(reservation.getId()).getLeft().getPriceWithVAT());
    }

    private EventModification.AdditionalService fee(AdditionalService.SupplementPolicy policy,
                                                   ZonedDateTime inception, ZonedDateTime expiration, int ordinal) {
        return new EventModification.AdditionalService(null, BigDecimal.TEN,
            !AdditionalService.SupplementPolicy.isMandatoryPercentage(policy), ordinal, -1, -1,
            DateTimeModification.fromZonedDateTime(inception), DateTimeModification.fromZonedDateTime(expiration),
            BigDecimal.ZERO, AdditionalService.VatType.NONE, List.of(),
            List.of(new EventModification.AdditionalServiceText(null, "en", "Booking fee", AdditionalServiceText.TextType.TITLE)),
            List.of(new EventModification.AdditionalServiceText(null, "en", "Synthetic booking fee", AdditionalServiceText.TextType.DESCRIPTION)),
            AdditionalService.AdditionalServiceType.SUPPLEMENT, policy, null, null);
    }

    private Pair<Event, String> initFeeEvent(List<EventModification.AdditionalService> fees, boolean multipleCategories) {
        var now = ZonedDateTime.now(ClockProvider.clock());
        var prices = multipleCategories ? List.of(new BigDecimal("100"), new BigDecimal("50")) : List.of(new BigDecimal("100"));
        var categories = prices.stream().map(price -> new TicketCategoryModification(null, "category " + price,
            TicketCategory.TicketAccessType.INHERIT, 10,
            DateTimeModification.fromZonedDateTime(now.minusDays(1)), DateTimeModification.fromZonedDateTime(now.plusDays(1)),
            DESCRIPTION, price, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty())).toList();
        var eventAndUser = initEvent(categories, organizationRepository, userManager, eventManager, eventRepository,
            fees, Event.EventFormat.IN_PERSON, PriceContainer.VatStatus.NONE);
        // The shared fixture sets 1% VAT; this regression uses the reproduced zero-tax condition.
        jdbcTemplate.update("update event set vat = 0, vat_status = 'NONE' where id = :eventId",
            Map.of("eventId", eventAndUser.getLeft().getId()));
        return Pair.of(eventRepository.findById(eventAndUser.getLeft().getId()), eventAndUser.getRight());
    }

    private TicketReservation createFeeReservation(Pair<Event, String> eventAndUser, int quantity) {
        var event = eventAndUser.getLeft();
        var ticketsInfo = ticketCategoryRepository.findAllTicketCategories(event.getId()).stream()
            .map(category -> new TicketsInfo(new Category(category.getId(), category.getName(), category.getPrice(), null),
                generateAttendees(quantity), false, false)).toList();
        var customer = new CustomerData("Synthetic", "Customer", "customer@example.test", "", "", "en", "", "CH", null);
        var modification = new AdminReservationModification(
            DateTimeModification.fromZonedDateTime(ZonedDateTime.now(ClockProvider.clock()).plusDays(1)),
            customer, ticketsInfo, "en", false, false, null, null, null, null);
        var result = adminReservationManager.createReservation(modification, event.getShortName(), eventAndUser.getRight());
        assertTrue(result.isSuccess(), () -> String.valueOf(result.getErrors()));
        return result.getData().getLeft();
    }

    @BeforeEach
    public void init() {
        IntegrationTestUtil.ensureMinimalConfiguration(configurationRepository);
    }

    @Test
    public void testReserveFromExistingCategory() {
        List<TicketCategoryModification> categories = Collections.singletonList(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, AVAILABLE_SEATS,
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                DESCRIPTION, BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        performExistingCategoryTest(categories, false, Collections.singletonList(1), false, true, 0, AVAILABLE_SEATS);
    }

    @Test
    public void testReserveFromExistingMultipleCategories() {
        List<TicketCategoryModification> categories = Arrays.asList(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, 10,
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                DESCRIPTION, BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()),
            new TicketCategoryModification(null, "2nd", TicketCategory.TicketAccessType.INHERIT, 10,
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                DESCRIPTION, BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        performExistingCategoryTest(categories, false, Arrays.asList(10,10), false, true, 0, AVAILABLE_SEATS);
    }

    @Test
    public void testReserveFromExistingCategoryNotEnoughSeatsNotBounded() {
        List<TicketCategoryModification> categories = Collections.singletonList(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, 1,
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                DESCRIPTION, BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        performExistingCategoryTest(categories, false, Collections.singletonList(1), false, true, 0, AVAILABLE_SEATS);
    }

    @Test
    public void testReserveExistingCategoryNotEnoughSeatsNotBoundedSoldOut() {
        List<TicketCategoryModification> categories = Collections.singletonList(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, 1,
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                DESCRIPTION, BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        performExistingCategoryTest(categories, false, Collections.singletonList(1), true, true, AVAILABLE_SEATS, AVAILABLE_SEATS+1);
    }

    @Test
    public void testReserveFromExistingCategoryNotEnoughSeatsBounded() {
        List<TicketCategoryModification> categories = Collections.singletonList(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, 1,
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                DESCRIPTION, BigDecimal.TEN, false, "", true, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        performExistingCategoryTest(categories, true, Collections.singletonList(2), false, true, 0, AVAILABLE_SEATS);
    }

    @Test
    public void testReserveFromExistingCategoryNotEnoughSeatsNoExtensionAllowedBounded() {
        List<TicketCategoryModification> categories = Collections.singletonList(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, AVAILABLE_SEATS,
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                DESCRIPTION, BigDecimal.TEN, false, "", true, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        performExistingCategoryTest(categories, true, Collections.singletonList(AVAILABLE_SEATS + 1), false, false, 0, AVAILABLE_SEATS);
    }

    @Test
    public void testReserveFromExistingCategoryNotEnoughSeatsNoExtensionAllowedNotBounded() {
        List<TicketCategoryModification> categories = Collections.singletonList(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, AVAILABLE_SEATS,
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                DESCRIPTION, BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        performExistingCategoryTest(categories, true, Collections.singletonList(AVAILABLE_SEATS + 1), false, false, 0, AVAILABLE_SEATS);
    }

    @Test
    public void testReserveFromNewCategory() {
        List<TicketCategoryModification> categories = Collections.singletonList(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, 1,
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                DESCRIPTION, BigDecimal.TEN, false, "", true, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        Pair<Event, String> eventWithUsername = initEvent(categories, organizationRepository, userManager, eventManager, eventRepository);
        Event event = eventWithUsername.getKey();
        String username = eventWithUsername.getValue();
        DateTimeModification expiration = DateTimeModification.fromZonedDateTime(ZonedDateTime.now(ClockProvider.clock()).plusDays(1));
        CustomerData customerData = new CustomerData("Integration", "Test", "integration-test@test.ch", "Billing Address", "reference", "en", "1234", "CH", null);
        Category category = new Category(null, "name", new BigDecimal("100.00"), null);
        int attendees = AVAILABLE_SEATS;
        List<TicketsInfo> ticketsInfoList = Collections.singletonList(new TicketsInfo(category, generateAttendees(attendees), true, false));
        AdminReservationModification modification = new AdminReservationModification(expiration, customerData, ticketsInfoList, "en", false, false, null, null, null, null);
        Result<Pair<TicketReservation, List<Ticket>>> result = adminReservationManager.createReservation(modification, event.getShortName(), username);
        assertTrue(result.isSuccess());
        Pair<TicketReservation, List<Ticket>> data = result.getData();
        List<Ticket> tickets = data.getRight();
        assertEquals(tickets.size(), attendees);
        assertNotNull(data.getLeft());
        int categoryId = tickets.getFirst().getCategoryId();
        eventManager.getSingleEvent(event.getShortName(), username);
        assertEquals(attendees + 1, eventRepository.countExistingTickets(event.getId()).intValue());
        assertEquals(attendees, ticketRepository.findPendingTicketsInCategories(Collections.singletonList(categoryId)).size());
        TicketCategory categoryModified = ticketCategoryRepository.getByIdAndActive(categoryId, event.getId());
        assertEquals(categoryModified.getMaxTickets(), attendees);
        ticketCategoryRepository.findAllTicketCategories(event.getId()).forEach(tc -> assertTrue(specialPriceRepository.findAllByCategoryId(tc.getId()).stream().allMatch(sp -> sp.getStatus() == SpecialPrice.Status.PENDING)));
        adminReservationManager.confirmReservation(PurchaseContextType.event, event.getShortName(), data.getLeft().getId(), username, EMPTY);
        ticketCategoryRepository.findAllTicketCategories(event.getId()).forEach(tc -> assertTrue(specialPriceRepository.findAllByCategoryId(tc.getId()).stream().allMatch(sp -> sp.getStatus() == SpecialPrice.Status.TAKEN)));
        assertFalse(ticketRepository.findAllReservationsConfirmedButNotAssignedForUpdate(event.getId()).contains(data.getLeft().getId()));
    }

    @Test
    public void testReserveMixed() {
        List<TicketCategoryModification> categories = Collections.singletonList(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, 1,
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                DESCRIPTION, BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        Pair<Event, String> eventWithUsername = initEvent(categories, organizationRepository, userManager, eventManager, eventRepository);
        Event event = eventWithUsername.getKey();
        String username = eventWithUsername.getValue();
        DateTimeModification expiration = DateTimeModification.fromZonedDateTime(ZonedDateTime.now(ClockProvider.clock()).plusDays(1));
        CustomerData customerData = new CustomerData("Integration", "Test", "integration-test@test.ch", "Billing Address", "reference", "en", "1234", "CH", null);

        TicketCategory existingCategory = ticketCategoryRepository.findAllTicketCategories(event.getId()).getFirst();


        Category resExistingCategory = new Category(existingCategory.getId(), "", existingCategory.getPrice(), null);
        Category resNewCategory = new Category(null, "name", new BigDecimal("100.00"), null);
        int attendees = 1;
        List<TicketsInfo> ticketsInfoList = Arrays.asList(new TicketsInfo(resExistingCategory, generateAttendees(attendees), false, false), new TicketsInfo(resNewCategory, generateAttendees(attendees), false, false),new TicketsInfo(resExistingCategory, generateAttendees(attendees), false, false));
        AdminReservationModification modification = new AdminReservationModification(expiration, customerData, ticketsInfoList, "en", false,false, null, null, null, null);
        Result<Pair<TicketReservation, List<Ticket>>> result = adminReservationManager.createReservation(modification, event.getShortName(), username);
        assertTrue(result.isSuccess());
        Pair<TicketReservation, List<Ticket>> data = result.getData();
        List<Ticket> tickets = data.getRight();
        assertEquals(3, tickets.size());
        assertNotNull(data.getLeft());
        assertTrue(tickets.stream().allMatch(t -> t.getTicketsReservationId().equals(data.getKey().getId())));
        int resExistingCategoryId = tickets.getFirst().getCategoryId();
        int resNewCategoryId = tickets.get(2).getCategoryId();

        eventManager.getSingleEvent(event.getShortName(), username);
        assertEquals(AVAILABLE_SEATS, eventRepository.countExistingTickets(event.getId()).intValue());
        assertEquals(3, ticketRepository.findPendingTicketsInCategories(Arrays.asList(resExistingCategoryId, resNewCategoryId)).size());
        assertEquals(3, ticketRepository.findTicketsInReservation(data.getLeft().getId()).size());

        String reservationId = data.getLeft().getId();
        assertEquals(ticketRepository.findTicketsInReservation(reservationId).stream().findFirst().get().getId(),
            ticketRepository.findFirstTicketInReservation(reservationId).get().getId());

        ticketCategoryRepository.findAllTicketCategories(event.getId()).forEach(tc -> assertTrue(specialPriceRepository.findAllByCategoryId(tc.getId()).stream().allMatch(sp -> sp.getStatus() == SpecialPrice.Status.PENDING)));
        adminReservationManager.confirmReservation(PurchaseContextType.event, event.getShortName(), data.getLeft().getId(), username, EMPTY);
        ticketCategoryRepository.findAllTicketCategories(event.getId()).forEach(tc -> assertTrue(specialPriceRepository.findAllByCategoryId(tc.getId()).stream().allMatch(sp -> sp.getStatus() == SpecialPrice.Status.TAKEN)));
        assertFalse(ticketRepository.findAllReservationsConfirmedButNotAssignedForUpdate(event.getId()).contains(data.getLeft().getId()));
    }

    @Test
    public void testConfirmReservation() {
        List<TicketCategoryModification> categories = Collections.singletonList(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, 1,
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                DESCRIPTION, BigDecimal.TEN, false, "", true, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        Triple<Event, String, TicketReservation> testResult = performExistingCategoryTest(categories, true, Collections.singletonList(2), false, true, 0, AVAILABLE_SEATS);
        assertNotNull(testResult);
        var result = adminReservationManager.confirmReservation(PurchaseContextType.event, testResult.getLeft().getShortName(), testResult.getRight().getId(), testResult.getMiddle(), EMPTY);
        assertTrue(result.isSuccess());
        var triple = result.getData();
        assertEquals(PurchaseContextType.event, triple.getRight().getType());
        assertEquals(TicketReservation.TicketReservationStatus.COMPLETE, triple.getLeft().getStatus());
        triple.getMiddle().forEach(t -> assertEquals(Ticket.TicketStatus.ACQUIRED, t.getStatus()));
        int eventId = triple.getRight().event().orElseThrow().getId();
        assertTrue(emailMessageRepository.findByEventId(eventId, 0, 50, null).isEmpty());
        ticketCategoryRepository.findAllTicketCategories(eventId).forEach(tc -> assertTrue(specialPriceRepository.findAllByCategoryId(tc.getId()).stream().allMatch(sp -> sp.getStatus() == SpecialPrice.Status.TAKEN)));
        assertFalse(ticketRepository.findAllReservationsConfirmedButNotAssignedForUpdate(eventId).contains(triple.getLeft().getId()));
    }

    @Test
    public void testConfirmReservationSendConfirmationEmail() {
        List<TicketCategoryModification> categories = Collections.singletonList(
            new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, 1,
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                new DateTimeModification(LocalDate.now(ClockProvider.clock()), LocalTime.now(ClockProvider.clock())),
                DESCRIPTION, BigDecimal.TEN, false, "", true, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
        int attendees = 2;
        Triple<Event, String, TicketReservation> testResult = performExistingCategoryTest(categories, true, Collections.singletonList(attendees), false, true, 0, AVAILABLE_SEATS);
        assertNotNull(testResult);
        var result = adminReservationManager.confirmReservation(PurchaseContextType.event, testResult.getLeft().getShortName(), testResult.getRight().getId(), testResult.getMiddle(), new Notification(true, true));
        assertTrue(result.isSuccess());
        var triple = result.getData();
        assertEquals(TicketReservation.TicketReservationStatus.COMPLETE, triple.getLeft().getStatus());
        assertEquals(PurchaseContextType.event, triple.getRight().getType());
        triple.getMiddle().forEach(t -> assertEquals(Ticket.TicketStatus.ACQUIRED, t.getStatus()));
        int eventId = triple.getRight().event().orElseThrow().getId();
        assertEquals(attendees + 2, emailMessageRepository.findByEventId(eventId, 0, 50, null).size());
        ticketCategoryRepository.findAllTicketCategories(eventId).forEach(tc -> assertTrue(specialPriceRepository.findAllByCategoryId(tc.getId()).stream().allMatch(sp -> sp.getStatus() == SpecialPrice.Status.TAKEN)));
        assertFalse(ticketRepository.findAllReservationsConfirmedButNotAssignedForUpdate(eventId).contains(triple.getLeft().getId()));
    }

    private Triple<Event, String, TicketReservation> performExistingCategoryTest(List<TicketCategoryModification> categories, boolean bounded,
                                                                                 List<Integer> attendeesNr, boolean addSeatsIfNotAvailable, boolean expectSuccess,
                                                                                 int reservedTickets, int expectedEventSeats) {
        assertEquals(categories.size(), attendeesNr.size(), "Test error: categories' size must be equal to attendees' size");
        Pair<Event, String> eventWithUsername = initEvent(categories, organizationRepository, userManager, eventManager, eventRepository);
        Event event = eventWithUsername.getKey();
        String username = eventWithUsername.getValue();
        DateTimeModification expiration = DateTimeModification.fromZonedDateTime(ZonedDateTime.now(ClockProvider.clock()).plusDays(1));
        CustomerData customerData = new CustomerData("Integration", "Test", "integration-test@test.ch", "Billing Address", "reference", "en", "1234", "CH", null);
        Iterator<Integer> attendeesIterator = attendeesNr.iterator();
        List<TicketCategory> existingCategories = ticketCategoryRepository.findAllTicketCategories(event.getId());
        List<Attendee> allAttendees = new ArrayList<>();
        List<TicketsInfo> ticketsInfoList = existingCategories.stream()
            .map(existingCategory -> {
                Category category = new Category(existingCategory.getId(), existingCategory.getName(), existingCategory.getPrice(), null);
                List<Attendee> attendees = generateAttendees(attendeesIterator.next());
                allAttendees.addAll(attendees);
                return new TicketsInfo(category, attendees, addSeatsIfNotAvailable, false);
            }).collect(toList());
        AdminReservationModification modification = new AdminReservationModification(expiration, customerData, ticketsInfoList, "en", false,false, null, null, null, null);

        if(reservedTickets > 0) {
            TicketReservationModification trm = new TicketReservationModification();
            trm.setAmount(reservedTickets);
            trm.setTicketCategoryId(existingCategories.getFirst().getId());
            TicketReservationWithOptionalCodeModification r = new TicketReservationWithOptionalCodeModification(trm, Optional.empty());
            ticketReservationManager.createTicketReservation(event, Collections.singletonList(r), Collections.emptyList(), DateUtils.addDays(new Date(), 1), Optional.empty(), Locale.ENGLISH, false, null);
        }

        Result<Pair<TicketReservation, List<Ticket>>> result = adminReservationManager.createReservation(modification, event.getShortName(), username);
        if(expectSuccess) {
            validateSuccess(bounded, attendeesNr, event, username, existingCategories, result, allAttendees, expectedEventSeats, reservedTickets);
        } else {
            assertFalse(result.isSuccess());
            return null;
        }
        return Triple.of(eventWithUsername.getLeft(), eventWithUsername.getRight(), result.getData().getKey());
    }

    private void validateSuccess(boolean bounded, List<Integer> attendeesNr, Event event, String username, List<TicketCategory> existingCategories, Result<Pair<TicketReservation,
        List<Ticket>>> result, List<Attendee> allAttendees, int expectedEventSeats, int reservedTickets) {

        assertTrue(result.isSuccess());
        Pair<TicketReservation, List<Ticket>> data = result.getData();
        assertEquals(data.getRight().size(), attendeesNr.stream().mapToInt(i -> i).sum());
        assertNotNull(data.getLeft());
        Event modified = eventManager.getSingleEvent(event.getShortName(), username);
        assertEquals(expectedEventSeats, eventRepository.countExistingTickets(event.getId()).intValue());
        List<Ticket> tickets = ticketRepository.findPendingTicketsInCategories(existingCategories.stream().map(TicketCategory::getId).collect(toList()));
        assertEquals(attendeesNr.stream().mapToInt(i -> i).sum(), tickets.size() - reservedTickets);
        if(bounded) {
            final Iterator<Integer> iterator = attendeesNr.iterator();
            existingCategories.forEach(existingCategory -> {
                TicketCategory categoryModified = ticketCategoryRepository.getByIdAndActive(existingCategory.getId(), event.getId());
                assertEquals(categoryModified.getMaxTickets(), iterator.next().intValue());
            });
        }
        for (int i = 0; i < tickets.size() - reservedTickets; i++) {
            Attendee attendee = allAttendees.get(i);
            if(!attendee.isEmpty()) {
                Ticket ticket = data.getRight().get(i);
                assertTrue(ticket.getAssigned());
                assertNotNull(ticket.getFullName());
                assertEquals(attendee.getFullName(), ticket.getFullName());
                assertEquals(attendee.getEmailAddress(), ticket.getEmail());
                assertEquals(Ticket.TicketStatus.PENDING, ticket.getStatus());
                assertEquals(data.getLeft().getId(), ticket.getTicketsReservationId());
            }
        }
        ticketCategoryRepository.findAllTicketCategories(modified.getId()).forEach(tc -> assertTrue(specialPriceRepository.findAllByCategoryId(tc.getId()).stream().allMatch(sp -> sp.getStatus() == SpecialPrice.Status.PENDING)));
        assertFalse(ticketRepository.findAllReservationsConfirmedButNotAssignedForUpdate(event.getId()).contains(data.getLeft().getId()));
    }

    private List<Attendee> generateAttendees(int count) {
        return IntStream.range(0, count)
            .mapToObj(i -> new Attendee(null, "Attendee "+i, "Test" + i, "attendee"+i+"@test.ch", "en",false, null, null, Collections.emptyMap(), null))
            .collect(toList());
    }
}