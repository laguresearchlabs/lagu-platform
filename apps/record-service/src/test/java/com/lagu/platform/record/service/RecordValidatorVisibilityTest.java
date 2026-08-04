package com.lagu.platform.record.service;

import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.record.client.MetadataClient;
import com.lagu.platform.record.client.MetadataClient.FieldSchemaDto;
import com.lagu.platform.record.client.MetadataClient.ObjectTypeSchemaDto;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The required check runs before anything else, so a required field hidden by a visibility rule
 * would make a record permanently unsubmittable — the user is never shown the field, so can never
 * satisfy it. These tests pin that conditional fields are enforced only while visible.
 *
 * <p>The rule reaching RecordValidator is the *effective* one: MetadataClient has already ANDed
 * each section's rule into its fields, because it flattens sections away.
 */
class RecordValidatorVisibilityTest {

    private final MetadataClient metadataClient = mock(MetadataClient.class);
    private final RecordValidator validator = new RecordValidator(metadataClient);

    private static FieldSchemaDto field(String name, String type, boolean required,
                                        Map<String, Object> visibleWhen) {
        return new FieldSchemaDto(name, name, type, required,
                false, false, false, false, null, null, null, null, visibleWhen);
    }

    private static Map<String, Object> whenEquals(String field, Object value) {
        Map<String, Object> cond = new HashMap<>();
        cond.put("field", field);
        cond.put("op", "eq");
        cond.put("value", value);
        return Map.of("all", List.of(cond));
    }

    private void stub(FieldSchemaDto... fields) {
        when(metadataClient.getSchema("WEDDING_EVENT"))
                .thenReturn(new ObjectTypeSchemaDto("WEDDING_EVENT", 3, List.of(fields)));
    }

    @Test
    void aRequiredFieldIsNotEnforcedWhileItsRuleIsFalse() {
        stub(field("venue_type", "TEXT", false, null),
             field("rain_plan", "TEXT", true, whenEquals("venue_type", "OUTDOOR")));

        assertThatCode(() -> validator.validate("WEDDING_EVENT", Map.of("venue_type", "INDOOR")))
                .doesNotThrowAnyException();
    }

    @Test
    void theSameFieldIsEnforcedOnceItsRuleIsTrue() {
        stub(field("venue_type", "TEXT", false, null),
             field("rain_plan", "TEXT", true, whenEquals("venue_type", "OUTDOOR")));

        assertThatThrownBy(() -> validator.validate("WEDDING_EVENT", Map.of("venue_type", "OUTDOOR")))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getFieldErrors())
                        .anyMatch(msg -> msg.contains("rain_plan") && msg.contains("required")));
    }

    @Test
    void aVisibleConditionalFieldStillPassesWhenSupplied() {
        stub(field("venue_type", "TEXT", false, null),
             field("rain_plan", "TEXT", true, whenEquals("venue_type", "OUTDOOR")));

        assertThatCode(() -> validator.validate("WEDDING_EVENT",
                Map.of("venue_type", "OUTDOOR", "rain_plan", "Marquee")))
                .doesNotThrowAnyException();
    }

    @Test
    void aHiddenFieldIsNotTypeCheckedEither() {
        // A stale client could send a value for a field that is currently hidden. Rejecting it
        // would race with schema changes, so the value is ignored rather than validated.
        stub(field("venue_type", "TEXT", false, null),
             field("tent_count", "NUMBER", false, whenEquals("venue_type", "OUTDOOR")));

        assertThatCode(() -> validator.validate("WEDDING_EVENT",
                Map.of("venue_type", "INDOOR", "tent_count", "not-a-number")))
                .doesNotThrowAnyException();
    }

    @Test
    void stripHiddenFieldsDropsValuesForFieldsNobodyCanSee() {
        // The client already strips these, but a stale or non-web client would not — and a stored
        // value for a hidden field reappears if the rule later flips back.
        stub(field("is_virtual", "BOOLEAN", false, null),
             field("virtual_meeting_url", "TEXT", false, whenEquals("is_virtual", true)));

        var cleaned = validator.stripHiddenFields("WEDDING_EVENT",
                Map.of("is_virtual", false, "virtual_meeting_url", "https://zoom.example/abc"));

        assertThat(cleaned).containsOnlyKeys("is_virtual");
    }

    @Test
    void stripHiddenFieldsKeepsValuesForVisibleFields() {
        stub(field("is_virtual", "BOOLEAN", false, null),
             field("virtual_meeting_url", "TEXT", false, whenEquals("is_virtual", true)));

        var cleaned = validator.stripHiddenFields("WEDDING_EVENT",
                Map.of("is_virtual", true, "virtual_meeting_url", "https://zoom.example/abc"));

        assertThat(cleaned).containsEntry("virtual_meeting_url", "https://zoom.example/abc");
    }

    @Test
    void stripHiddenFieldsLeavesUnknownKeysAlone() {
        // Unknown-field handling is a separate concern — RecordValidator logs and stores them.
        stub(field("name", "TEXT", false, null));

        var cleaned = validator.stripHiddenFields("WEDDING_EVENT",
                Map.of("name", "Our Wedding", "legacy_key", "x"));

        assertThat(cleaned).containsEntry("legacy_key", "x");
    }

    @Test
    void unconditionalFieldsAreUnaffected() {
        stub(field("name", "TEXT", true, null));

        assertThatThrownBy(() -> validator.validate("WEDDING_EVENT", Map.of()))
                .isInstanceOf(ValidationException.class);
        assertThatCode(() -> validator.validate("WEDDING_EVENT", Map.of("name", "Our Wedding")))
                .doesNotThrowAnyException();
    }
}
