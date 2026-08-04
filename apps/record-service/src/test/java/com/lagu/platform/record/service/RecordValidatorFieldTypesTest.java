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
 * Covers the FieldTypes that used to fall through validateByType's silent default no-op
 * (ENTITY_REFERENCE, USER_REFERENCE, JSON, GEOLOCATION, ADDRESS, CURRENCY, FILE, IMAGE, and
 * DATE/DATETIME/TIME, previously deferred entirely to "client-side"), plus regression tests for
 * the three crash-safety bugs found alongside them: an unchecked (Number) cast on a
 * schema-author-supplied validationRules entry threw ClassCastException instead of a clean
 * validation error, an invalid regex pattern threw PatternSyntaxException, and isBlank() didn't
 * treat an empty List/Map as blank for a required field.
 */
class RecordValidatorFieldTypesTest {

    private final MetadataClient metadataClient = mock(MetadataClient.class);
    private final RecordValidator validator = new RecordValidator(metadataClient);

    private static FieldSchemaDto field(String name, String type, boolean required,
                                         List<String> enumValues, Map<String, Object> rules) {
        return new FieldSchemaDto(name, name, type, required, false, false, false, false,
                enumValues, rules, null, null);
    }

    private void validate(FieldSchemaDto f, Object value) {
        var schema = new ObjectTypeSchemaDto("TEST_TYPE", List.of(f));
        when(metadataClient.getSchema("TEST_TYPE")).thenReturn(schema);
        validator.validate("TEST_TYPE", value == null ? Map.of() : java.util.Collections.singletonMap(f.name(), value));
    }

