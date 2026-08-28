package com.lagu.platform.booking.domain;

/**
 * Where a booking's commission stands, on the offline-settlement model: the customer pays the
 * vendor directly and the platform bills the vendor for the commission frozen at quote time.
 *
 * <p>This tracks the platform's own receivable. It deliberately says nothing about whether the
 * customer paid the vendor — the platform is not party to that transaction and recording a
 * guess about it would be worse than recording nothing.
 */
public enum SettlementStatus {

    /** Nothing owed yet — the booking has not completed, or was cancelled before it could. */
    NOT_DUE,

    /** Completed with commission owed, not yet billed. This is the admin's work queue. */
    DUE,

    /** The vendor has been billed and the invoice number is on the booking. */
    INVOICED,

    /** The vendor settled. The only status that counts as collected revenue. */
    PAID,

    /**
     * Written off — a goodwill gesture, a disputed job, an uncollectable amount. Kept distinct
     * from PAID rather than folded into it, so "collected" and "given up on" never add together
     * in a revenue figure.
     */
    WAIVED;

    /** Whether an admin can still act on this booking's commission. */
    public boolean isOpen() {
        return this == DUE || this == INVOICED;
    }
}
