package com.lagu.platform.schema.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @Column(name = "is_required")
    private boolean required = false;

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
