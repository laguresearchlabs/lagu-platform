package com.lagu.platform.booking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A consumer asking a vendor about a date.
 *
 * @param guestCount how many people the event expects, when it knows. Null is "not stated" rather
 *     than zero, and the two must stay distinct: a vendor reading 0 covers would quote nothing.
 *     Before this field existed the number could only travel in {@code inquiryMessage}, which no
 *     part of the platform could read as a quantity — see V4__booking_guest_count.sql.
 */
public record CreateBookingRequest(
        @NotNull UUID listingRecordId,
        @NotNull LocalDate eventDate,
        UUID eventId,
        @Positive(message = "guestCount must be greater than zero") Integer guestCount,
        String inquiryMessage,
        /**
         * Save this listing to think about instead of asking about it. The booking is created
         * SHORTLISTED, nothing reaches the vendor, and {@code POST /bookings/{id}/inquire} is what
         * sends it later. Null and false are the same thing — an ordinary inquiry.
         */
        Boolean shortlist) {}
