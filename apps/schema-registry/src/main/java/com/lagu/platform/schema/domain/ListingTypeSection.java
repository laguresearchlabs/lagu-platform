package com.lagu.platform.schema.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "listing_type_section",
       uniqueConstraints = @UniqueConstraint(columnNames = {"listing_type_id", "section_key"}))
@Data
@NoArgsConstructor
public class ListingTypeSection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "listing_type_id", nullable = false)
    private ListingTypeDefinition listingType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "field_group_id", nullable = false)
    private FieldGroup fieldGroup;

    @Column(length = 200)
    private String label;

    @Column(name = "section_key", nullable = false, length = 100)
    private String sectionKey;

    @Column(name = "display_order")
    private int displayOrder = 0;

    @Column(name = "is_collapsible")
    private boolean collapsible = false;

    /** Conditional visibility rule for this section; null = always visible. A hidden section
     *  takes all of its fields with it. Scoped per listing type, unlike a field group entry's. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "visible_when", columnDefinition = "jsonb")
    private Map<String, Object> visibleWhen;
}
