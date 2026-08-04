package com.lagu.platform.schema.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.schema.domain.FieldType;
import com.lagu.platform.schema.dto.ListingTypeSchemaDto;
import com.lagu.platform.schema.dto.ListingTypeSchemaDto.FieldSchemaDto;
import com.lagu.platform.schema.dto.ListingTypeSchemaDto.SectionSchemaDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classification recorded at publish is what tells anyone downstream whether existing records
 * still validate, so getting it wrong is worse than not having it — a HARD_BREAKING change
 * recorded as SAFE is an invitation to ignore it.
 *
 * <p>Previous snapshots are built by serialising a real DTO through Jackson, exactly as
 * {@code SchemaVersionService.publish} does. Hand-written maps would test a shape production never
 * stores, and {@code flattenSnapshot} reads that loosely-typed jsonb back by hand.
 */
class SchemaChangeClassifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static FieldSchemaDto field(String key, FieldType type, boolean required, List<String> enumValues) {
        return new FieldSchemaDto(key, key, type, required, false, false, false, false,
                false, false, enumValues, null, null, null);
    }

    private static FieldSchemaDto text(String key, boolean required) {
        return field(key, FieldType.TEXT, required, null);
    }

    private static ListingTypeSchemaDto schema(FieldSchemaDto... fields) {
        return new ListingTypeSchemaDto("WEDDING_EVENT", 1,
                List.of(new SectionSchemaDto("s", "S", 0, List.of(fields), null)));
    }

    /** Round-trips through Jackson the way publish stores it. */
    private static Map<String, Object> snapshotOf(ListingTypeSchemaDto dto) {
        return MAPPER.convertValue(dto, new TypeReference<>() {});
    }

    @Test
    void theFirstPublishIsSafeBecauseNothingExistsToBreak() {
        var result = SchemaChangeClassifier.classify(null, schema(text("name", true)));

        assertThat(result.classification()).isEqualTo(SchemaChangeClassifier.SAFE);
        assertThat(result.reasons()).containsExactly("initial publish");
    }

    @Test
    void anEmptySnapshotIsTreatedAsAFirstPublish() {
        var result = SchemaChangeClassifier.classify(Map.of(), schema(text("name", true)));
        assertThat(result.classification()).isEqualTo(SchemaChangeClassifier.SAFE);
    }

    @Test
    void republishingAnUnchangedSchemaIsSafeWithNoReasons() {
        var before = schema(text("name", true), text("notes", false));

        var result = SchemaChangeClassifier.classify(snapshotOf(before), before);

        assertThat(result.classification()).isEqualTo(SchemaChangeClassifier.SAFE);
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void addingAnOptionalFieldIsSafe() {
        var before = snapshotOf(schema(text("name", true)));
        var after = schema(text("name", true), text("notes", false));

        assertThat(SchemaChangeClassifier.classify(before, after).classification())
                .isEqualTo(SchemaChangeClassifier.SAFE);
    }

    @Test
    void addingARequiredFieldIsSoftBreaking() {
        // Existing records omit it, so they stop validating on their next write — reads still work.
        var before = snapshotOf(schema(text("name", true)));
        var after = schema(text("name", true), text("venue", true));

        var result = SchemaChangeClassifier.classify(before, after);

        assertThat(result.classification()).isEqualTo(SchemaChangeClassifier.SOFT_BREAKING);
        assertThat(result.reasons()).anyMatch(r -> r.contains("new required field 'venue'"));
    }

    @Test
    void makingAnExistingFieldRequiredIsSoftBreaking() {
        var before = snapshotOf(schema(text("name", true), text("notes", false)));
        var after = schema(text("name", true), text("notes", true));

        var result = SchemaChangeClassifier.classify(before, after);

        assertThat(result.classification()).isEqualTo(SchemaChangeClassifier.SOFT_BREAKING);
        assertThat(result.reasons()).anyMatch(r -> r.contains("'notes' became required"));
    }

    @Test
    void removingAFieldIsHardBreaking() {
        var before = snapshotOf(schema(text("name", true), text("notes", false)));
        var after = schema(text("name", true));

        var result = SchemaChangeClassifier.classify(before, after);

        assertThat(result.classification()).isEqualTo(SchemaChangeClassifier.HARD_BREAKING);
        assertThat(result.reasons()).anyMatch(r -> r.contains("'notes' was removed"));
    }

    @Test
    void changingAFieldsTypeIsHardBreaking() {
        var before = snapshotOf(schema(text("guests", false)));
        var after = schema(field("guests", FieldType.NUMBER, false, null));

        var result = SchemaChangeClassifier.classify(before, after);

        assertThat(result.classification()).isEqualTo(SchemaChangeClassifier.HARD_BREAKING);
        assertThat(result.reasons()).anyMatch(r -> r.contains("TEXT") && r.contains("NUMBER"));
    }

    @Test
    void droppingAnEnumValueIsHardBreaking() {
        // Records already holding the dropped value stop validating.
        var before = snapshotOf(schema(field("visibility", FieldType.ENUM, false,
                List.of("PRIVATE", "UNLISTED", "PUBLIC"))));
        var after = schema(field("visibility", FieldType.ENUM, false, List.of("PRIVATE", "PUBLIC")));

        var result = SchemaChangeClassifier.classify(before, after);

        assertThat(result.classification()).isEqualTo(SchemaChangeClassifier.HARD_BREAKING);
        assertThat(result.reasons()).anyMatch(r -> r.contains("UNLISTED"));
    }

    @Test
    void addingAnEnumValueIsSafe() {
        var before = snapshotOf(schema(field("visibility", FieldType.ENUM, false, List.of("PRIVATE"))));
        var after = schema(field("visibility", FieldType.ENUM, false, List.of("PRIVATE", "PUBLIC")));

        assertThat(SchemaChangeClassifier.classify(before, after).classification())
                .isEqualTo(SchemaChangeClassifier.SAFE);
    }

    @Test
    void hardBreakingWinsWhenBothKindsOfChangeArePresent() {
        // Reporting SOFT here would understate the impact of the removal.
        var before = snapshotOf(schema(text("name", true), text("notes", false)));
        var after = schema(text("name", true), text("venue", true));

        var result = SchemaChangeClassifier.classify(before, after);

        assertThat(result.classification()).isEqualTo(SchemaChangeClassifier.HARD_BREAKING);
        assertThat(result.reasons()).anyMatch(r -> r.contains("'notes' was removed"));
        assertThat(result.reasons()).noneMatch(r -> r.contains("venue"));
    }

    @Test
    void aSnapshotMissingNewerKeysStillClassifies() {
        // An old snapshot predates fields like visibleWhen; reading it must degrade, not throw.
        Map<String, Object> legacy = Map.of(
                "listingType", "WEDDING_EVENT",
                "version", 1,
                "sections", List.of(Map.of(
                        "sectionKey", "s",
                        "fields", List.of(Map.of("key", "name", "fieldType", "TEXT", "required", true)))));

        var result = SchemaChangeClassifier.classify(legacy, schema(text("name", true)));

        assertThat(result.classification()).isEqualTo(SchemaChangeClassifier.SAFE);
    }

    @Test
    void aStructurallyUnreadableSnapshotOverReportsRatherThanThrowing() {
        // A malformed historical row must not make publish fail. It flattens to nothing, so every
        // required field looks new and the result is SOFT_BREAKING — an overstatement, which is
        // the safe direction: a spurious warning beats a silent SAFE on a real break.
        var result = SchemaChangeClassifier.classify(Map.of("sections", "not-a-list"),
                schema(text("name", true)));

        assertThat(result.classification()).isEqualTo(SchemaChangeClassifier.SOFT_BREAKING);
    }
}
