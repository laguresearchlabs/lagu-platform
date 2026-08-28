package com.lagu.platform.booking.service;

import com.lagu.platform.booking.domain.Booking;
import com.lagu.platform.booking.domain.BookingRepository;
import com.lagu.platform.booking.domain.BookingStatus;
import com.lagu.platform.booking.domain.SettlementStatus;
import com.lagu.platform.booking.dto.BookingResponse;
import com.lagu.platform.booking.dto.SettlementSummary;
import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The commission ledger. The platform is not in the payment path, so every figure here is a record
 * of something that happened elsewhere — which makes the distinctions between earned, collected,
 * outstanding and waived the whole value of the feature. Collapsing any two of them produces a
 * revenue number that means nothing, so most of these tests are about keeping them apart.
 */
class SettlementServiceTest {

    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final SettlementService service = new SettlementService(bookingRepo);

    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void saveEchoes() {
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Booking booking(SettlementStatus settlement, String commission) {
        Booking b = new Booking();
        b.setId(bookingId);
        b.setConsumerUserId(UUID.randomUUID());
        b.setVendorId(UUID.randomUUID());
        b.setListingRecordId(UUID.randomUUID());
        b.setEventDate(LocalDate.of(2026, 9, 14));
        b.setStatus(BookingStatus.COMPLETED);
        b.setQuotedPrice(new BigDecimal("50000.00"));
        b.setCommissionAmount(new BigDecimal(commission));
        b.setSettlementStatus(settlement);
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.of(b));
        return b;
    }

    // ── invoicing ─────────────────────────────────────────────────────────────

    @Test
    void invoicingADueBookingStampsANumberAndMovesItOn() {
        booking(SettlementStatus.DUE, "7500.00");

        BookingResponse res = service.invoice(bookingId, null, null);

        assertThat(res.settlementStatus()).isEqualTo("INVOICED");
        assertThat(res.invoiceNumber()).isNotBlank();
        assertThat(res.invoicedAt()).isNotNull();
    }

    @Test
    void aGeneratedInvoiceNumberIsSortableByPeriodAndUniquePerBooking() {
        booking(SettlementStatus.DUE, "7500.00");

        String number = service.invoice(bookingId, null, null).invoiceNumber();

        assertThat(number).startsWith("INV-202609-");
        assertThat(number).endsWith(bookingId.toString().substring(0, 8).toUpperCase());
    }

    @Test
    void anAdminSuppliedInvoiceNumberWinsOverTheGeneratedOne() {
        // So the ledger reconciles against whatever system actually issues the document.
        booking(SettlementStatus.DUE, "7500.00");

        assertThat(service.invoice(bookingId, "  ACME-0042  ", null).invoiceNumber())
                .isEqualTo("ACME-0042");
    }

