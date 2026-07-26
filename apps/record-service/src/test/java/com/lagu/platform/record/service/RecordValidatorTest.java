package com.lagu.platform.record.service;

import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.record.client.MetadataClient;
import com.lagu.platform.record.client.MetadataClient.FieldSchemaDto;
import com.lagu.platform.record.client.MetadataClient.ObjectTypeSchemaDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ARRAY_OF_OBJECTS fields (wish lists, planning tasks, budget items, ...) previously fell
 * through validateByType's default no-op — any shape, or no shape at all, was accepted. These
 * tests pin the new shallow item-schema check: the field itself must be a List, and each
 * item's declared-required sub-fields must be present.
 */
class RecordValidatorTest {

    private final MetadataClient metadataClient = mock(MetadataClient.class);
    private final RecordValidator validator = new RecordValidator(metadataClient);

    private static FieldSchemaDto arrayField(String name, boolean required, List<Map<String, Object>> itemSchema) {
        return new FieldSchemaDto(name, name, "ARRAY_OF_OBJECTS", required,
                false, false, false, false, null, null, null, itemSchema);
    }

    private static Map<String, Object> item(String name, String type, boolean required) {
        return Map.of("name", name, "type", type, "required", required);
    }

    @Test
    void rejectsNonListValue() {
        var schema = new ObjectTypeSchemaDto("BIRTHDAY_EVENT", List.of(
                arrayField("wish_list", false, List.of(item("item", "TEXT", true)))));
        when(metadataClient.getSchema("BIRTHDAY_EVENT")).thenReturn(schema);

        assertThatThrownBy(() -> validator.validate("BIRTHDAY_EVENT", Map.of("wish_list", "not-a-list")))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getFieldErrors())
                        .anyMatch(msg -> msg.contains("wish_list") && msg.contains("must be an array")));
    }

    @Test
    void rejectsItemMissingRequiredSubField() {
        var schema = new ObjectTypeSchemaDto("BIRTHDAY_EVENT", List.of(
                arrayField("wish_list", false, List.of(
                        item("item", "TEXT", true), item("priority", "TEXT", false)))));
        when(metadataClient.getSchema("BIRTHDAY_EVENT")).thenReturn(schema);

        Map<String, Object> data = Map.of("wish_list", List.of(Map.of("priority", "HIGH")));

        assertThatThrownBy(() -> validator.validate("BIRTHDAY_EVENT", data))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getFieldErrors())
                        .anyMatch(msg -> msg.contains("wish_list[0].item") && msg.contains("required")));
    }

    @Test
    void acceptsWellFormedItems() {
        var schema = new ObjectTypeSchemaDto("BIRTHDAY_EVENT", List.of(
                arrayField("wish_list", false, List.of(
                        item("item", "TEXT", true), item("purchased", "BOOLEAN", false)))));
        when(metadataClient.getSchema("BIRTHDAY_EVENT")).thenReturn(schema);

        Map<String, Object> data = Map.of("wish_list", List.of(
                Map.of("item", "Lego set", "purchased", false),
                Map.of("item", "Bicycle")));

        validator.validate("BIRTHDAY_EVENT", data); // must not throw
    }

    @Test
    void rejectsWrongSubFieldType() {
        var schema = new ObjectTypeSchemaDto("BIRTHDAY_EVENT", List.of(
                arrayField("budget_items", false, List.of(item("estimatedCost", "DECIMAL", false)))));
        when(metadataClient.getSchema("BIRTHDAY_EVENT")).thenReturn(schema);

        Map<String, Object> data = Map.of("budget_items", List.of(Map.of("estimatedCost", "not-a-number")));

        assertThatThrownBy(() -> validator.validate("BIRTHDAY_EVENT", data))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getFieldErrors())
                        .anyMatch(msg -> msg.contains("estimatedCost") && msg.contains("must be a number")));
    }
}
