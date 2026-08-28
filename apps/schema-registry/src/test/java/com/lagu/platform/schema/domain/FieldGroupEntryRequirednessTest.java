package com.lagu.platform.schema.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defect 7: a field-group entry could only ever ADD requiredness. Resolution was
 * {@code field.isRequired() || entry.isRequired()}, so unchecking "required" on a placement did
 * nothing when the field definition said required — while the admin UI presented that control as
 * an override. It is also what made the first attempt at defect 2 ineffective.
 *
 * <p>The fix needs three states, not two, and that is the whole subtlety. A primitive boolean
 * cannot tell "this placement says optional" from "this placement has no opinion", and every
 * untouched entry in the database sits at false. Reading those as an explicit relaxation would
 * un-require every globally-required field on the platform in a single deploy.
 */
class FieldGroupEntryRequirednessTest {

    private FieldGroupEntry entry(Boolean override) {
        FieldGroupEntry e = new FieldGroupEntry();
        e.setRequiredOverride(override);
        return e;
    }

    @Test
    void noOpinionInheritsTheFieldDefinition() {
        // The default, and what V7 backfills every untouched row to. Both directions must pass
        // through unchanged — this is the case that keeps the migration safe.
        assertThat(entry(null).resolveRequired(true)).isTrue();
        assertThat(entry(null).resolveRequired(false)).isFalse();
    }

    @Test
    void anEntryCanRelaxAGloballyRequiredField() {
        // The capability that did not exist. The same field is genuinely required for one listing
        // type and optional for another, which is the whole reason the control is offered.
        assertThat(entry(false).resolveRequired(true)).isFalse();
    }

    @Test
    void anEntryCanStillRequireAnOptionalField() {
        // The one thing the old boolean could express, which must keep working.
        assertThat(entry(true).resolveRequired(false)).isTrue();
    }

    @Test
    void anEntryAgreeingWithTheDefinitionChangesNothing() {
        assertThat(entry(true).resolveRequired(true)).isTrue();
        assertThat(entry(false).resolveRequired(false)).isFalse();
    }

    @Test
    void aFreshEntryHasNoOpinionRatherThanSayingOptional() {
        // If the field defaulted to FALSE instead of null, creating an entry for a required field
        // would quietly make it optional — the exact regression this design exists to avoid.
        assertThat(new FieldGroupEntry().getRequiredOverride()).isNull();
        assertThat(new FieldGroupEntry().resolveRequired(true)).isTrue();
    }
}
