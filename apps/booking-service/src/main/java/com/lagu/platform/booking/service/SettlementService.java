package com.lagu.platform.booking.service;

import com.lagu.platform.booking.domain.Booking;
import com.lagu.platform.booking.domain.BookingRepository;
import com.lagu.platform.booking.domain.SettlementStatus;
import com.lagu.platform.booking.dto.BookingResponse;
import com.lagu.platform.booking.dto.SettlementSummary;
import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * The platform's commission ledger, on the offline-settlement model: the customer pays the vendor
 * directly, and the platform bills the vendor for the commission frozen onto the booking at quote
 * time.
 *
 * <p>Everything here is an admin action on the platform's own receivable. None of it moves money —
 * it records that money moved, which is the honest thing for a platform that is not in the payment
 * path to claim. The value is that a completed booking now produces a number somebody can chase,
 * rather than a commission figure sitting inert on a row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SettlementService {

    private final BookingRepository bookingRepo;

    private static final DateTimeFormatter INVOICE_PERIOD =
            DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);

    /**
     * Bills the vendor. DUE → INVOICED, stamping an invoice number.
     *
     * <p>The caller may supply their own number so this can be reconciled against whatever
     * accounting system actually issues the document; otherwise one is generated. Either way it is
     * unique across bookings (enforced by a partial unique index, not just by this code).
     */
    @Transactional
    public BookingResponse invoice(UUID bookingId, String suppliedNumber, String note) {
        Booking booking = require(bookingId);
        requireStatus(booking, SettlementStatus.DUE, "invoiced");

        String number = suppliedNumber != null && !suppliedNumber.isBlank()
                ? suppliedNumber.trim()
                : generateInvoiceNumber(booking);

        booking.setInvoiceNumber(number);
        booking.setInvoicedAt(Instant.now());
        booking.setSettlementStatus(SettlementStatus.INVOICED);
        if (note != null && !note.isBlank()) booking.setSettlementNote(note.trim());

        log.info("Booking {} commission invoiced as {} ({} {})", bookingId, number,
                booking.getCurrency(), booking.getCommissionAmount());
        return BookingResponse.from(bookingRepo.save(booking));
    }

    /**
     * Records that the vendor settled. INVOICED → PAID.
     *
     * <p>Deliberately not reachable from DUE: marking something paid that was never billed loses
     * the invoice trail, and an admin who genuinely wants that can invoice and then settle in two
     * clicks. The refusal says so rather than silently doing half of it.
     */
    @Transactional
    public BookingResponse markPaid(UUID bookingId, String note) {
        Booking booking = require(bookingId);
        requireStatus(booking, SettlementStatus.INVOICED, "marked paid");

        booking.setSettlementStatus(SettlementStatus.PAID);
        booking.setSettledAt(Instant.now());
        if (note != null && !note.isBlank()) booking.setSettlementNote(note.trim());

        log.info("Booking {} commission settled ({} {})", bookingId,
                booking.getCurrency(), booking.getCommissionAmount());
        return BookingResponse.from(bookingRepo.save(booking));
    }

    /**
     * Writes the commission off. Allowed from DUE or INVOICED, because a decision not to collect
     * can be taken before or after billing.
     *
     * <p>A reason is required. A waiver is the platform choosing to forgo revenue, and an
     * unexplained one is indistinguishable from a mistake when someone reads the ledger later.
     */
    @Transactional
    public BookingResponse waive(UUID bookingId, String reason) {
        Booking booking = require(bookingId);
        if (booking.getSettlementStatus() == null || !booking.getSettlementStatus().isOpen()) {
            throw new PlatformException("SETTLEMENT_NOT_OPEN",
                    "This booking's commission is " + describe(booking) + ", so it cannot be waived.",
                    HttpStatus.CONFLICT);
        }
        if (reason == null || reason.isBlank()) {
            throw new PlatformException("WAIVER_REASON_REQUIRED",
                    "Waiving commission needs a reason — it is revenue the platform is choosing "
                            + "not to collect, and an unexplained waiver reads as a mistake later.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        booking.setSettlementStatus(SettlementStatus.WAIVED);
        booking.setSettledAt(Instant.now());
        booking.setSettlementNote(reason.trim());

        log.info("Booking {} commission waived: {}", bookingId, reason.trim());
        return BookingResponse.from(bookingRepo.save(booking));
    }

    /** The admin settlement queue, filtered by where the commission stands. */
    public List<BookingResponse> list(SettlementStatus status) {
        List<Booking> rows = status != null
                ? bookingRepo.findBySettlementStatusOrderByEventDateAsc(status)
                : bookingRepo.findAllByOrderByEventDateDesc();
        return rows.stream().map(BookingResponse::from).toList();
    }

    /**
     * What the business is actually worth: booked value, commission earned, and what of it has
     * been collected. Computed from the rows rather than kept as running totals — the volumes are
     * small, and a derived figure cannot drift from the ledger it describes.
     */
    public SettlementSummary summary() {
        List<Booking> all = bookingRepo.findAll();

        BigDecimal gmv = BigDecimal.ZERO;
        BigDecimal commissionEarned = BigDecimal.ZERO;
        BigDecimal commissionCollected = BigDecimal.ZERO;
        BigDecimal commissionOutstanding = BigDecimal.ZERO;
        BigDecimal commissionWaived = BigDecimal.ZERO;
        long inquiry = 0, quoted = 0, confirmed = 0, completed = 0, cancelled = 0;

        for (Booking b : all) {
            BigDecimal price = b.getQuotedPrice() != null ? b.getQuotedPrice() : BigDecimal.ZERO;
            BigDecimal commission = b.getCommissionAmount() != null ? b.getCommissionAmount() : BigDecimal.ZERO;

            switch (b.getStatus()) {
                case INQUIRY -> inquiry++;
                case QUOTED -> quoted++;
                case CONFIRMED -> confirmed++;
                case COMPLETED -> completed++;
                case CANCELLED -> cancelled++;
                // No default: exhaustive on purpose, so adding a booking status fails to compile
                // here rather than silently going uncounted in the funnel.
            }

            // GMV counts value the platform actually brokered — a booking the customer confirmed.
            // Counting inquiries or quotes would make the number a measure of hope, not trade.
            if (b.getStatus() == com.lagu.platform.booking.domain.BookingStatus.CONFIRMED
                    || b.getStatus() == com.lagu.platform.booking.domain.BookingStatus.COMPLETED) {
                gmv = gmv.add(price);
            }

            SettlementStatus st = b.getSettlementStatus();
            if (st == null) continue;
            switch (st) {
                case DUE, INVOICED -> {
                    commissionEarned = commissionEarned.add(commission);
                    commissionOutstanding = commissionOutstanding.add(commission);
                }
                case PAID -> {
                    commissionEarned = commissionEarned.add(commission);
                    commissionCollected = commissionCollected.add(commission);
                }
                // Waived commission is earned-then-forgone: counted as neither collected nor
                // outstanding, and kept visible so writing revenue off is not invisible.
                case WAIVED -> commissionWaived = commissionWaived.add(commission);
                case NOT_DUE -> { }
            }
        }

        return new SettlementSummary(all.size(), inquiry, quoted, confirmed, completed, cancelled,
                gmv, commissionEarned, commissionCollected, commissionOutstanding, commissionWaived);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Booking require(UUID id) {
        return bookingRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", id.toString()));
    }

    private void requireStatus(Booking booking, SettlementStatus expected, String action) {
        if (booking.getSettlementStatus() != expected) {
            throw new PlatformException("SETTLEMENT_NOT_" + expected.name(),
                    "This booking's commission is " + describe(booking)
                            + ", so it cannot be " + action + ".",
                    HttpStatus.CONFLICT);
        }
    }

    private static String describe(Booking booking) {
        return switch (booking.getSettlementStatus()) {
            case NOT_DUE -> "not due yet (the booking has not completed)";
            case DUE -> "due but not yet invoiced";
            case INVOICED -> "already invoiced as " + booking.getInvoiceNumber();
            case PAID -> "already settled";
            case WAIVED -> "already waived";
        };
    }

    /** {@code INV-YYYYMM-<first 8 of the booking id>} — sortable by period, unique by booking. */
    private static String generateInvoiceNumber(Booking booking) {
        LocalDate basis = booking.getEventDate() != null ? booking.getEventDate() : LocalDate.now();
        return "INV-" + INVOICE_PERIOD.format(basis.atStartOfDay(ZoneOffset.UTC).toInstant())
                + "-" + booking.getId().toString().substring(0, 8).toUpperCase();
    }
}
