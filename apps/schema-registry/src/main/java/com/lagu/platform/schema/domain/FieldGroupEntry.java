package com.lagu.platform.schema.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "field_group_entry")
@Data
@NoArgsConstructor
public class FieldGroupEntry {

    @EmbeddedId
    private FieldGroupEntryId id = new FieldGroupEntryId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("fieldGroupId")
    @JoinColumn(name = "field_group_id")
    private FieldGroup fieldGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("fieldId")
    @JoinColumn(name = "field_id")
    private FieldDefinition field;

    @Column(name = "display_order")
    private int displayOrder = 0;

    /**
     * @deprecated superseded by {@link #requiredOverride} (V7). Could only ever ADD requiredness,
     *     because resolution was {@code field.isRequired() || entry.isRequired()} — so unchecking
     *     it on a globally-required field silently did nothing while the admin UI presented it as
     *     an override. Still mapped so older images mid-rollout keep writing a column that exists;
     *     nothing reads it.
     */
    @Deprecated
    @Column(name = "is_required")
    private boolean required = false;

    /**
     * This placement's opinion on whether the field is required, which now wins over the field
     * definition — the "override" semantics the admin UI has always advertised.
     *
     * <p>Boxed on purpose. Three states are needed and a primitive can only carry two:
     * {@code null} means "no opinion, inherit the definition", which is what every untouched entry
     * means. Collapsing null and false would make every globally-required field optional the
     * moment this shipped.
     */
    @Column(name = "required_override")
    private Boolean requiredOverride;

    /** Resolved requiredness for this placement: the override when set, else the definition's. */
    public boolean resolveRequired(boolean fieldDefinitionRequired) {
        return requiredOverride != null ? requiredOverride : fieldDefinitionRequired;
    }

    /**
     * Conditional visibility rule for this field within this group; null = always visible.
     * Because field groups are shared across listing types, the rule applies to every type
     * composing the group — give a type its own field group when it needs to diverge.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visible_when", columnDefinition = "jsonb")
    private Map<String, Object> visibleWhen;

    @Embeddable
    @Data
    @NoArgsConstructor
    public static class FieldGroupEntryId implements java.io.Serializable {
        @Column(name = "field_group_id")
        private java.util.UUID fieldGroupId;

        @Column(name = "field_id")
        private java.util.UUID fieldId;
    }
}
