package com.lagu.platform.vendor.service;

import com.lagu.platform.vendor.client.RecordServiceClient;
import com.lagu.platform.vendor.domain.*;
import com.lagu.platform.vendor.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    @Transactional
    public VendorProfileResponse register(RegisterVendorRequest req, UUID userId) {
        UUID orgId = UUID.randomUUID();

        // Create the canonical VENDOR record in record-service
        Map<String, Object> recordResponse = recordClient.createRecord(orgId, userId, "VENDOR", Map.of(
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

        // Deliberately NOT associated with the user's IAM platformOrgId: a user can be a
        // VendorMember of many vendor orgs at once (see VendorMember's org_id+user_id unique
        // pair), but IAM's platformOrgId is a single scalar — writing it here would silently
        // evict the user from acting as any other vendor org they already belong to. Tenancy
        // for vendor-service's own endpoints is resolved from VendorMember, never from the
        // caller's JWT orgId claim.
        log.info("Registered vendor org={} for user={}", orgId, userId);
        return toResponse(profile, null);
    }

    /** All vendor orgs the caller belongs to (owner or invited member), not just owned ones. */
    public List<VendorProfileResponse> listMine(UUID userId) {
        return memberRepo.findByUserId(userId).stream()
                .map(m -> profileRepo.findByOrgId(m.getOrgId()).map(p -> toResponse(p, null)))
                .flatMap(Optional::stream)
                .toList();
    }

    public VendorProfileResponse getByOrgId(UUID orgId, UUID requesterId) {
        VendorProfile profile = requireProfile(orgId);
        requireMember(profile, requesterId);
        VendorKycChecklist kyc = kycRepo.findById(orgId).orElse(null);
        return toResponse(profile, kyc);
    }

    /** Cross-org admin lookup — bypasses membership entirely, callers must check isConfigAdmin(). */
    public VendorProfileResponse getByOrgIdAsAdmin(UUID orgId) {
        VendorProfile profile = requireProfile(orgId);
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
    public VendorProfileResponse submit(UUID orgId, UUID requesterId) {
        VendorProfile profile = requireProfile(orgId);
        requireManager(profile, requesterId);
        return updateStatus(orgId, "SUBMITTED", requesterId);
    }

    @Transactional
    public KycChecklistDto computeKyc(UUID orgId, UUID requesterId) {
        VendorProfile profile = requireProfile(orgId);
        requireMember(profile, requesterId);

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

    private VendorProfile requireProfile(UUID orgId) {
        return profileRepo.findByOrgId(orgId)
                .orElseThrow(() -> new NoSuchElementException("Vendor not found: " + orgId));
    }

    private VendorMember requireMember(VendorProfile profile, UUID userId) {
        return memberRepo.findByOrgIdAndUserId(profile.getOrgId(), userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this vendor org"));
    }

    private VendorMember requireManager(VendorProfile profile, UUID userId) {
        VendorMember member = requireMember(profile, userId);
        if (!"OWNER".equals(member.getRole()) && !"ADMIN".equals(member.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "OWNER or ADMIN role required");
        }
        return member;
    }

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
