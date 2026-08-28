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

    /**
     * Atomically claims one day for {@code bookingRef}, returning 1 if the claim was taken and 0
     * if the day was already spoken for.
     *
     * <p><b>A day with no row is claimable.</b> This replaced a conditional UPDATE
     * (`SET slot_type='BOOKED' WHERE slot_type='AVAILABLE'`) which matched nothing when the
     * listing had no availability rows at all — and since nothing in any frontend has ever called
     * `PUT /api/v1/listings/{{recordId}}/availability`, that was every listing on the platform.
     * Every {@code POST /bookings/{{id}}/confirm} failed with SLOT_UNAVAILABLE as a result.
     *
     * <p>Absence now means "nobody has blocked this", which is the correct default here because
     * the availability calendar is not the safety gate — the quote is. A consumer can only confirm
     * a date the vendor has already priced <em>for that exact date</em>, so an open-by-default
     * calendar cannot commit a vendor to anything they did not agree to. The calendar's job is to
     * stop inquiries landing on days the vendor knows are gone, which is opt-in by nature.
     *
     * <p>Rows that already exist keep their meaning exactly: AVAILABLE is claimable, BLOCKED and
     * BOOKED are not. Only the no-row case changes, and its previous behaviour was a guaranteed
     * failure — so nothing that worked before behaves differently now.
     *
     * <p>Race safety comes from the {@code (record_id, slot_date)} unique constraint rather than
     * from the WHERE clause: concurrent claimants collide on the index, the loser's
     * ON CONFLICT branch re-reads the winner's committed row as BOOKED, its WHERE fails, and it
     * affects zero rows. One winner, no lost update, no phantom insert.
     */
    @Modifying
    @Query(value = """
            INSERT INTO listing_availability
                   (id, record_id, tenant_id, slot_date, slot_type, booking_ref, created_at, updated_at)
            VALUES (gen_random_uuid(), :recordId, :tenantId, :date, 'BOOKED', :bookingRef, now(), now())
            ON CONFLICT (record_id, slot_date) DO UPDATE
               SET slot_type   = 'BOOKED',
                   booking_ref = EXCLUDED.booking_ref,
                   updated_at  = now()
             WHERE listing_availability.slot_type = 'AVAILABLE'
            """, nativeQuery = true)
    int claimSlot(@Param("recordId") UUID recordId, @Param("tenantId") UUID tenantId,
                  @Param("date") LocalDate date, @Param("bookingRef") UUID bookingRef);

    @Modifying
    @Query("UPDATE ListingAvailability a SET a.slotType = 'AVAILABLE', a.bookingRef = null " +
           "WHERE a.recordId = :recordId AND a.slotDate = :date " +
           "AND a.slotType = 'BOOKED' AND a.bookingRef = :bookingRef")
    int releaseBooked(@Param("recordId") UUID recordId, @Param("date") LocalDate date,
                      @Param("bookingRef") UUID bookingRef);
}
