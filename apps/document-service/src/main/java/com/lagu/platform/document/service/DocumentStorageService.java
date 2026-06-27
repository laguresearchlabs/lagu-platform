package com.lagu.platform.document.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentStorageService {

    private final RestClient imageRestClient;

    /**
     * Proxies a document upload to image-service.
     * group-type = HR_DOCUMENT, group-id = userId, sub-group-type = documentType
     */
    @SuppressWarnings("unchecked")
    public String upload(MultipartFile file, UUID userId, String documentType) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file",           file.getResource());
        parts.add("group-id",       userId.toString());
        parts.add("group-type",     "HR_DOCUMENT");
        parts.add("sub-group-type", documentType);

        Map<String, Object> response = imageRestClient.post()
                .uri("/api/v1/images/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(Map.class);

        if (response == null || response.get("data") == null) {
            throw new IllegalStateException("Empty response from image-service");
        }

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        String url = (String) data.get("signedUrl");
        if (url == null) url = (String) data.get("imageURL");
        if (url == null) {
            Object idVal = data.get("id");
            url = idVal != null ? idVal.toString() : null;
        }
        if (url == null) {
            throw new IllegalStateException("No URL in image-service response");
        }
        log.debug("Document uploaded: userId={} type={} url={}", userId, documentType, url);
        return url;
    }
}
