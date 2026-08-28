package com.lagu.platform.vendor.service;

import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.vendor.client.ListingServiceClient;
import com.lagu.platform.vendor.client.RecordServiceClient;
import com.lagu.platform.vendor.domain.VendorKycChecklistRepository;
import com.lagu.platform.vendor.domain.VendorMemberRepository;
import com.lagu.platform.vendor.domain.VendorProfile;
import com.lagu.platform.vendor.domain.VendorProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.NoSuchElementException;
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
 * Defect 4 from todo/18-backend-defects.md: an illegal vendor status transition threw an unmapped
 * IllegalStateException, so an admin got 500 "An unexpected error occurred" while the server knew
 * exactly what was wrong and threw the reason away.
 *
 * <p>Two admins working the same vendor hit this routinely, as does a stale browser tab — it is an
 * ordinary business-rule outcome, and the response now says so and says what would be allowed.
 */
class VendorStatusTransitionTest {

    private final VendorProfileRepository profileRepo = mock(VendorProfileRepository.class);
    private final VendorMemberRepository memberRepo = mock(VendorMemberRepository.class);
    private final VendorKycChecklistRepository kycRepo = mock(VendorKycChecklistRepository.class);
    private final RecordServiceClient recordClient = mock(RecordServiceClient.class);
    private final ListingServiceClient listingClient = mock(ListingServiceClient.class);

    private final VendorService service =
            new VendorService(profileRepo, memberRepo, kycRepo, recordClient, listingClient);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    private void vendorIn(String status) {
        VendorProfile profile = new VendorProfile();
        profile.setId(tenantId);
        profile.setStatus(status);
        profile.setOwnerUserId(actorId);
        when(profileRepo.findById(tenantId)).thenReturn(Optional.of(profile));
        when(profileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private PlatformException refusedTransition(String from, String to) {
        vendorIn(from);
        return (PlatformException) org.assertj.core.api.Assertions
                .catchThrowable(() -> service.updateStatus(tenantId, to, actorId));
    }

    @Test
    void anIllegalTransitionIs409NotAServerError() {
        // The exact reproduction from the defect writeup: UNDER_REVIEW on an ACTIVE vendor.
        PlatformException ex = refusedTransition("ACTIVE", "UNDER_REVIEW");

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getCode()).isEqualTo("INVALID_STATUS_TRANSITION");
    }

    @Test
    void theRefusalNamesBothTheCurrentStatusAndWhatIsAllowedFromIt() {
        // So an admin can act on it without going and reading the state machine.
        PlatformException ex = refusedTransition("ACTIVE", "UNDER_REVIEW");

        assertThat(ex.getMessage()).contains("ACTIVE").contains("UNDER_REVIEW").contains("SUSPENDED");
    }

    @Test
    void aTerminalStatusSaysSoRatherThanListingNothing() {
        // No entry in the table at all — "allowed: " with an empty list reads like a bug.
        vendorIn("ARCHIVED");

        assertThatThrownBy(() -> service.updateStatus(tenantId, "ACTIVE", actorId))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("no further transitions");
    }

    @Test
    void anIllegalTransitionDoesNotPersistTheNewStatus() {
        refusedTransition("ACTIVE", "UNDER_REVIEW");
        verify(profileRepo, never()).save(any());
    }

    @Test
    void everyLegalTransitionStillGoesThrough() {
        // Guards against the refusal path over-reaching: the whole documented state machine.
        record Step(String from, String to) {}
        for (Step step : new Step[]{
                new Step("DRAFT", "SUBMITTED"),
                new Step("SUBMITTED", "UNDER_REVIEW"), new Step("SUBMITTED", "DRAFT"),
                new Step("UNDER_REVIEW", "ACTIVE"), new Step("UNDER_REVIEW", "REJECTED"),
                new Step("ACTIVE", "SUSPENDED"),
                new Step("SUSPENDED", "ACTIVE"), new Step("SUSPENDED", "REJECTED"),
                new Step("REJECTED", "DRAFT")}) {
            vendorIn(step.from());
            assertThat(service.updateStatus(tenantId, step.to(), actorId).getStatus())
                    .as("%s -> %s", step.from(), step.to())
                    .isEqualTo(step.to());
        }
    }

    @Test
    void transitionsAreCaseInsensitiveOnTheWayIn() {
        vendorIn("draft");
        assertThat(service.updateStatus(tenantId, "submitted", actorId).getStatus()).isEqualTo("SUBMITTED");
    }

    @Test
    void anUnknownVendorIsStillNotFoundRatherThanATransitionFailure() {
        when(profileRepo.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(tenantId, "SUBMITTED", actorId))
                .isInstanceOf(NoSuchElementException.class);
    }
}
