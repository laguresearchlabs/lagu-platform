package com.lagu.platform.vendor.service;

import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.vendor.client.ListingServiceClient;
import com.lagu.platform.vendor.client.RecordServiceClient;
import com.lagu.platform.vendor.domain.*;
import com.lagu.platform.vendor.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class VendorService {

    private final VendorProfileRepository     profileRepo;
    private final VendorMemberRepository      memberRepo;
    private final VendorKycChecklistRepository kycRepo;
    private final RecordServiceClient         recordClient;
    private final ListingServiceClient        listingClient;

    @Transactional
    public VendorProfileResponse register(RegisterVendorRequest req, UUID userId) {
        UUID tenantId = UUID.randomUUID();

        // Create the canonical VENDOR record in record-service. Field key is "name" per the
        // VENDOR schema (schema-registry's basic_details.name) — not "businessName"; the two
        // just happen to share a value at registration time.
        // HashMap rather than Map.of: contactEmail is optional and Map.of rejects a null value.
        Map<String, Object> vendorFields = new HashMap<>();
        vendorFields.put("name", req.getBusinessName());
        vendorFields.put("country", req.getCountry());
        if (req.getContactEmail() != null && !req.getContactEmail().isBlank()) {
            vendorFields.put("email", req.getContactEmail().trim());
        }

        Map<String, Object> recordResponse =
                recordClient.createRecord(tenantId, userId, "VENDOR", vendorFields);
        UUID recordId = recordClient.extractRecordId(recordResponse);
        if (recordId == null) {
            throw new IllegalStateException("Failed to create VENDOR record in record-service");
        }

        // Persist local profile
        VendorProfile profile = new VendorProfile();
        profile.setId(tenantId);
        profile.setRecordId(recordId);
        profile.setOwnerUserId(userId);
        profile.setBusinessName(req.getBusinessName());
        profile.setCountry(req.getCountry());
        profileRepo.save(profile);

        // Add owner as member
        VendorMember owner = new VendorMember();
        owner.setTenantId(tenantId);
        owner.setUserId(userId);
        owner.setRole("OWNER");
        memberRepo.save(owner);

        // Initialise empty KYC checklist
        VendorKycChecklist kyc = new VendorKycChecklist();
        kyc.setTenantId(tenantId);
        kyc.setBusinessNameFilled(req.getBusinessName() != null && !req.getBusinessName().isBlank());
        kycRepo.save(kyc);

        // Tenancy for vendor-service's own endpoints is resolved from VendorMember (which allows
        // a user to belong to many vendor orgs via its tenant_id+user_id unique pair), never from
        // the caller's JWT tenantId claim, which only ever reflects the single org a request
        // targets — not the full set the user belongs to.
        log.info("Registered vendor org={} for user={}", tenantId, userId);
        return toResponse(profile, null);
    }

    /** All vendor orgs the caller belongs to (owner or invited member), not just owned ones. */
    public List<VendorProfileResponse> listMine(UUID userId) {
        return memberRepo.findByUserId(userId).stream()
                .map(m -> profileRepo.findById(m.getTenantId()).map(p -> toResponse(p, null)))
                .flatMap(Optional::stream)
                .toList();
    }

    public VendorProfileResponse getByTenantId(UUID tenantId, UUID requesterId) {
        VendorProfile profile = requireProfile(tenantId);
        requireMember(profile, requesterId);
        VendorKycChecklist kyc = kycRepo.findById(tenantId).orElse(null);
        return toResponse(profile, kyc);
    }

    /** Cross-org admin lookup — bypasses membership entirely, callers must check isConfigAdmin(). */
    public VendorProfileResponse getByTenantIdAsAdmin(UUID tenantId) {
        VendorProfile profile = requireProfile(tenantId);
        VendorKycChecklist kyc = kycRepo.findById(tenantId).orElse(null);
        return toResponse(profile, kyc);
    }

    /** Admin listing — cross-org, paginated, optionally filtered by status and/or a business-name
     *  search term. */
    public PageResult<VendorProfileResponse> listForAdmin(String status, String search, int page, int size) {
        String st = (status != null && !status.isBlank()) ? status.toUpperCase() : null;
        String q = (search != null && !search.isBlank()) ? search.trim() : null;
        var results = profileRepo.search(st, q, PageRequest.of(page, size));
        return PageResult.from(results.map(p -> toResponse(p, null)));
    }

    @Transactional
    public VendorProfileResponse updateStatus(UUID tenantId, String newStatus, UUID actorId) {
        VendorProfile profile = profileRepo.findById(tenantId)
                .orElseThrow(() -> new NoSuchElementException("Vendor not found: " + tenantId));

        validateStatusTransition(profile.getStatus(), newStatus);
        String resolved = newStatus.toUpperCase();
        profile.setStatus(resolved);
        profileRepo.save(profile);
        log.info("Vendor {} status changed to {} by {}", tenantId, resolved, actorId);

        // Reaching ACTIVE is what releases any listings listing-service held behind the KYC gate.
        // Without this the gate is a one-way door: nothing else in the platform re-publishes an
        // approved listing, so a vendor who completed KYC would stay invisible with no signal.
        //
        // Best-effort on purpose — see ListingServiceClient. The approval must not fail because a
        // downstream service is restarting, and the reconcile endpoint is idempotent so re-running
        // it is the recovery.
        if ("ACTIVE".equals(resolved)) {
            int released = listingClient.reconcileHeldListings(tenantId);
            if (released > 0) {
                log.info("Activation of vendor {} released {} held listing(s)", tenantId, released);
            }
        }
        return toResponse(profile, null);
    }

    @Transactional
    public VendorProfileResponse submit(UUID tenantId, UUID requesterId) {
        VendorProfile profile = requireProfile(tenantId);
        requireManager(profile, requesterId);
        return updateStatus(tenantId, "SUBMITTED", requesterId);
    }

    @Transactional
    public KycChecklistDto computeKyc(UUID tenantId, UUID requesterId) {
        VendorProfile profile = requireProfile(tenantId);
        requireMember(profile, requesterId);

        // Fetch document status from document-service via record-service client
        Map<String, Object> docStatus = recordClient.getDocumentStatus(tenantId, profile.getOwnerUserId());

        VendorKycChecklist kyc = kycRepo.findById(tenantId).orElseGet(() -> {
            VendorKycChecklist c = new VendorKycChecklist();
            c.setTenantId(tenantId);
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

    private VendorProfile requireProfile(UUID tenantId) {
        return profileRepo.findById(tenantId)
                .orElseThrow(() -> new NoSuchElementException("Vendor not found: " + tenantId));
    }

    private VendorMember requireMember(VendorProfile profile, UUID userId) {
        return memberRepo.findByTenantIdAndUserIdAndStatus(profile.getTenantId(), userId, "ACTIVE")
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

    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
        "DRAFT",        Set.of("SUBMITTED"),
        "SUBMITTED",    Set.of("UNDER_REVIEW", "DRAFT"),
        "UNDER_REVIEW", Set.of("ACTIVE", "REJECTED"),
        "ACTIVE",       Set.of("SUSPENDED"),
        "SUSPENDED",    Set.of("ACTIVE", "REJECTED"),
        "REJECTED",     Set.of("DRAFT")
    );

    /**
     * Refusing an illegal transition is an ordinary business-rule outcome, not a server fault —
     * two admins working the same vendor will hit it routinely, and so will a stale browser tab.
     * It previously threw an unmapped IllegalStateException, so the caller got 500 "An unexpected
     * error occurred" while the server knew exactly what was wrong and discarded it.
     *
     * <p>409 rather than 400: the request was well formed and would have been valid against a
     * different current status. The response names both the current status and what it does allow,
     * so an admin can act on it without reading the state machine.
     *
     * <p>Note this is fixed at the throw site rather than by mapping IllegalStateException in
     * GlobalExceptionHandler. The platform uses that exception for genuine server faults too —
     * {@code register()} above throws it when record-service fails to create the VENDOR record —
     * and a blanket 409 would turn a real outage into a status code nothing alerts on.
     */
    private void validateStatusTransition(String current, String next) {
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(current.toUpperCase(), Set.of());
        if (!allowed.contains(next.toUpperCase())) {
            String options = allowed.isEmpty()
                    ? "no further transitions are possible from there"
                    : "allowed from " + current.toUpperCase() + ": "
                            + allowed.stream().sorted().collect(Collectors.joining(", "));
            throw new PlatformException("INVALID_STATUS_TRANSITION",
                    "Cannot change vendor status from " + current.toUpperCase()
                            + " to " + next.toUpperCase() + " — " + options,
                    HttpStatus.CONFLICT);
        }
    }

    private VendorProfileResponse toResponse(VendorProfile p, VendorKycChecklist kyc) {
        return VendorProfileResponse.builder()
                .tenantId(p.getTenantId())
                .recordId(p.getRecordId())
                .businessName(p.getBusinessName())
                .status(p.getStatus())
                .country(p.getCountry())
                .kycChecklist(kyc != null ? toKycDto(kyc) : null)
                .createdAt(p.getCreatedAt() != null ? p.getCreatedAt().atOffset(java.time.ZoneOffset.UTC) : null)
                .updatedAt(p.getUpdatedAt() != null ? p.getUpdatedAt().atOffset(java.time.ZoneOffset.UTC) : null)
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

    /**
     * Who to address when another service has something to tell this vendor org, and where to
     * email them. Used by booking-service when a customer acts on a booking.
     *
     * Two lookups, deliberately independent: the OWNER row is authoritative for *who*, and the
     * org's VENDOR record supplies the business contact email. A record-service failure or a
     * blank email field degrades to a userId with no address — the in-app notification still
     * lands, only the email half is lost — rather than failing the whole resolution.
     */
    public Optional<MembershipOwnerResponse> resolveNotificationTarget(UUID tenantId) {
        return memberRepo
                .findFirstByTenantIdAndRoleAndStatusOrderByJoinedAtAsc(tenantId, "OWNER", "ACTIVE")
                .map(owner -> new MembershipOwnerResponse(
                        owner.getUserId(), owner.getRole(), contactEmail(tenantId)));
    }

    /** The `email` field of the org's VENDOR record, or null if unset/unreachable. */
    @SuppressWarnings("unchecked")
    private String contactEmail(UUID tenantId) {
        try {
            UUID recordId = profileRepo.findById(tenantId).map(VendorProfile::getRecordId).orElse(null);
            if (recordId == null) return null;

            Map<String, Object> response = recordClient.getRecord(recordId, tenantId);
            if (response == null || !(response.get("data") instanceof Map<?, ?> record)) return null;
            if (!(((Map<String, Object>) record).get("data") instanceof Map<?, ?> fields)) return null;

            Object email = ((Map<String, Object>) fields).get("email");
            String value = email != null ? email.toString().trim() : "";
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            log.warn("Could not read contact email for org {}: {}", tenantId, e.getMessage());
            return null;
        }
    }
}
