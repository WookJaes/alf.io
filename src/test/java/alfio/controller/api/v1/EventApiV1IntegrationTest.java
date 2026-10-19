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
package alfio.controller.api.v1;

import alfio.TestConfiguration;
import alfio.config.DataSourceConfiguration;
import alfio.config.Initializer;
import alfio.controller.api.ControllerConfiguration;
import alfio.controller.api.ApiControllerExceptionHandler;
import alfio.controller.api.admin.EventApiController;
import alfio.controller.api.v1.admin.EventApiV1Controller;
import alfio.manager.EventManager;
import alfio.manager.EventStatisticsManager;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.model.TicketCategory;
import alfio.model.TicketCategoryWithAdditionalInfo;
import alfio.model.api.v1.admin.AdditionalInfoRequest;
import alfio.model.api.v1.admin.DescriptionRequest;
import alfio.model.api.v1.admin.EventCreationRequest;
import alfio.model.modification.OrganizationModification;
import alfio.model.transaction.PaymentProxy;
import alfio.model.user.Organization;
import alfio.model.user.Role;
import alfio.model.user.User;
import alfio.repository.TicketCategoryRepository;
import alfio.repository.EventRepository;
import alfio.repository.PurchaseContextFieldRepository;
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.test.util.IntegrationTestUtil;
import alfio.util.BaseIntegrationTest;
import alfio.util.ClockProvider;
import alfio.util.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.*;


