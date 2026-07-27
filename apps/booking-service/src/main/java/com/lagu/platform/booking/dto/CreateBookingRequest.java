package com.lagu.platform.booking.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record CreateBookingRequest(
        @NotNull UUID listingRecordId,
        @NotNull LocalDate eventDate,
        UUID eventId,
        String inquiryMessage) {}
