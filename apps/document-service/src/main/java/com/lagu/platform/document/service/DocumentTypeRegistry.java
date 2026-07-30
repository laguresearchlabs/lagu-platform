package com.lagu.platform.document.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final List<DocumentConfig> FALLBACK = List.of(
        new DocumentConfig("RESUME",               "Resume / CV",                            true,  false, null),
        new DocumentConfig("HR_IDENTITY_PROOF",    "Government-issued Identity Proof",       true,  false, null),
        new DocumentConfig("PHOTOGRAPH",           "Passport-size Photograph",               true,  false, null),
        new DocumentConfig("ACADEMIC_CERTIFICATE", "Academic Certificates / Mark Sheets",    false, false, null),
        new DocumentConfig("ADDRESS_PROOF",        "Address Proof",                          false, false, null),
        new DocumentConfig("OTHER",                "Additional Documents",                   false, false, null)
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
                            str(m, "listingType")))
                    .toList();

            configs.clear();
            configs.addAll(loaded);
            log.info("DocumentTypeRegistry: loaded {} type(s) from schema-registry", loaded.size());
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

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    public record DocumentConfig(String code, String label, boolean required, boolean expiryTracked,
                                  String listingType) {}
}
