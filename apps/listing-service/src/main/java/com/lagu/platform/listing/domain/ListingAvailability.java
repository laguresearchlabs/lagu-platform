package com.lagu.platform.listing.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "listing_availability",
       uniqueConstraints = @UniqueConstraint(columnNames = {"record_id", "slot_date"}))
@Data
public class ListingAvailability {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "record_id", nullable = false)
    private UUID recordId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "slot_date", nullable = false)
    private LocalDate slotDate;

    @Column(name = "slot_type", nullable = false, length = 20)
    private String slotType = "AVAILABLE"; // AVAILABLE | BLOCKED | BOOKED

    @Column(name = "booking_ref")
    private UUID bookingRef;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
