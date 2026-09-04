package com.lagu.platform.booking.domain;

public enum BookingStatus {
    /**
     * A consumer is considering this listing and the vendor has not been told.
     *
     * <p>Choosing a venue is a compare-three-then-decide task, and there was nowhere to keep the
     * three — a shopper did it in browser tabs, off the platform. A shortlist is a booking that
     * has not been sent, which costs no new store and puts the candidates on the event's own
     * Vendors tab beside the inquiries.
     *
     * <p><b>It is invisible to the vendor and always must be.</b> Nothing is published to the
     * outbox when one is created, {@code listVendor} filters it out, and removing one deletes the
     * row rather than cancelling it — a vendor must never be notified that someone considered
     * them and changed their mind, nor find a CANCELLED booking for a conversation that never
     * happened. Anything added here that reads a booking on the vendor side has to honour that.
     */
    SHORTLISTED,
    INQUIRY,
    QUOTED,
    CONFIRMED,
    COMPLETED,
    CANCELLED
}