    private void assertRejects(FieldSchemaDto f, Object value, String expectedMessageFragment) {
        assertThatThrownBy(() -> validate(f, value))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getFieldErrors())
                        .anyMatch(msg -> msg.contains(f.name()) && msg.contains(expectedMessageFragment)));
    }

    // ---- DATE / DATETIME / TIME ----

    @Test
    void dateAcceptsIsoDate() {
        validate(field("event_date", "DATE", false, null, null), "2026-08-25"); // must not throw
    }

    @Test
    void dateRejectsNonIsoString() {
        assertRejects(field("event_date", "DATE", false, null, null), "25th August", "valid ISO date");
    }

    @Test
    void dateTimeAcceptsOffsetForm() {
        validate(field("start_datetime", "DATETIME", false, null, null), "2026-08-25T10:00:00Z");
    }

    @Test
    void dateTimeAcceptsLocalFormFromDatetimeLocalInput() {
        // events-ui's <input type="datetime-local"> sends this shape, with no offset.
        validate(field("start_datetime", "DATETIME", false, null, null), "2026-08-25T10:00:00");
    }

    @Test
    void dateTimeRejectsGarbage() {
        assertRejects(field("start_datetime", "DATETIME", false, null, null), "not-a-datetime", "valid ISO date-time");
    }

    @Test
    void timeAcceptsIsoTime() {
        validate(field("event_time", "TIME", false, null, null), "18:00:00");
    }

    @Test
    void timeRejectsGarbage() {
        assertRejects(field("event_time", "TIME", false, null, null), "6pm", "valid ISO time");
    }

    // ---- ENTITY_REFERENCE / USER_REFERENCE ----

    @Test
    void entityReferenceAcceptsWellFormedUuid() {
        validate(field("venue_ref", "ENTITY_REFERENCE", false, null, null),
                "550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    void entityReferenceRejectsNonUuidString() {
        // This is the exact case the review flagged: {"venue_ref": "not-a-uuid"} previously
        // persisted silently with zero validation.
        assertRejects(field("venue_ref", "ENTITY_REFERENCE", false, null, null), "not-a-uuid", "valid UUID");
    }

    @Test
    void userReferenceRejectsNonUuidString() {
        assertRejects(field("assignee_ref", "USER_REFERENCE", false, null, null), "12345", "valid UUID");
    }

    // ---- FILE / IMAGE ----

    @Test
    void imageRejectsNonStringValue() {
        assertRejects(field("cover_image", "IMAGE", false, null, null), 12345, "non-empty file/image URL");
    }

    @Test
    void blankValueOnAnOptionalFieldIsAcceptedRatherThanTypeChecked() {
        // validate() skips type validation for a blank value on a non-required field, so
        // validateFileOrImage's own blank branch is unreachable from here: a *required* blank is
        // caught earlier by the required check, and an *optional* blank means "not provided".
        // This previously read as imageRejectsBlankValue and asserted an outcome no input can
        // produce. If blank-but-present should ever become an error, validate()'s early continue
        // is what has to change, not this validator.
        validate(field("cover_image", "IMAGE", false, null, null), "   ");
    }

    @Test
    void fileAcceptsUrlString() {
        validate(field("attachment", "FILE", false, null, null), "https://cdn.example.com/f.pdf");
    }

    // ---- CURRENCY ----

    @Test
    void currencyAcceptsNumber() {
        validate(field("price", "CURRENCY", false, null, null), 1500.0);
    }

    @Test
    void currencyRejectsString() {
        assertRejects(field("price", "CURRENCY", false, null, null), "1500 INR", "must be a number");
    }

    // ---- GEOLOCATION / ADDRESS ----

    @Test
    void geolocationAcceptsObject() {
        validate(field("location", "GEOLOCATION", false, null, null), Map.of("lat", 12.9, "lon", 77.6));
    }

    @Test
    void geolocationRejectsScalar() {
        assertRejects(field("location", "GEOLOCATION", false, null, null), "12.9,77.6", "must be an object");
    }

    @Test
    void addressRejectsScalar() {
        assertRejects(field("billing_address", "ADDRESS", false, null, null), "123 Main St", "must be an object");
    }

    // ---- JSON: deliberately permissive ----

    @Test
    void jsonAcceptsArbitraryStructure() {
        validate(field("metadata", "JSON", false, null, null), Map.of("anything", List.of(1, 2, 3)));
    }

    // ---- Regression: validationRules cast safety (finding: unchecked (Number) cast → 500) ----

    @Test
    void numberFieldWithNonNumericMinRuleDoesNotThrow() {
        var rules = Map.<String, Object>of("min", "10"); // schema author error: string, not number
        // Must not throw ClassCastException — the rule is ignored (and logged), not enforced.
        validate(field("guest_count", "NUMBER", false, null, rules), 5);
    }

    @Test
    void textFieldWithNonNumericMaxLengthRuleDoesNotThrow() {
        var rules = Map.<String, Object>of("maxLength", "20");
        validate(field("nickname", "TEXT", false, null, rules), "some text");
    }

    // ---- Regression: malformed regex safety (finding: PatternSyntaxException → 500) ----

    @Test
    void textFieldWithInvalidRegexPatternFailsCleanlyInsteadOfThrowing() {
        var rules = Map.<String, Object>of("pattern", "[unclosed");
        assertRejects(field("code", "TEXT", false, null, rules), "invalid-input", "misconfigured");
    }

    @Test
    void textFieldWithValidPatternStillEnforcesIt() {
        var rules = Map.<String, Object>of("pattern", "^[A-Z]{3}$");
        assertRejects(field("code", "TEXT", false, null, rules), "abc", "does not match required pattern");
        validate(field("code", "TEXT", false, null, rules), "ABC"); // must not throw
    }

    // ---- Regression: isBlank() must treat empty collections as blank for required fields ----

    @Test
    void requiredMultiSelectRejectsEmptyList() {
        assertRejects(field("amenities", "MULTI_SELECT", true, List.of("WIFI", "BAR"), null),
                List.of(), "field is required");
    }

    @Test
    void requiredArrayOfObjectsRejectsEmptyList() {
        var f = new FieldSchemaDto("wish_list", "wish_list", "ARRAY_OF_OBJECTS", true,
                false, false, false, false, null, null, null,
                List.of(Map.of("name", "item", "type", "TEXT", "required", true)));
        assertRejects(f, List.of(), "field is required");
    }
}
