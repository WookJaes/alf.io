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

import alfio.manager.i18n.MessageSourceManager;
import alfio.model.Event;
import alfio.model.PurchaseContextFieldConfiguration;
import alfio.model.modification.AdditionalFieldRequest;
import alfio.model.modification.EventModification;
import alfio.repository.AdditionalServiceRepository;
import alfio.repository.PurchaseContextFieldRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.validation.BeanPropertyBindingResult;

import java.util.List;
import java.util.Map;

import static alfio.util.Validator.validateAdditionalFields;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PurchaseContextFieldManagerTest {
    private PurchaseContextFieldRepository repository;
    private PurchaseContextFieldManager manager;

    @BeforeEach
    void setUp() {
        repository = mock(PurchaseContextFieldRepository.class);
        manager = new PurchaseContextFieldManager(repository, mock(AdditionalServiceRepository.class), mock(MessageSourceManager.class));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsCreationWithoutOptions(List<EventModification.RestrictedValue> options) {
        var request = create("select", true, options);
        var result = validateAdditionalFields(List.of(), request, new BeanPropertyBindingResult(request, "field"));
        assertFalse(result.isSuccess());
        assertEquals("restrictedValues", result.getValidationErrors().getFirst().getFieldName());
        var event = mock(Event.class);
        when(event.ofType(alfio.model.PurchaseContext.PurchaseContextType.event)).thenReturn(true);
        assertFalse(manager.validateAndAddField(event, request, new BeanPropertyBindingResult(request, "field")).isSuccess());
        verify(repository, never()).insertConfiguration(any(), anyInt(), any(), anyString(), anyInt(), anyString(), any(), any(), any(), anyBoolean(), any(), anyInt(), any(), anyBoolean());
    }

    @Test
    void rejectsBlankOptionOnCreation() {
        var request = create("select", true, List.of(new EventModification.RestrictedValue(" ", true)));
        assertFalse(validateAdditionalFields(List.of(), request, new BeanPropertyBindingResult(request, "field")).isSuccess());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsRemovingEveryOptionOnUpdate(List<String> options) {
        existingSelect();
        assertThrows(IllegalArgumentException.class, () -> manager.updateAdditionalField(42, update("select", true, options, List.of()), 1));
        verify(repository, never()).updateField(anyLong(), anyBoolean(), anyBoolean(), any(), any(), any(), anyBoolean());
        verify(repository, never()).upsertDescription(anyLong(), anyString(), anyString(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"input:text", "country"})
    void cannotBypassUpdateUsingAnotherType(String requestType) {
        existingSelect();
        assertThrows(IllegalArgumentException.class, () -> manager.updateAdditionalField(42, update(requestType, true, List.of("blue"), List.of()), 1));
        verify(repository, never()).updateField(anyLong(), anyBoolean(), anyBoolean(), any(), any(), any(), anyBoolean());
    }

    @Test
    void rejectsAllDisabledOptionsOnUpdate() {
        existingSelect();
        assertThrows(IllegalArgumentException.class, () -> manager.updateAdditionalField(42, update("select", true, List.of("blue"), List.of("blue")), 1));
        verify(repository, never()).updateField(anyLong(), anyBoolean(), anyBoolean(), any(), any(), any(), anyBoolean());
    }

    @Test
    void acceptsValidDropdownCreationAndUpdate() {
        var request = create("select", true, List.of(new EventModification.RestrictedValue("blue", true)));
        assertTrue(validateAdditionalFields(List.of(), request, new BeanPropertyBindingResult(request, "field")).isSuccess());
        existingSelect();
        manager.updateAdditionalField(42, update("select", true, List.of("blue", "red"), List.of("red")), 1);
        verify(repository).updateField(42, true, true, "[\"blue\",\"red\"]", "[\"red\"]", null, false);
    }

    @Test
    void preservesOptionalDropdownAndOtherFieldTypes() {
        for (var type : List.of("select", "input:text", "country", "checkbox", "radio")) {
            boolean required = !type.equals("select");
            var request = create(type, required, List.of());
            assertTrue(validateAdditionalFields(List.of(), request, new BeanPropertyBindingResult(request, "field")).isSuccess(), type);
            var existing = mock(PurchaseContextFieldConfiguration.class);
            when(existing.getType()).thenReturn(type);
            when(repository.findById(42)).thenReturn(existing);
            manager.updateAdditionalField(42, update(type, required, List.of(), List.of()), 1);
        }
        verify(repository, times(5)).updateField(eq(42L), anyBoolean(), eq(true), isNull(), isNull(), isNull(), eq(false));
    }

    private void existingSelect() {
        var existing = mock(PurchaseContextFieldConfiguration.class);
        when(existing.getType()).thenReturn("select");
        when(repository.findById(42)).thenReturn(existing);
    }

    private static AdditionalFieldRequest create(String type, boolean required, List<EventModification.RestrictedValue> options) {
        return new AdditionalFieldRequest(0, false, "synthetic_field", type, required, false, null, null, options, Map.of(), null, List.of(), false);
    }

    private static EventModification.UpdateAdditionalField update(String type, boolean required, List<String> options, List<String> disabled) {
        return new EventModification.UpdateAdditionalField(type, required, false, options, disabled, Map.of(), List.of(), false);
    }
}