    @Test
    void invoicingSomethingNotYetDueIsRefusedWithWhatIsActuallyTrue() {
        booking(SettlementStatus.NOT_DUE, "7500.00");

        assertThatThrownBy(() -> service.invoice(bookingId, null, null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not due yet")
                .extracting(e -> ((PlatformException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void invoicingTwiceIsRefusedAndNamesTheExistingInvoice() {
        // The double-click case, and the two-admins case. Re-stamping would orphan the first number.
        Booking b = booking(SettlementStatus.INVOICED, "7500.00");
        b.setInvoiceNumber("INV-202609-ABCD1234");

        assertThatThrownBy(() -> service.invoice(bookingId, null, null))
                .hasMessageContaining("INV-202609-ABCD1234");
        verify(bookingRepo, never()).save(any());
    }

    // ── settling ──────────────────────────────────────────────────────────────

    @Test
    void markingAnInvoicedBookingPaidRecordsWhen() {
        booking(SettlementStatus.INVOICED, "7500.00");

        BookingResponse res = service.markPaid(bookingId, "NEFT ref 99");

        assertThat(res.settlementStatus()).isEqualTo("PAID");
        assertThat(res.settledAt()).isNotNull();
        assertThat(res.settlementNote()).isEqualTo("NEFT ref 99");
    }

    @Test
    void payingSomethingNeverInvoicedIsRefusedRatherThanSkippingTheInvoiceTrail() {
        // An admin who genuinely wants this can invoice then settle — two clicks, and the trail
        // survives. Silently allowing it loses the only record that the vendor was ever billed.
        booking(SettlementStatus.DUE, "7500.00");

        assertThatThrownBy(() -> service.markPaid(bookingId, null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("due but not yet invoiced");
    }

    // ── waiving ───────────────────────────────────────────────────────────────

    @Test
    void waivingIsAllowedBeforeOrAfterInvoicing() {
        for (SettlementStatus from : List.of(SettlementStatus.DUE, SettlementStatus.INVOICED)) {
            booking(from, "7500.00");
            assertThat(service.waive(bookingId, "goodwill after a venue double-booking").settlementStatus())
                    .as("waive from %s", from)
                    .isEqualTo("WAIVED");
        }
    }

    @Test
    void waivingWithoutAReasonIsRefused() {
        // A waiver is revenue the platform chose to forgo; unexplained, it is indistinguishable
        // from a mistake when someone reads the ledger a quarter later.
        booking(SettlementStatus.DUE, "7500.00");

        assertThatThrownBy(() -> service.waive(bookingId, "   "))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getCode())
                .isEqualTo("WAIVER_REASON_REQUIRED");
    }

    @Test
    void alreadySettledCommissionCannotBeWaivedAway() {
        booking(SettlementStatus.PAID, "7500.00");

        assertThatThrownBy(() -> service.waive(bookingId, "changed my mind"))
                .hasMessageContaining("already settled");
    }

    @Test
    void anUnknownBookingIsNotFound() {
        when(bookingRepo.findById(bookingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.invoice(bookingId, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── the summary ───────────────────────────────────────────────────────────

    private Booking row(BookingStatus status, SettlementStatus settlement, String price, String commission) {
        Booking b = new Booking();
        b.setId(UUID.randomUUID());
        b.setStatus(status);
        b.setSettlementStatus(settlement);
        b.setQuotedPrice(new BigDecimal(price));
        b.setCommissionAmount(new BigDecimal(commission));
        return b;
    }

    @Test
    void gmvCountsOnlyBookingsThePlatformActuallyBrokered() {
        // An inquiry is not trade and a quote nobody accepted is not revenue. Counting them would
        // make GMV a measure of hope.
        when(bookingRepo.findAll()).thenReturn(List.of(
                row(BookingStatus.INQUIRY,   SettlementStatus.NOT_DUE, "10000", "0"),
                row(BookingStatus.QUOTED,    SettlementStatus.NOT_DUE, "20000", "0"),
                row(BookingStatus.CONFIRMED, SettlementStatus.NOT_DUE, "30000", "0"),
                row(BookingStatus.COMPLETED, SettlementStatus.PAID,    "40000", "6000"),
                row(BookingStatus.CANCELLED, SettlementStatus.NOT_DUE, "50000", "0")));

        assertThat(service.summary().gmv()).isEqualByComparingTo("70000");
    }

    @Test
    void collectedOutstandingAndWaivedNeverOverlap() {
        when(bookingRepo.findAll()).thenReturn(List.of(
                row(BookingStatus.COMPLETED, SettlementStatus.DUE,      "10000", "1500"),
                row(BookingStatus.COMPLETED, SettlementStatus.INVOICED, "10000", "1500"),
                row(BookingStatus.COMPLETED, SettlementStatus.PAID,     "10000", "1500"),
                row(BookingStatus.COMPLETED, SettlementStatus.WAIVED,   "10000", "1500")));

        SettlementSummary s = service.summary();

        assertThat(s.commissionOutstanding()).isEqualByComparingTo("3000");  // DUE + INVOICED
        assertThat(s.commissionCollected()).isEqualByComparingTo("1500");    // PAID only
        assertThat(s.commissionWaived()).isEqualByComparingTo("1500");
        // Earned is what the platform became entitled to, so a write-off is excluded from it —
        // otherwise earned minus collected would silently include revenue nobody is chasing.
        assertThat(s.commissionEarned()).isEqualByComparingTo("4500");
    }

    @Test
    void aWaivedCommissionIsNeitherCollectedNorChaseable() {
        when(bookingRepo.findAll()).thenReturn(List.of(
                row(BookingStatus.COMPLETED, SettlementStatus.WAIVED, "10000", "1500")));

        SettlementSummary s = service.summary();

        assertThat(s.commissionCollected()).isEqualByComparingTo("0");
        assertThat(s.commissionOutstanding()).isEqualByComparingTo("0");
        assertThat(s.commissionWaived()).isEqualByComparingTo("1500");
    }

    @Test
    void nullPricesAndCommissionsDoNotBreakTheTotals() {
        // Every booking before its quote has both null, and they are the majority of rows.
        Booking bare = new Booking();
        bare.setStatus(BookingStatus.INQUIRY);
        bare.setSettlementStatus(SettlementStatus.NOT_DUE);
        when(bookingRepo.findAll()).thenReturn(List.of(bare));

        SettlementSummary s = service.summary();

        assertThat(s.gmv()).isEqualByComparingTo("0");
        assertThat(s.commissionEarned()).isEqualByComparingTo("0");
        assertThat(s.totalBookings()).isEqualTo(1);
    }

    @Test
    void countsEveryFunnelStageSoTheDropOffIsVisible() {
        // The funnel is derived from booking rows rather than browser events: four of the six
        // steps are durable server-side facts, and counting them here cannot be lost to an ad
        // blocker or a closed tab.
        when(bookingRepo.findAll()).thenReturn(List.of(
                row(BookingStatus.INQUIRY,   SettlementStatus.NOT_DUE, "1", "0"),
                row(BookingStatus.INQUIRY,   SettlementStatus.NOT_DUE, "1", "0"),
                row(BookingStatus.QUOTED,    SettlementStatus.NOT_DUE, "1", "0"),
                row(BookingStatus.CONFIRMED, SettlementStatus.NOT_DUE, "1", "0"),
                row(BookingStatus.COMPLETED, SettlementStatus.PAID,    "1", "0"),
                row(BookingStatus.COMPLETED, SettlementStatus.PAID,    "1", "0"),
                row(BookingStatus.CANCELLED, SettlementStatus.NOT_DUE, "1", "0")));

        SettlementSummary s = service.summary();

        assertThat(s.inquiryBookings()).isEqualTo(2);
        assertThat(s.quotedBookings()).isEqualTo(1);
        assertThat(s.confirmedBookings()).isEqualTo(1);
        assertThat(s.completedBookings()).isEqualTo(2);
        assertThat(s.cancelledBookings()).isEqualTo(1);
        assertThat(s.totalBookings()).isEqualTo(7);
    }
}