@AlfioIntegrationTest
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class, ControllerConfiguration.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class EventApiV1IntegrationTest extends BaseIntegrationTest {

    public static final int MAX_TICKETS = 10;

    @BeforeAll
    public static void initEnv() {
    }

    @Autowired
    private ConfigurationRepository configurationRepository;

    @Autowired
    private UserManager userManager;

    @Autowired
    private OrganizationRepository organizationRepository;


    @Autowired
    private EventApiV1Controller controller;

    @Autowired
    private EventManager eventManager;

    @Autowired
    private EventStatisticsManager eventStatisticsManager;

    @Autowired
    private TicketCategoryRepository ticketCategoryRepository;


    @Autowired
    private EventApiController adminController;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PurchaseContextFieldRepository fieldRepository;

    private String organizationName;
    private String username;
    private Organization organization;
    private Principal mockPrincipal;
    private Principal ownerPrincipal;

    private final String defaultSlug = "test";

    @BeforeEach
    public void ensureConfiguration() {
        IntegrationTestUtil.ensureMinimalConfiguration(configurationRepository);

        this.organizationName = UUID.randomUUID().toString();
        this.username = UUID.randomUUID().toString();

        var organizationModification = new OrganizationModification(null, organizationName, "email@example.com", "org", null, null);
        userManager.createOrganization(organizationModification, null);
        this.organization = organizationRepository.findByName(organizationName).orElseThrow();
        userManager.insertUser(organization.getId(), username, "test", "test", "test@example.com", Role.API_CONSUMER, User.Type.INTERNAL, null);

        this.mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockPrincipal.getName()).thenReturn(username);

    }

    static EventCreationRequest creationRequest(String shortName, String additionalInfoName) {
        return new EventCreationRequest(
            "Title",
            shortName,
            Collections.singletonList(new DescriptionRequest("en", "desc")),
            null,
            new EventCreationRequest.LocationRequest(
                "Pollegio 6742 Switzerland",
                new EventCreationRequest.CoordinateRequest("45.5","9.00")
            ),
            "Europe/Zurich",
            LocalDateTime.now(ClockProvider.clock()).plusDays(30),
            LocalDateTime.now(ClockProvider.clock()).plusDays(30).plusHours(2),
            "https://alf.io",
            "https://alf.io",
            "https://alf.io",
            "https://alf.io/img/tutorials/check-in-app/003.png",
            new EventCreationRequest.TicketRequest(
                false,
                MAX_TICKETS,
                "CHF",
                new BigDecimal("7.7"),
                true,
                Arrays.asList(PaymentProxy.OFFLINE,PaymentProxy.STRIPE),
                Collections.singletonList(
                    new EventCreationRequest.CategoryRequest(
                        null, // forces new category
                        "standard",
                        Collections.singletonList(new DescriptionRequest("en", "desc")),
                        MAX_TICKETS,
                        false,
                        BigDecimal.TEN,
                        LocalDateTime.of(2019, 1, 10, 12, 0),
                        LocalDateTime.of(2019, 1, 30, 18, 0),
                        null,
                        null,
                        null,
                        null
                    )
                ),
                null
            ),
            null,
            List.of(new AdditionalInfoRequest(0, additionalInfoName, AdditionalInfoRequest.AdditionalInfoType.GENERIC_TEXT, false, List.of(new DescriptionRequest("en", "label")), List.of(new DescriptionRequest("en", "placeholder")), null, null, null))
        );
    }

    static EventCreationRequest creationRequest(String shortName) {
        return creationRequest(shortName, "additionalInfoName");
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsEmptyRequiredDropdownInApiCreation(List<AdditionalInfoRequest.RestrictedValueRequest> options) throws Exception {
        var request = dropdownRequest("invalid-api-dropdown", true, options);
        var response = creationMvc().perform(post("/api/v1/admin/event/create").principal(mockPrincipal)
            .contentType(MediaType.APPLICATION_JSON).content(Json.toJson(request))).andReturn().getResponse();
        assertTrue(eventRepository.findOptionalByShortName(request.getSlug()).isEmpty());
        assertEquals(400, response.getStatus(), response.getContentAsString());
        assertTrue(response.getContentAsString().contains("invalid.additionalInfo"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsEmptyRequiredDropdownInAdminCreation(List<AdditionalInfoRequest.RestrictedValueRequest> options) throws Exception {
        var request = dropdownRequest("invalid-admin-dropdown", true, options);
        var modification = request.toEventModification(organization, title -> request.getSlug(), null);
        var response = creationMvc().perform(post("/admin/api/events/new").principal(organizationOwner())
            .contentType(MediaType.APPLICATION_JSON).content(Json.toJson(modification))).andReturn().getResponse();
        assertTrue(eventRepository.findOptionalByShortName(request.getSlug()).isEmpty());
        assertEquals(400, response.getStatus(), response.getContentAsString());
        var result = Json.OBJECT_MAPPER.readTree(response.getContentAsString());
        assertFalse(result.path("success").asBoolean());
        assertEquals("ticketFields[0].restrictedValues", result.path("validationErrors").get(0).path("fieldName").asString());
        var check = creationMvc().perform(post("/admin/api/events/check").principal(organizationOwner())
            .contentType(MediaType.APPLICATION_JSON).content(Json.toJson(modification))).andReturn().getResponse();
        assertEquals(200, check.getStatus());
        assertFalse(Json.OBJECT_MAPPER.readTree(check.getContentAsString()).path("success").asBoolean());
    }

    @Test
    void acceptsRequiredDropdownInApiCreation() throws Exception {
        assertDropdownCreation(false, true, dropdownOptions());
    }

    @Test
    void acceptsRequiredDropdownInAdminCreation() throws Exception {
        assertDropdownCreation(true, true, dropdownOptions());
    }

    @Test
    void preservesOptionalEmptyDropdownInApiCreation() throws Exception {
        assertDropdownCreation(false, false, List.of());
    }

    @Test
    void preservesOptionalEmptyDropdownInAdminCreation() throws Exception {
        assertDropdownCreation(true, false, List.of());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsEmptyRequiredRadioInApiCreation(List<AdditionalInfoRequest.RestrictedValueRequest> options) throws Exception {
        var request = singleChoiceRequest("invalid-api-radio", true, options, AdditionalInfoRequest.AdditionalInfoType.RADIO);
        var response = creationMvc().perform(post("/api/v1/admin/event/create").principal(mockPrincipal)
            .contentType(MediaType.APPLICATION_JSON).content(Json.toJson(request))).andReturn().getResponse();
        assertTrue(eventRepository.findOptionalByShortName(request.getSlug()).isEmpty());
        assertEquals(400, response.getStatus(), response.getContentAsString());
        assertTrue(response.getContentAsString().contains("invalid.additionalInfo"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsEmptyRequiredRadioInAdminCreation(List<AdditionalInfoRequest.RestrictedValueRequest> options) throws Exception {
        var request = singleChoiceRequest("invalid-admin-radio", true, options, AdditionalInfoRequest.AdditionalInfoType.RADIO);
        var modification = request.toEventModification(organization, title -> request.getSlug(), null);
        var response = creationMvc().perform(post("/admin/api/events/new").principal(organizationOwner())
            .contentType(MediaType.APPLICATION_JSON).content(Json.toJson(modification))).andReturn().getResponse();
        assertTrue(eventRepository.findOptionalByShortName(request.getSlug()).isEmpty());
        assertEquals(400, response.getStatus(), response.getContentAsString());
        var result = Json.OBJECT_MAPPER.readTree(response.getContentAsString());
        assertFalse(result.path("success").asBoolean());
        assertEquals("ticketFields[0].restrictedValues", result.path("validationErrors").get(0).path("fieldName").asString());
    }

    @Test
    void rejectsBlankAndDisabledRadioInBothCreationPaths() throws Exception {
        for (boolean admin : List.of(false, true)) {
            for (var option : List.of(
                new AdditionalInfoRequest.RestrictedValueRequest(" ", true, List.of(new DescriptionRequest("en", "Blank"))),
                new AdditionalInfoRequest.RestrictedValueRequest("blue", false, List.of(new DescriptionRequest("en", "Blue"))))) {
                var request = singleChoiceRequest("invalid-radio-values", true, List.of(option), AdditionalInfoRequest.AdditionalInfoType.RADIO);
                Object body = admin ? request.toEventModification(organization, title -> request.getSlug(), null) : request;
                var response = creationMvc().perform(post(admin ? "/admin/api/events/new" : "/api/v1/admin/event/create")
                    .principal(admin ? organizationOwner() : mockPrincipal).contentType(MediaType.APPLICATION_JSON)
                    .content(Json.toJson(body))).andReturn().getResponse();
                assertEquals(400, response.getStatus(), response.getContentAsString());
                assertTrue(eventRepository.findOptionalByShortName(request.getSlug()).isEmpty());
            }
        }
    }

    @Test
    void acceptsValidRequiredAndOptionalRadioInBothCreationPaths() throws Exception {
        for (boolean admin : List.of(false, true)) {
            for (boolean required : List.of(false, true)) {
                var request = singleChoiceRequest("valid-radio-" + admin + "-" + required, required,
                    required ? dropdownOptions() : List.of(), AdditionalInfoRequest.AdditionalInfoType.RADIO);
                Object body = admin ? request.toEventModification(organization, title -> request.getSlug(), null) : request;
                var response = creationMvc().perform(post(admin ? "/admin/api/events/new" : "/api/v1/admin/event/create")
                    .principal(admin ? organizationOwner() : mockPrincipal).contentType(MediaType.APPLICATION_JSON)
                    .content(Json.toJson(body))).andReturn().getResponse();
                assertEquals(200, response.getStatus(), response.getContentAsString());
                var event = eventRepository.findOptionalByShortName(request.getSlug()).orElseThrow();
                var field = fieldRepository.findAdditionalFieldsForEvent(event.getId()).getFirst();
                assertEquals("radio", field.getType());
                assertEquals(required, field.isRequired());
                assertEquals(required ? List.of("blue") : List.of(), field.getRestrictedValues());
            }
        }
    }

    private void assertDropdownCreation(boolean admin, boolean required, List<AdditionalInfoRequest.RestrictedValueRequest> options) throws Exception {
        var request = dropdownRequest("valid-dropdown", required, options);
        Object body = admin ? request.toEventModification(organization, title -> request.getSlug(), null) : request;
        var response = creationMvc().perform(post(admin ? "/admin/api/events/new" : "/api/v1/admin/event/create")
            .principal(admin ? organizationOwner() : mockPrincipal).contentType(MediaType.APPLICATION_JSON)
            .content(Json.toJson(body))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        if (admin) {
            assertEquals("OK", response.getContentAsString());
        } else {
            assertEquals(request.getSlug(), response.getContentAsString());
        }
        var created = eventRepository.findOptionalByShortName(request.getSlug()).orElseThrow();
        var fields = fieldRepository.findAdditionalFieldsForEvent(created.getId());
        assertEquals(1, fields.size());
        assertEquals(required, fields.getFirst().isRequired());
        assertEquals(required ? List.of("blue") : List.of(), fields.getFirst().getRestrictedValues());
    }

    private MockMvc creationMvc() {
        return MockMvcBuilders.standaloneSetup(controller, adminController)
            .setControllerAdvice(new ApiControllerExceptionHandler())
            .setMessageConverters(new StringHttpMessageConverter(), new JacksonJsonHttpMessageConverter(Json.OBJECT_MAPPER))
            .build();
    }

    private Principal organizationOwner() {
        String owner = username + "-owner";
        if (ownerPrincipal == null) {
            userManager.insertUser(organization.getId(), owner, "Synthetic", "Owner", "owner@example.invalid", Role.OWNER, User.Type.INTERNAL, null);
            ownerPrincipal = () -> owner;
        }
        return ownerPrincipal;
    }

    private static List<AdditionalInfoRequest.RestrictedValueRequest> dropdownOptions() {
        return List.of(new AdditionalInfoRequest.RestrictedValueRequest("blue", true, List.of(new DescriptionRequest("en", "Blue"))));
    }

    private static EventCreationRequest dropdownRequest(String slug, boolean required, List<AdditionalInfoRequest.RestrictedValueRequest> options) {
        return singleChoiceRequest(slug, required, options, AdditionalInfoRequest.AdditionalInfoType.LIST_BOX);
    }

    private static EventCreationRequest singleChoiceRequest(String slug, boolean required, List<AdditionalInfoRequest.RestrictedValueRequest> options, AdditionalInfoRequest.AdditionalInfoType type) {
        var base = creationRequest(slug);
        return new EventCreationRequest(base.getTitle(), base.getSlug(), base.getDescription(), base.getFormat(), base.getLocation(),
            base.getTimezone(), base.getStartDate(), base.getEndDate(), base.getWebsiteUrl(), base.getTermsAndConditionsUrl(),
            base.getPrivacyPolicyUrl(), base.getImageUrl(), base.getTickets(), base.getExtensionSettings(),
            List.of(new AdditionalInfoRequest(0, "synthetic_dropdown", type,
                required, List.of(new DescriptionRequest("en", "Synthetic dropdown")), List.of(), options, null, false)));
    }

    @Test
    void validationFailed() {
        EventCreationRequest eventCreationRequest = creationRequest(defaultSlug, "additionalInfo.name");
        var response = controller.create(eventCreationRequest, mockPrincipal);
        assertTrue(response.getStatusCode().is4xxClientError());
    }

    @Test
    void createTest() {

        EventCreationRequest eventCreationRequest = creationRequest(defaultSlug);

        String slug = controller.create(eventCreationRequest,mockPrincipal).getBody();
        Event event = eventManager.getSingleEvent(slug,username);
        List<TicketCategory> tickets = ticketCategoryRepository.findAllTicketCategories(event.getId());
        assertEquals(eventCreationRequest.getTitle(),event.getDisplayName());
        assertEquals(eventCreationRequest.getSlug(),event.getShortName());
        assertEquals(eventCreationRequest.getTickets().getCurrency(),event.getCurrency());
        assertEquals(eventCreationRequest.getWebsiteUrl(),event.getWebsiteUrl());
        assertEquals(eventCreationRequest.getTickets().getPaymentMethods(),event.getAllowedPaymentProxies());
        assertTrue(event.getFileBlobIdIsPresent());
        assertEquals(eventCreationRequest.getTickets().getCategories().size(),tickets.size());
        tickets.forEach((t) -> {
                List<EventCreationRequest.CategoryRequest> requestCategories = eventCreationRequest.getTickets().getCategories().stream().filter((rt) -> rt.getName().equals(t.getName())).toList();
                assertEquals(1,requestCategories.size());
                requestCategories.forEach((rtc) -> {
                        assertNotEquals(0, t.getOrdinal());
                        assertEquals(t.getMaxTickets(), rtc.getMaxTickets().intValue());
                        assertEquals(0, t.getPrice().compareTo(rtc.getPrice()));
                    }
                );
            }
        );
    }

    @Test
    void stats() {
        controller.create(creationRequest(defaultSlug), mockPrincipal);
        var statsResponse = controller.stats(defaultSlug, mockPrincipal);
        assertTrue(statsResponse.getStatusCode().is2xxSuccessful());
        var stats = requireNonNull(statsResponse.getBody());
        int lastOrdinal = -1;
        for (TicketCategoryWithAdditionalInfo ticketCategory : stats.getTicketCategories()) {
            assertTrue(ticketCategory.getOrdinal() > 0);
            assertTrue(ticketCategory.getOrdinal() > lastOrdinal);
            lastOrdinal = ticketCategory.getOrdinal();
        }
    }

    @Test
    void updateTest() {
        controller.create(creationRequest(defaultSlug), mockPrincipal);
        String newTitle = "new title";
        int categoryId = eventManager.loadTicketCategories(eventManager.getSingleEvent(defaultSlug, username)).getFirst().getId();
        // decrease number of tickets
        EventCreationRequest updateRequest = new EventCreationRequest(newTitle,null,null,null, null,null,null,null,null,null, null,null,
            new EventCreationRequest.TicketRequest(null, MAX_TICKETS - 1,null,null,null,null,List.of(
                new EventCreationRequest.CategoryRequest(
                    categoryId,
                    "standard",
                    Collections.singletonList(new DescriptionRequest("en", "desc")),
                    MAX_TICKETS - 1,
                    false,
                    BigDecimal.TEN,
                    LocalDateTime.of(2019, 1, 10, 12, 0),
                    LocalDateTime.of(2019, 1, 30, 18, 0),
                    null,
                    null,
                    null,
                    null
                )
            ),null), null, null
        );
        controller.update(defaultSlug, updateRequest, mockPrincipal);
        var eventWithAdditionalInfo = eventStatisticsManager.getEventWithAdditionalInfo(defaultSlug,username);
        assertEquals(newTitle, eventWithAdditionalInfo.getDisplayName());
        assertEquals(MAX_TICKETS - 1, eventWithAdditionalInfo.getAvailableSeats());

        // increase number of tickets again
        updateRequest = new EventCreationRequest(newTitle,null,null,null, null,null,null,null,null,null, null,null,
            new EventCreationRequest.TicketRequest(null, MAX_TICKETS,null,null,null,null,List.of(
                new EventCreationRequest.CategoryRequest(
                    categoryId,
                    "standard",
                    Collections.singletonList(new DescriptionRequest("en", "desc")),
                    MAX_TICKETS,
                    false,
                    BigDecimal.TEN,
                    LocalDateTime.of(2019, 1, 10, 12, 0),
                    LocalDateTime.of(2019, 1, 30, 18, 0),
                    null,
                    null,
                    null,
                    null
                )
            ),null), null, null
        );
        controller.update(defaultSlug, updateRequest, mockPrincipal);
        eventWithAdditionalInfo = eventStatisticsManager.getEventWithAdditionalInfo(defaultSlug,username);
        assertEquals(newTitle, eventWithAdditionalInfo.getDisplayName());
        assertEquals(MAX_TICKETS, eventWithAdditionalInfo.getAvailableSeats());
    }

    @Test
    void updateExistingCategoryUsingId() {
        controller.create(creationRequest(defaultSlug), mockPrincipal);
        var existing = requireNonNull(controller.stats(defaultSlug, mockPrincipal).getBody());
        var existingCategory = existing.getTicketCategories().getFirst();
        var categoriesRequest = List.of(
            new EventCreationRequest.CategoryRequest(
                existingCategory.getId(),
                existingCategory.getName() + "_1",
                List.of(new DescriptionRequest("en", "desc")),
                existingCategory.getMaxTickets(),
                existingCategory.isAccessRestricted(),
                existingCategory.getPrice(),
                LocalDateTime.now(ClockProvider.clock()),
                LocalDateTime.now(ClockProvider.clock()).plusHours(1),
                existingCategory.getCode(),
                null,
                null,
                existingCategory.getTicketAccessType()
            )
        );
        var ticketRequest = new EventCreationRequest.TicketRequest(null, MAX_TICKETS,null,null,null,null, categoriesRequest,null);
        EventCreationRequest updateRequest = new EventCreationRequest(null,null,null,null, null,null,null,null,null,null, null,null,
            ticketRequest, null, null
        );
        assertTrue(controller.update(defaultSlug, updateRequest, mockPrincipal).getStatusCode().is2xxSuccessful());
        var modifiedCategories = ticketCategoryRepository.findAllTicketCategories(existing.getId());
        assertEquals(1, modifiedCategories.size());
        assertEquals(existingCategory.getName() + "_1", modifiedCategories.getFirst().getName());
    }

    @Test
    void updateExistingCategoryAndAddNewUsingId() {
        controller.create(creationRequest(defaultSlug), mockPrincipal);
        var existing = requireNonNull(controller.stats(defaultSlug, mockPrincipal).getBody());
        var existingCategory = existing.getTicketCategories().getFirst();
        var categoriesRequest = List.of(
            new EventCreationRequest.CategoryRequest(
                existingCategory.getId(),
                existingCategory.getName() + "_1",
                List.of(new DescriptionRequest("en", "desc")),
                existingCategory.getMaxTickets() - 5,
                existingCategory.isAccessRestricted(),
                existingCategory.getPrice(),
                LocalDateTime.now(ClockProvider.clock()),
                LocalDateTime.now(ClockProvider.clock()).plusHours(1),
                existingCategory.getCode(),
                null,
                null,
                existingCategory.getTicketAccessType()
            ),
            new EventCreationRequest.CategoryRequest(
                null,
                existingCategory.getName() + "_2",
                List.of(new DescriptionRequest("en", "desc")),
                existingCategory.getMaxTickets() - 5,
                existingCategory.isAccessRestricted(),
                existingCategory.getPrice(),
                LocalDateTime.now(ClockProvider.clock()),
                LocalDateTime.now(ClockProvider.clock()).plusHours(1),
                existingCategory.getCode(),
                null,
                null,
                existingCategory.getTicketAccessType()
            )
        );
        var ticketRequest = new EventCreationRequest.TicketRequest(null, MAX_TICKETS,null,null,null,null, categoriesRequest,null);
        EventCreationRequest updateRequest = new EventCreationRequest(null,null,null,null, null,null,null,null,null,null, null,null,
            ticketRequest, null, null
        );
        assertTrue(controller.update(defaultSlug, updateRequest, mockPrincipal).getStatusCode().is2xxSuccessful());
        var modifiedCategories = ticketCategoryRepository.findAllTicketCategories(existing.getId());
        assertEquals(2, modifiedCategories.size());
        assertEquals(existingCategory.getName() + "_1", modifiedCategories.getFirst().getName());
        assertEquals(existingCategory.getOrdinal(), modifiedCategories.getFirst().getOrdinal());
        assertEquals(existingCategory.getName() + "_2", modifiedCategories.get(1).getName());
        assertEquals(existingCategory.getOrdinal() + 1, modifiedCategories.get(1).getOrdinal());
    }

    @Test
    void updateExistingCategoryUsingName() {
        controller.create(creationRequest(defaultSlug), mockPrincipal);
        var existing = requireNonNull(controller.stats(defaultSlug, mockPrincipal).getBody());
        var existingCategory = existing.getTicketCategories().getFirst();
        var categoriesRequest = List.of(
            new EventCreationRequest.CategoryRequest(null,
                existingCategory.getName(),
                List.of(new DescriptionRequest("en", "desc")),
                existingCategory.getMaxTickets() - 1,
                existingCategory.isAccessRestricted(),
                existingCategory.getPrice(),
                LocalDateTime.now(ClockProvider.clock()),
                LocalDateTime.now(ClockProvider.clock()).plusHours(1),
                existingCategory.getCode(),
                null,
                null,
                existingCategory.getTicketAccessType()
            )
        );
        var ticketRequest = new EventCreationRequest.TicketRequest(null, MAX_TICKETS,null,null,null,null, categoriesRequest,null);
        EventCreationRequest updateRequest = new EventCreationRequest(null,null,null,null, null,null,null,null,null,null, null,null,
            ticketRequest, null, null
        );
        assertTrue(controller.update(defaultSlug, updateRequest, mockPrincipal).getStatusCode().is2xxSuccessful());
        var modifiedCategories = ticketCategoryRepository.findAllTicketCategories(existing.getId());
        assertEquals(1, modifiedCategories.size());
        assertEquals(existingCategory.getMaxTickets() - 1, modifiedCategories.getFirst().getMaxTickets());
    }

    @Test
    void retrieveLinkedSubscriptions() {
        controller.create(creationRequest(defaultSlug),mockPrincipal);
        var response = controller.getLinkedSubscriptions(defaultSlug, mockPrincipal);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        var linkedSubscriptions = response.getBody();
        assertNotNull(linkedSubscriptions);
        assertTrue(linkedSubscriptions.getSubscriptions().isEmpty());
        assertEquals(defaultSlug, linkedSubscriptions.getEventSlug());
    }


}
