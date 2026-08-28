package com.lagu.platform.vendor.service;

import com.lagu.platform.vendor.client.ListingServiceClient;
import com.lagu.platform.vendor.client.RecordServiceClient;
import com.lagu.platform.vendor.domain.VendorKycChecklistRepository;
import com.lagu.platform.vendor.domain.VendorMember;
import com.lagu.platform.vendor.domain.VendorMemberRepository;
import com.lagu.platform.vendor.domain.VendorProfile;
import com.lagu.platform.vendor.domain.VendorProfileRepository;
import com.lagu.platform.vendor.dto.MembershipOwnerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * booking-service asks this question on every booking transition, so its failure modes matter more
 * than its happy path: the *who* and the *where to email* are resolved from two different sources,
 * and losing the second must not lose the first — otherwise a record-service blip silently
 * downgrades a notification to nothing instead of to in-app only.
 */
class VendorNotificationTargetTest {

    private final VendorProfileRepository profileRepo = mock(VendorProfileRepository.class);
    private final VendorMemberRepository memberRepo = mock(VendorMemberRepository.class);
    private final VendorKycChecklistRepository kycRepo = mock(VendorKycChecklistRepository.class);
    private final RecordServiceClient recordClient = mock(RecordServiceClient.class);
    private final ListingServiceClient listingClient = mock(ListingServiceClient.class);

    private final VendorService service =
            new VendorService(profileRepo, memberRepo, kycRepo, recordClient, listingClient);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID ownerUserId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        VendorProfile profile = new VendorProfile();
        profile.setId(tenantId);
        profile.setRecordId(recordId);
        when(profileRepo.findById(tenantId)).thenReturn(Optional.of(profile));

        VendorMember owner = new VendorMember();
        owner.setTenantId(tenantId);
        owner.setUserId(ownerUserId);
        owner.setRole("OWNER");
        owner.setStatus("ACTIVE");
        when(memberRepo.findFirstByTenantIdAndRoleAndStatusOrderByJoinedAtAsc(tenantId, "OWNER", "ACTIVE"))
                .thenReturn(Optional.of(owner));
    }

    /** record-service's envelope: {data: {id, data: {…fields}}}. */
    private void stubRecordEmail(Object email) {
        when(recordClient.getRecord(recordId, tenantId)).thenReturn(
                Map.of("data", Map.of("id", recordId.toString(), "data", email == null
                        ? Map.of()
                        : Map.of("email", email))));
    }

    @Test
    void resolvesTheOwnerAndTheOrgsContactEmail() {
        stubRecordEmail("bookings@venue.example");

        MembershipOwnerResponse target = service.resolveNotificationTarget(tenantId).orElseThrow();

        assertThat(target.getUserId()).isEqualTo(ownerUserId);
        assertThat(target.getRole()).isEqualTo("OWNER");
        assertThat(target.getContactEmail()).isEqualTo("bookings@venue.example");
    }

    @Test
    void stillResolvesTheOwnerWhenTheEmailFieldIsUnset() {
        // `email` is optional on the VENDOR schema — most drafts will not have it.
        stubRecordEmail(null);

        MembershipOwnerResponse target = service.resolveNotificationTarget(tenantId).orElseThrow();

        assertThat(target.getUserId()).isEqualTo(ownerUserId);
        assertThat(target.getContactEmail()).isNull();
    }

    @Test
    void treatsAWhitespaceOnlyEmailAsUnset() {
        // Otherwise it reaches EmailDeliveryService as a non-null address and is only caught by
        // its own isBlank() check, one layer further from where it can be explained.
        stubRecordEmail("   ");

        assertThat(service.resolveNotificationTarget(tenantId).orElseThrow().getContactEmail()).isNull();
    }

    @Test
    void stillResolvesTheOwnerWhenRecordServiceIsUnreachable() {
        // RecordServiceClient.getRecord swallows to null on failure.
        when(recordClient.getRecord(any(), any())).thenReturn(null);

        MembershipOwnerResponse target = service.resolveNotificationTarget(tenantId).orElseThrow();

        assertThat(target.getUserId()).isEqualTo(ownerUserId);
        assertThat(target.getContactEmail()).isNull();
    }

    @Test
    void stillResolvesTheOwnerWhenTheRecordPayloadIsAnUnexpectedShape() {
        when(recordClient.getRecord(recordId, tenantId)).thenReturn(Map.of("data", "not-a-map"));

        assertThat(service.resolveNotificationTarget(tenantId).orElseThrow().getUserId())
                .isEqualTo(ownerUserId);
    }

    @Test
    void resolvesNothingWhenTheOrgHasNoActiveOwner() {
        when(memberRepo.findFirstByTenantIdAndRoleAndStatusOrderByJoinedAtAsc(tenantId, "OWNER", "ACTIVE"))
                .thenReturn(Optional.empty());

        assertThat(service.resolveNotificationTarget(tenantId)).isEmpty();
    }
}
