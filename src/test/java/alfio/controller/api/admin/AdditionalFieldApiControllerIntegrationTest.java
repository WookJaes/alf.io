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
import alfio.manager.EventManager;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.repository.EventRepository;
import alfio.repository.PurchaseContextFieldRepository;
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.test.util.IntegrationTestUtil;
import alfio.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AlfioIntegrationTest
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class, ControllerConfiguration.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class AdditionalFieldApiControllerIntegrationTest {
    @Autowired private AdditionalFieldApiController controller;
    @Autowired private ConfigurationRepository configurationRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserManager userManager;
    @Autowired private EventManager eventManager;
    @Autowired private EventRepository eventRepository;
    @Autowired private PurchaseContextFieldRepository fieldRepository;

    private MockMvc mvc;
    private Event event;
    private Principal owner;
    private String endpoint;

    @BeforeEach
    void setUp() {
        IntegrationTestUtil.ensureMinimalConfiguration(configurationRepository);
        var fixture = IntegrationTestUtil.initEvent(List.of(), organizationRepository, userManager, eventManager, eventRepository);
        event = fixture.getLeft();
        owner = () -> IntegrationTestUtil.owner(fixture.getRight());
        endpoint = "/admin/api/event/" + event.getShortName() + "/additional-field";
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new JacksonJsonHttpMessageConverter(Json.OBJECT_MAPPER)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"select", "radio"})
    void rejectsEmptyRequiredSingleChoiceCreationWithoutPersistingIt(String type) throws Exception {
        for (var options : List.of(List.of(), List.of(Map.of("value", " ", "enabled", true)), List.of(Map.of("value", "blue", "enabled", false)))) {
            var response = mvc.perform(post(endpoint + "/new").principal(owner).contentType(MediaType.APPLICATION_JSON)
                .content(Json.toJson(createBody(type, true, options)))).andExpect(status().isOk()).andReturn();
            var result = Json.OBJECT_MAPPER.readTree(response.getResponse().getContentAsString());
            assertFalse(result.path("success").asBoolean());
            assertEquals("restrictedValues", result.path("validationErrors").get(0).path("fieldName").asString());
            assertTrue(fieldRepository.findAdditionalFieldsForEvent(event.getId()).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"select", "radio"})
    void rejectsInvalidUpdatesAndPreservesStoredOptionsAndRequiredFlag(String type) throws Exception {
        createField(type, true, List.of(Map.of("value", "blue", "enabled", true)));
        var id = fieldRepository.findAdditionalFieldsForEvent(event.getId()).getFirst().getId();
        for (var body : List.of(
            Map.of("type", type, "required", true, "description", Map.of()),
            Map.of("type", type, "required", true, "restrictedValues", List.of(" "), "description", Map.of()),
            Map.of("type", type, "required", true, "restrictedValues", List.of(), "description", Map.of()),
            Map.of("type", "input:text", "required", true, "restrictedValues", List.of("blue"), "description", Map.of()),
            Map.of("type", type, "required", true, "restrictedValues", List.of("blue"), "disabledValues", List.of("blue"), "description", Map.of()))) {
            mvc.perform(post(endpoint + "/" + id).principal(owner).contentType(MediaType.APPLICATION_JSON)
                .content(Json.toJson(body))).andExpect(status().isBadRequest());
            var saved = fieldRepository.findById(id);
            assertTrue(saved.isRequired());
            assertEquals(List.of("blue"), saved.getRestrictedValues());
            assertTrue(saved.getDisabledValues().isEmpty());
        }
        mvc.perform(post(endpoint + "/" + id).principal(owner).contentType(MediaType.APPLICATION_JSON)
            .content(Json.toJson(Map.of("type", type, "required", true, "restrictedValues", List.of("blue", "green"), "description", Map.of()))))
            .andExpect(status().isOk());
        assertEquals(List.of("blue", "green"), fieldRepository.findById(id).getRestrictedValues());
    }

    @ParameterizedTest
    @ValueSource(strings = {"select", "radio"})
    void allowsOptionalEmptySingleChoiceButRejectsMakingItRequired(String type) throws Exception {
        createField(type, false, List.of());
        var id = fieldRepository.findAdditionalFieldsForEvent(event.getId()).getFirst().getId();
        mvc.perform(post(endpoint + "/" + id).principal(owner).contentType(MediaType.APPLICATION_JSON)
            .content(Json.toJson(Map.of("type", type, "required", true, "description", Map.of()))))
            .andExpect(status().isBadRequest());
        assertFalse(fieldRepository.findById(id).isRequired());
    }

    @ParameterizedTest
    @ValueSource(strings = {"select", "radio"})
    void preservesDisabledOptionsWhenCreatingValidSingleChoice(String type) throws Exception {
        createField(type, true, List.of(Map.of("value", "blue", "enabled", true), Map.of("value", "red", "enabled", false)));
        var field = fieldRepository.findAdditionalFieldsForEvent(event.getId()).getFirst();
        assertEquals(List.of("blue", "red"), field.getRestrictedValues());
        assertEquals(List.of("red"), field.getDisabledValues());
    }

    private void createField(String type, boolean required, List<?> options) throws Exception {
        var response = mvc.perform(post(endpoint + "/new").principal(owner).contentType(MediaType.APPLICATION_JSON)
            .content(Json.toJson(createBody(type, required, options)))).andExpect(status().isOk()).andReturn();
        assertTrue(Json.OBJECT_MAPPER.readTree(response.getResponse().getContentAsString()).path("success").asBoolean());
    }

    private static Map<String, Object> createBody(String type, boolean required, List<?> options) {
        return Map.of("name", "synthetic_dropdown", "type", type, "required", required,
            "restrictedValues", options, "description", Map.of("en", Map.of("label", "Synthetic dropdown")));
    }
}
