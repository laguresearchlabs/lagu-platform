package com.lagu.platform.metadata.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "object_type_section")
@Data
@NoArgsConstructor
public class ObjectTypeSection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "object_type_id", nullable = false)
    private ObjectTypeDefinition objectType;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "entity_id", nullable = false)
    private EntityDefinition entity;

    @Column(length = 200)
    private String label;

    @Column(name = "display_order")
    private int displayOrder = 0;

    @Column(name = "is_collapsible")
    private boolean collapsible = false;
}
