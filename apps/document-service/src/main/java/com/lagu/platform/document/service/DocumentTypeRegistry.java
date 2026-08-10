package com.lagu.platform.document.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.storage.ContentTypeSniffer;
import com.lagu.platform.storage.MediaPolicy;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loads document type definitions from schema-registry at startup and refreshes hourly.
 * Falls back to a built-in static list if schema-registry is unreachable.
 */
@Component
@Slf4j
public class DocumentTypeRegistry {

    private static final String SCHEMA_REGISTRY_BASE_URL = "http://schema-registry";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<DocumentConfig> configs = new CopyOnWriteArrayList<>(FALLBACK);

    public DocumentTypeRegistry(RestTemplate loadBalancedRestTemplate) {
        this.restTemplate = loadBalancedRestTemplate;
    }

    /**
     * Applies to any document type that does not configure its own limits — identity and
     * verification documents are photos and scans, so no executables, no HTML/SVG (stored-XSS
     * risk if ever rendered inline), no office or archive formats.
     *
     * <p>An admin can widen or narrow this per document type in schema-registry; what they
     * cannot do is leave a type unconstrained, because this is what an unconfigured one gets.
     */
    public static final MediaPolicy DEFAULT_POLICY = MediaPolicy.of(
            List.of("image/jpeg", "image/png", "image/webp", "application/pdf"), 20);

    private static final List<DocumentConfig> FALLBACK = List.of(
        new DocumentConfig("RESUME",               "Resume / CV",                            true,  false, null, null, 0),
        new DocumentConfig("HR_IDENTITY_PROOF",    "Government-issued Identity Proof",       true,  false, null, null, 0),
        new DocumentConfig("PHOTOGRAPH",           "Passport-size Photograph",               true,  false, null, null, 0),
        new DocumentConfig("ACADEMIC_CERTIFICATE", "Academic Certificates / Mark Sheets",    false, false, null, null, 0),
        new DocumentConfig("ADDRESS_PROOF",        "Address Proof",                          false, false, null, null, 0),
        new DocumentConfig("OTHER",                "Additional Documents",                   false, false, null, null, 0)
    );

    @PostConstruct
    public void init() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${platform.doc-types.refresh-ms:3600000}")
    public void refresh() {
        String url = SCHEMA_REGISTRY_BASE_URL + "/api/v1/document-requirements/catalog";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response == null) return;

            Object data = response.get("data");
            List<Map<String, Object>> items = objectMapper.convertValue(
                    data, new TypeReference<>() {});

            if (items == null || items.isEmpty()) return;

            List<DocumentConfig> loaded = items.stream()
                    .map(m -> new DocumentConfig(
                            str(m, "code"),
                            str(m, "label"),
                            Boolean.TRUE.equals(m.get("required")),
                            Boolean.TRUE.equals(m.get("expiryTracked")),
                            str(m, "listingType"),
                            strList(m, "allowedMimeTypes"),
                            intOrZero(m, "maxSizeMb")))
                    .toList();

            configs.clear();
            configs.addAll(loaded);
            log.info("DocumentTypeRegistry: loaded {} type(s) from schema-registry", loaded.size());
            warnAboutUnverifiableTypes(loaded);
        } catch (Exception ex) {
            log.warn("DocumentTypeRegistry: could not reach schema-registry ({}), using fallback",
                    ex.getMessage());
        }
    }

    /** Types with no listingType (null) are generic/HR-oriented and available regardless of
     *  context; document-service's own /submission-status checklist has always used this. */
    public List<DocumentConfig> all() {
        return forListingType(null);
    }

    /** Types visible for a given listing-type context: the generic (listingType == null) set
     *  plus any specific to this listingType (e.g. "VENDOR" also unlocks PAN_CARD,
     *  GST_CERTIFICATE, BANK_CANCELLED_CHEQUE, ...). A null/blank listingType returns only the
     *  generic set — same as the historical (pre-listingType-aware) behavior. */
    public List<DocumentConfig> forListingType(String listingType) {
        return configs.stream()
                .filter(c -> c.listingType() == null
                        || (listingType != null && c.listingType().equalsIgnoreCase(listingType)))
                .toList();
    }

    public Set<String> validCodes() {
        return validCodes(null);
    }

    public Set<String> validCodes(String listingType) {
        Set<String> codes = new HashSet<>();
        forListingType(listingType).forEach(c -> codes.add(c.code()));
        return codes;
    }

    public boolean isRequired(String code) {
        return configs.stream().anyMatch(c -> c.code().equals(code) && c.required());
    }

    /**
     * Upload constraints for one document type: what an admin configured for it, over the
     * platform default for anything they left unset.
     *
     * <p>Before this, {@code allowed_mime_types} and {@code max_size_mb} were editable in the
     * admin API and persisted, while document-service enforced compiled-in constants — so the
     * screen worked, saved, and changed nothing. An unknown code falls back to the default
     * rather than to "no limits"; {@code validateDocumentType} rejects it separately, and this
     * must not be the thing that decides whether that happens.
     */
    public MediaPolicy policyFor(String documentType) {
        if (documentType == null) return DEFAULT_POLICY;
        return configs.stream()
                .filter(c -> documentType.equalsIgnoreCase(c.code()))
                .findFirst()
                .map(c -> DEFAULT_POLICY.overriddenBy(c.allowedMimeTypes(), c.maxSizeMb()))
                .orElse(DEFAULT_POLICY);
    }

    /**
     * A configured type with no signature in {@link ContentTypeSniffer} rejects every upload at
     * confirm time, because the sniffer fails closed. That is the correct behaviour but an
     * awful way to find out, so it is reported here — once, when the configuration loads —
     * rather than being left to surface as vendors failing to upload.
     */
    private void warnAboutUnverifiableTypes(List<DocumentConfig> loaded) {
        loaded.stream()
                .filter(c -> c.allowedMimeTypes() != null && !c.allowedMimeTypes().isEmpty())
                .forEach(c -> {
                    Set<String> unverifiable = policyFor(c.code()).unverifiableTypes();
                    if (!unverifiable.isEmpty()) {
                        log.warn("DocumentTypeRegistry: document type {} allows {} which the platform "
                                        + "cannot verify — uploads of those types will be rejected at "
                                        + "confirm. Verifiable types: {}",
                                c.code(), unverifiable, ContentTypeSniffer.supportedTypes());
                    }
                });
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> strList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof List<?> list)) return null;
        return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }

    private int intOrZero(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /**
     * @param allowedMimeTypes admin-configured content types, or null to take the platform default
     * @param maxSizeMb        admin-configured size cap in MB; 0 means "unset", not "unlimited"
     */
    public record DocumentConfig(String code, String label, boolean required, boolean expiryTracked,
                                  String listingType, List<String> allowedMimeTypes, int maxSizeMb) {}
}
