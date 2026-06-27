package com.lagu.platform.record.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImageServiceClient {

    private final RestClient imageRestClient;

    /**
     * Uploads a file to image-service under PLATFORM_RECORD group.
     *
     * @param file         the multipart file from the client request
     * @param recordId     used as the groupId in image-service
     * @param fieldName    stored as subGroupType (e.g. "logo", "banner")
     * @return the public image URL from image-service
     */
    @SuppressWarnings("unchecked")
    public String upload(MultipartFile file, UUID recordId, String fieldName) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        try {
            parts.add("file",           file.getResource());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read uploaded file", e);
        }
        parts.add("group-id",       recordId.toString());
        parts.add("group-type",     "PLATFORM_RECORD");
        parts.add("sub-group-type", fieldName);

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
        if (url == null) url = (String) data.get("id");  // fallback: store the image ID
        return url;
    }
}
