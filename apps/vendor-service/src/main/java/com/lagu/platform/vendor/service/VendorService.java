package com.lagu.platform.vendor.service;

import com.lagu.platform.vendor.client.IamServiceClient;
import com.lagu.platform.vendor.client.RecordServiceClient;
import com.lagu.platform.vendor.domain.*;
import com.lagu.platform.vendor.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class VendorService {

    private final VendorProfileRepository     profileRepo;
    private final VendorMemberRepository      memberRepo;
    private final VendorKycChecklistRepository kycRepo;
    private final RecordServiceClient         recordClient;
    private final IamServiceClient            iamClient;

    @Transactional
    public VendorProfileResponse register(RegisterVendorRequest req, UUID userId, String bearerToken) {
        if (profileRepo.findByOwnerUserId(userId).isPresent()) {
            throw new IllegalStateException("User already has a vendor profile");
        }

        UUID orgId = UUID.randomUUID();

        // Create the canonical VENDOR record in record-service
        Map<String, Object> recordResponse = recordClient.createRecord(orgId, "VENDOR", Map.of(
            "businessName", req.getBusinessName(),
            "country", req.getCountry()
        ));
        UUID recordId = recordClient.extractRecordId(recordResponse);
        if (recordId == null) {
            throw new IllegalStateException("Failed to create VENDOR record in record-service");
        }

        // Persist local profile
        VendorProfile profile = new VendorProfile();
        profile.setOrgId(orgId);
        profile.setRecordId(recordId);
        profile.setOwnerUserId(userId);
        profile.setBusinessName(req.getBusinessName());
        profile.setCountry(req.getCountry());
        profileRepo.save(profile);

        // Add owner as member
        VendorMember owner = new VendorMember();
        owner.setOrgId(orgId);
        owner.setUserId(userId);
        owner.setRole("OWNER");
        memberRepo.save(owner);

        // Initialise empty KYC checklist
        VendorKycChecklist kyc = new VendorKycChecklist();
        kyc.setOrgId(orgId);
        kyc.setBusinessNameFilled(req.getBusinessName() != null && !req.getBusinessName().isBlank());
        kycRepo.save(kyc);

        // Associate the user with the new org in IAM so the JWT starts carrying this orgId
        iamClient.associateOrgWithUser(userId, orgId, bearerToken);

        log.info("Registered vendor org={} for user={}", orgId, userId);
        return toResponse(profile, null);
    }

    public VendorProfileResponse getMyProfile(UUID userId) {
        VendorProfile profile = profileRepo.findByOwnerUserId(userId)
                .orElseThrow(() -> new NoSuchElementException("No vendor profile for user " + userId));
        VendorKycChecklist kyc = kycRepo.findById(profile.getOrgId()).orElse(null);
        return toResponse(profile, kyc);
    }

    public VendorProfileResponse getByOrgId(UUID orgId) {
        VendorProfile profile = profileRepo.findByOrgId(orgId)
                .orElseThrow(() -> new NoSuchElementException("Vendor not found: " + orgId));
        VendorKycChecklist kyc = kycRepo.findById(orgId).orElse(null);
        return toResponse(profile, kyc);
    }

    public List<VendorProfileResponse> listByStatus(String status) {
        return profileRepo.findByStatus(status.toUpperCase()).stream()
                .map(p -> toResponse(p, null))
                .toList();
    }

    @Transactional
    public VendorProfileResponse updateStatus(UUID orgId, String newStatus, UUID actorId) {
        VendorProfile profile = profileRepo.findByOrgId(orgId)
                .orElseThrow(() -> new NoSuchElementException("Vendor not found: " + orgId));

        validateStatusTransition(profile.getStatus(), newStatus);
        profile.setStatus(newStatus.toUpperCase());
        profileRepo.save(profile);
        log.info("Vendor {} status changed to {} by {}", orgId, newStatus, actorId);
        return toResponse(profile, null);
    }

    @Transactional
    public VendorProfileResponse submit(UUID orgId) {
        return updateStatus(orgId, "SUBMITTED", orgId);
    }

    @Transactional
    public KycChecklistDto computeKyc(UUID orgId) {
        VendorProfile profile = profileRepo.findByOrgId(orgId)
                .orElseThrow(() -> new NoSuchElementException("Vendor not found: " + orgId));

        // Fetch document status from document-service via record-service client
        Map<String, Object> docStatus = recordClient.getDocumentStatus(orgId, profile.getOwnerUserId());

        VendorKycChecklist kyc = kycRepo.findById(orgId).orElseGet(() -> {
            VendorKycChecklist c = new VendorKycChecklist();
            c.setOrgId(orgId);
            return c;
        });

        boolean hasGst = hasVerifiedDoc(docStatus, "GST_CERTIFICATE");
        boolean hasPan = hasVerifiedDoc(docStatus, "PAN_CARD");
        boolean hasBank = hasVerifiedDoc(docStatus, "BANK_CANCELLED_CHEQUE");
        boolean hasId = hasVerifiedDoc(docStatus, "IDENTITY_PROOF");

        kyc.setHasGstDoc(hasGst);
        kyc.setHasPanDoc(hasPan);
        kyc.setHasBankDoc(hasBank);
        kyc.setHasIdentityDoc(hasId);
        kyc.setBusinessNameFilled(profile.getBusinessName() != null);
        kyc.setKycReady(hasPan && hasBank && hasId && kyc.isBusinessNameFilled());
        kyc.setLastComputedAt(Instant.now());
        kycRepo.save(kyc);

        return toKycDto(kyc);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean hasVerifiedDoc(Map<String, Object> docStatus, String code) {
        if (docStatus == null) return false;
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> docs = (List<Map<String, Object>>) docStatus.get("documents");
            if (docs == null) return false;
            return docs.stream().anyMatch(d ->
                    code.equals(d.get("documentType")) && "VERIFIED".equals(d.get("status")));
        } catch (Exception e) {
            return false;
        }
    }

    private void validateStatusTransition(String current, String next) {
        Map<String, Set<String>> allowed = Map.of(
            "DRAFT",        Set.of("SUBMITTED"),
            "SUBMITTED",    Set.of("UNDER_REVIEW", "DRAFT"),
            "UNDER_REVIEW", Set.of("ACTIVE", "REJECTED"),
            "ACTIVE",       Set.of("SUSPENDED"),
            "SUSPENDED",    Set.of("ACTIVE", "REJECTED"),
            "REJECTED",     Set.of("DRAFT")
        );
        if (!allowed.getOrDefault(current.toUpperCase(), Set.of()).contains(next.toUpperCase())) {
            throw new IllegalStateException(
                    "Cannot transition vendor from " + current + " to " + next);
        }
    }

    private VendorProfileResponse toResponse(VendorProfile p, VendorKycChecklist kyc) {
        return VendorProfileResponse.builder()
                .orgId(p.getOrgId())
                .recordId(p.getRecordId())
                .businessName(p.getBusinessName())
                .status(p.getStatus())
                .country(p.getCountry())
                .kycChecklist(kyc != null ? toKycDto(kyc) : null)
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private KycChecklistDto toKycDto(VendorKycChecklist k) {
        return KycChecklistDto.builder()
                .hasGstDoc(k.isHasGstDoc())
                .hasPanDoc(k.isHasPanDoc())
                .hasBankDoc(k.isHasBankDoc())
                .hasIdentityDoc(k.isHasIdentityDoc())
                .businessNameFilled(k.isBusinessNameFilled())
                .addressFilled(k.isAddressFilled())
                .phoneFilled(k.isPhoneFilled())
                .kycReady(k.isKycReady())
                .build();
    }
}
