package com.lagu.platform.booking.dto;

import java.math.BigDecimal;

/**
 * What the marketplace is worth, for the admin console.
 *
 * <p>Three commission figures rather than one, because they answer different questions and folding
 * them together is how a revenue number stops meaning anything:
 *
 * <ul>
 *   <li>{@code commissionEarned} — everything the platform became entitled to.</li>
 *   <li>{@code commissionCollected} — what a vendor actually paid. The only one that is money.</li>
 *   <li>{@code commissionOutstanding} — earned, billed or billable, not yet in.</li>
 * </ul>
 *
 * <p>{@code commissionWaived} sits outside all three: written off, so neither collected nor
 * chaseable, but visible so that forgoing revenue is a decision somebody can see rather than a
 * silent gap between earned and collected.
 *
 * <p>The per-status counts are also the marketplace funnel — inquiry → quoted → confirmed →
 * completed — derived from the booking rows rather than instrumented in a browser. Four of the six
 * funnel steps are durable server-side facts, so counting them here is both free and more reliable
 * than a client event an ad blocker or a closed tab can lose. Only "listing viewed" and "quote
 * viewed" genuinely need client instrumentation.
 *
 * @param gmv value of bookings the platform actually brokered — confirmed and completed only.
 *            Inquiries and unaccepted quotes are excluded; counting them would measure hope.
 */
public record SettlementSummary(
        long totalBookings,
        /** Raised but not yet priced — the step where the loop used to die silently. */
        long inquiryBookings,
        long quotedBookings,
        long confirmedBookings,
        long completedBookings,
        long cancelledBookings,
        BigDecimal gmv,
        BigDecimal commissionEarned,
        BigDecimal commissionCollected,
        BigDecimal commissionOutstanding,
        BigDecimal commissionWaived) {
}
