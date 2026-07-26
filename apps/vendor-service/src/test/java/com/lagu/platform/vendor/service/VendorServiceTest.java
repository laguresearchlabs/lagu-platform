package com.lagu.platform.vendor.service;

import com.lagu.platform.vendor.client.RecordServiceClient;
import com.lagu.platform.vendor.domain.VendorKycChecklistRepository;
import com.lagu.platform.vendor.domain.VendorMember;
import com.lagu.platform.vendor.domain.VendorMemberRepository;
import com.lagu.platform.vendor.domain.VendorProfile;
import com.lagu.platform.vendor.domain.VendorProfileRepository;
import com.lagu.platform.vendor.dto.RegisterVendorRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * VendorService no longer trusts a caller's JWT orgId for its own tenancy — every member-facing
 * endpoint (getByOrgId/submit/computeKyc) resolves access from VendorMember instead, so a user
 * who belongs to several vendor orgs can act on each independently of what their JWT happens to
 * carry. These tests pin that boundary, plus that registration never touches IAM anymore.
 */
class VendorServiceTest {

    private final VendorProfileRepository profileRepo = mock(VendorProfileRepository.class);
    private final VendorMemberRepository memberRepo = mock(VendorMemberRepository.class);
    private final VendorKycChecklistRepository kycRepo = mock(VendorKycChecklistRepository.class);
    private final RecordServiceClient recordClient = mock(RecordServiceClient.class);
    private final VendorService service = new VendorService(profileRepo, memberRepo, kycRepo, recordClient);

    private final UUID orgId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        VendorProfile profile = new VendorProfile();
        profile.setOrgId(orgId);
        profile.setOwnerUserId(ownerId);
        profile.setBusinessName("Test Biz");
        profile.setStatus("DRAFT");
        when(profileRepo.findByOrgId(orgId)).thenReturn(Optional.of(profile));
    }

    private VendorMember memberWithRole(UUID userId, String role) {
        VendorMember m = new VendorMember();
        m.setOrgId(orgId);
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }

    @Test
    void registerNeverCallsIamAndReturnsNewOrg() {
        UUID recordId = UUID.randomUUID();
        Map<String, Object> recordResponse = Map.of("data", Map.of("id", recordId.toString()));
        when(recordClient.createRecord(any(), eq(ownerId), eq("VENDOR"), any())).thenReturn(recordResponse);
        when(recordClient.extractRecordId(recordResponse)).thenReturn(recordId);

        RegisterVendorRequest req = new RegisterVendorRequest();
        req.setBusinessName("New Biz");
        req.setCountry("IN");

        var response = service.register(req, ownerId);

        assertThat(response.getBusinessName()).isEqualTo("New Biz");
        verify(memberRepo).save(argThat(m -> m.getUserId().equals(ownerId) && "OWNER".equals(m.getRole())));
        // No RestClient/IAM dependency exists on VendorService at all anymore — nothing to verify
        // a lack of call on; the absence of the field/constructor param is the real assertion.
    }

    @Test
    void getByOrgIdRejectsNonMember() {
        UUID stranger = UUID.randomUUID();
        when(memberRepo.findByOrgIdAndUserId(orgId, stranger)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByOrgId(orgId, stranger))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void getByOrgIdSucceedsForAnyAcceptedRole() {
        when(memberRepo.findByOrgIdAndUserId(orgId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "MEMBER")));

        var response = service.getByOrgId(orgId, ownerId);
        assertThat(response.getBusinessName()).isEqualTo("Test Biz");
    }

    @Test
    void submitRejectsPlainMember() {
        when(memberRepo.findByOrgIdAndUserId(orgId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "MEMBER")));

        assertThatThrownBy(() -> service.submit(orgId, ownerId))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void submitSucceedsForOwner() {
        when(memberRepo.findByOrgIdAndUserId(orgId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "OWNER")));

        var response = service.submit(orgId, ownerId);
        assertThat(response.getStatus()).isEqualTo("SUBMITTED");
    }

    @Test
    void computeKycRejectsNonMember() {
        UUID stranger = UUID.randomUUID();
        when(memberRepo.findByOrgIdAndUserId(orgId, stranger)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.computeKyc(orgId, stranger))
                .isInstanceOf(ResponseStatusException.class);
        verify(recordClient, never()).getDocumentStatus(any(), any());
    }

    @Test
    void listMineReturnsProfilesAcrossMultipleOrgs() {
        UUID otherOrgId = UUID.randomUUID();
        VendorProfile otherProfile = new VendorProfile();
        otherProfile.setOrgId(otherOrgId);
        otherProfile.setOwnerUserId(UUID.randomUUID());
        otherProfile.setBusinessName("Other Biz");
        when(profileRepo.findByOrgId(otherOrgId)).thenReturn(Optional.of(otherProfile));

        VendorMember ownMembership = memberWithRole(ownerId, "OWNER");
        VendorMember otherMembership = new VendorMember();
        otherMembership.setOrgId(otherOrgId);
        otherMembership.setUserId(ownerId);
        otherMembership.setRole("MEMBER");
        when(memberRepo.findByUserId(ownerId)).thenReturn(List.of(ownMembership, otherMembership));

        var results = service.listMine(ownerId);

        assertThat(results).extracting("businessName").containsExactlyInAnyOrder("Test Biz", "Other Biz");
    }
}
