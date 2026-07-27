package com.lagu.platform.booking.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record QuoteBookingRequest(
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal price,
        String currency,
        String quoteNote) {}
