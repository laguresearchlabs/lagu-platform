package com.lagu.platform.metadata.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "entity_attribute")
@Data
@NoArgsConstructor
public class EntityAttribute {

    @EmbeddedId
    private EntityAttributeId id = new EntityAttributeId();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("entityId")
    @JoinColumn(name = "entity_id")
    private EntityDefinition entity;

    @ManyToOne(fetch = FetchType.EAGER)
    @MapsId("attributeId")
    @JoinColumn(name = "attribute_id")
    private AttributeDefinition attribute;

    @Column(name = "display_order")
    private int displayOrder = 0;

    @Column(name = "is_required")
    private boolean required;

    @Embeddable
    @Data
    @NoArgsConstructor
    public static class EntityAttributeId implements java.io.Serializable {
        @Column(name = "entity_id")
        private UUID entityId;

        @Column(name = "attribute_id")
        private UUID attributeId;
    }
}
