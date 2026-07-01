package com.lagu.platform.schema.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "search_definition")
@Data
@NoArgsConstructor
public class SearchDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "listing_type", nullable = false, unique = true, length = 100)
    private String listingType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "consumer_facets", columnDefinition = "jsonb")
    private List<String> consumerFacets;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "admin_facets", columnDefinition = "jsonb")
    private List<String> adminFacets;

    @Column(name = "default_sort_field", length = 100)
    private String defaultSortField;

    @Column(name = "default_sort_dir", length = 5)
    private String defaultSortDir = "ASC";

    @Column(name = "boost_field", length = 100)
    private String boostField;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = OffsetDateTime.now(); }
    @PreUpdate  void onUpdate() { updatedAt = OffsetDateTime.now(); }
}
