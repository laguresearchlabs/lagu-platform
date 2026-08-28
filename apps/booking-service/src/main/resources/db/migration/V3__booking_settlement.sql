-- Offline settlement.
--
-- The platform does not take the money: the customer pays the vendor directly. What it does do is
-- record that the job completed and invoice the vendor for the commission that was frozen onto the
-- booking at quote time. These columns are that ledger — the difference between a commission
-- number nobody can act on and one that gets collected.
--
-- Deliberately not a separate `invoice` table. One booking produces at most one commission line,
-- the amount is already on the row, and a join table would buy nothing but a join. If commission
-- ever becomes divisible (partial refunds, split payouts) this needs revisiting.

ALTER TABLE booking
    -- NOT_DUE   -> nothing owed yet; the booking has not completed
    -- DUE       -> completed with commission owed, not yet invoiced
    -- INVOICED  -> the vendor has been billed
    -- PAID      -> the vendor settled the commission
    -- WAIVED    -> written off; kept distinct from PAID so it never counts as revenue
    ADD COLUMN settlement_status  VARCHAR(20)  NOT NULL DEFAULT 'NOT_DUE',
    ADD COLUMN invoice_number     VARCHAR(40),
    ADD COLUMN invoiced_at        TIMESTAMPTZ,
    ADD COLUMN settled_at         TIMESTAMPTZ,
    ADD COLUMN settlement_note    TEXT;

-- One invoice number per booking, and no two bookings sharing one. Partial so the many rows with
-- no invoice yet do not all collide on NULL.
CREATE UNIQUE INDEX IF NOT EXISTS uq_booking_invoice_number
    ON booking (invoice_number) WHERE invoice_number IS NOT NULL;

-- The admin settlement queue reads "everything owed, oldest first"; without this it is a full scan
-- that grows with every completed booking.
CREATE INDEX IF NOT EXISTS idx_booking_settlement
    ON booking (settlement_status, event_date);

-- Bookings that already completed before this migration are owed commission just as much as new
-- ones; leaving them NOT_DUE would silently write off whatever revenue is already on the books.
-- Zero-commission completions go straight to PAID rather than DUE — there is nothing to collect,
-- and leaving them DUE would clog the queue with lines nobody can action.
UPDATE booking
   SET settlement_status = CASE
           WHEN COALESCE(commission_amount, 0) > 0 THEN 'DUE'
           ELSE 'PAID'
       END
 WHERE status = 'COMPLETED';
