package com.lagu.platform.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A vendor's right of reply. One per review — a thread would put the platform in the argument. */
public record VendorReplyRequest(
        @NotBlank(message = "a reply needs words in it")
        @Size(max = 2000, message = "reply is too long") String reply) {}
