package com.lagu.platform.listing.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListingAvailabilityRepository extends JpaRepository<ListingAvailability, UUID> {

    List<ListingAvailability> findByRecordIdAndSlotDateBetween(UUID recordId,
                                                                LocalDate from, LocalDate to);

    Optional<ListingAvailability> findByRecordIdAndSlotDate(UUID recordId, LocalDate date);

    List<ListingAvailability> findByRecordIdAndSlotDateBetweenAndSlotType(UUID recordId,
                                                                           LocalDate from, LocalDate to,
                                                                           String slotType);

    @Modifying
    @Query("UPDATE ListingAvailability a SET a.slotType = 'BOOKED', a.bookingRef = :bookingRef " +
           "WHERE a.recordId = :recordId AND a.slotDate = :date AND a.slotType = 'AVAILABLE'")
    int markBooked(@Param("recordId") UUID recordId, @Param("date") LocalDate date,
                   @Param("bookingRef") UUID bookingRef);
}
