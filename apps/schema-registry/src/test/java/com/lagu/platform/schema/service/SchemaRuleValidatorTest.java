package com.lagu.platform.schema.service;

import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.schema.domain.FieldType;
import com.lagu.platform.schema.dto.ListingTypeSchemaDto;
import com.lagu.platform.schema.dto.ListingTypeSchemaDto.FieldSchemaDto;
import com.lagu.platform.schema.dto.ListingTypeSchemaDto.SectionSchemaDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Publish is the only place cross-field rule problems can be caught, because field groups are
 * shared across listing types: a rule on a shared group may reference a field that one composing
 * type has and another does not. In the type that lacks it the rule would silently evaluate
 * against nothing and hide the section permanently.
 */
class SchemaRuleValidatorTest {

    private static FieldSchemaDto field(String key, Map<String, Object> visibleWhen) {
        return new FieldSchemaDto(key, key, FieldType.TEXT, false, false, false, false, false,
                false, false, null, null, null, visibleWhen);
    }

    private static SectionSchemaDto section(String key, Map<String, Object> visibleWhen,
                                            FieldSchemaDto... fields) {
        return new SectionSchemaDto(key, key, 0, List.of(fields), visibleWhen);
    }

    private static Map<String, Object> whenTruthy(String field) {
        return Map.of("all", List.of(Map.of("field", field, "op", "truthy")));
    }

    private static ListingTypeSchemaDto schema(SectionSchemaDto... sections) {
        return new ListingTypeSchemaDto("WEDDING_EVENT", 1, List.of(sections));
    }

    @Test
    void acceptsARuleReferencingAFieldTheTypeActuallyHas() {
        // Mirrors the seeded event_visibility group: virtual_meeting_url gated on is_virtual.
        var s = schema(section("visibility", null,
                field("is_virtual", null),
                field("virtual_meeting_url", whenTruthy("is_virtual"))));

        assertThatCode(() -> SchemaRuleValidator.validate(s)).doesNotThrowAnyException();
    }

    @Test
    void rejectsARuleReferencingAFieldThisTypeDoesNotHave() {
        // The shared-field-group hazard: a rule authored against WEDDING's venue_type reaching a
        // type that never composes it.
        var s = schema(section("address", null, field("city", whenTruthy("venue_type"))));

        assertThatThrownBy(() -> SchemaRuleValidator.validate(s))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getFieldErrors())
                        .anyMatch(m -> m.contains("unknown field 'venue_type'")));
    }

    @Test
    void rejectsAFieldWhoseVisibilityDependsOnItself() {
        var s = schema(section("basics", null, field("a", whenTruthy("a"))));

        assertThatThrownBy(() -> SchemaRuleValidator.validate(s))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getFieldErrors())
                        .anyMatch(m -> m.contains("depends on itself")));
    }

    @Test
    void rejectsACycleBetweenTwoFields() {
        var s = schema(section("basics", null,
                field("a", whenTruthy("b")),
                field("b", whenTruthy("a"))));

        assertThatThrownBy(() -> SchemaRuleValidator.validate(s))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getFieldErrors())
                        .anyMatch(m -> m.contains("cycle")));
    }

    @Test
    void rejectsAMalformedRule() {
        var s = schema(section("basics", null,
                field("a", Map.of("all", List.of(Map.of("field", "b", "op", "matches")))),
                field("b", null)));

        assertThatThrownBy(() -> SchemaRuleValidator.validate(s))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getFieldErrors())
                        .anyMatch(m -> m.contains("matches")));
    }

    @Test
    void aSectionRuleIsCheckedAndContributesToItsFieldsDependencies() {
        var s = schema(section("outdoor", whenTruthy("missing_field"), field("tent_size", null)));

        assertThatThrownBy(() -> SchemaRuleValidator.validate(s))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).getFieldErrors())
                        .anyMatch(m -> m.contains("section 'outdoor'") && m.contains("missing_field")));
    }

    @Test
    void acceptsASchemaWithNoRulesAtAll() {
        var s = schema(section("basics", null, field("name", null), field("city", null)));
        assertThatCode(() -> SchemaRuleValidator.validate(s)).doesNotThrowAnyException();
    }
}
