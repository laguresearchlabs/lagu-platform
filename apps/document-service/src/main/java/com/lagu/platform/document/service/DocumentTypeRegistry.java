package com.lagu.platform.document.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loads document type definitions from metadata-service at startup and refreshes hourly.
 * Falls back to a built-in static list if metadata-service is unreachable.
 */
@Component
@Slf4j
public class DocumentTypeRegistry {

    @Value("${platform.metadata.url:http://metadata-service}")
    private String metadataBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<DocumentConfig> configs = new CopyOnWriteArrayList<>(FALLBACK);

    private static final List<DocumentConfig> FALLBACK = List.of(
        new DocumentConfig("RESUME",               "Resume / CV",                            true,  false),
        new DocumentConfig("HR_IDENTITY_PROOF",    "Government-issued Identity Proof",       true,  false),
        new DocumentConfig("PHOTOGRAPH",           "Passport-size Photograph",               true,  false),
        new DocumentConfig("ACADEMIC_CERTIFICATE", "Academic Certificates / Mark Sheets",    false, false),
        new DocumentConfig("ADDRESS_PROOF",        "Address Proof",                          false, false),
        new DocumentConfig("OTHER",                "Additional Documents",                   false, false)
    );

    @PostConstruct
    public void init() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${platform.doc-types.refresh-ms:3600000}")
    public void refresh() {
        String url = metadataBaseUrl + "/api/v1/document-types";
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
                            Boolean.TRUE.equals(m.get("expiryTracked"))))
                    .toList();

            configs.clear();
            configs.addAll(loaded);
            log.info("DocumentTypeRegistry: loaded {} type(s) from metadata-service", loaded.size());
        } catch (Exception ex) {
            log.warn("DocumentTypeRegistry: could not reach metadata-service ({}), using fallback",
                    ex.getMessage());
        }
    }

    public List<DocumentConfig> all() {
        return Collections.unmodifiableList(configs);
    }

    public Set<String> validCodes() {
        Set<String> codes = new HashSet<>();
        configs.forEach(c -> codes.add(c.code()));
        return codes;
    }

    public boolean isRequired(String code) {
        return configs.stream().anyMatch(c -> c.code().equals(code) && c.required());
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    record DocumentConfig(String code, String label, boolean required, boolean expiryTracked) {}
}
