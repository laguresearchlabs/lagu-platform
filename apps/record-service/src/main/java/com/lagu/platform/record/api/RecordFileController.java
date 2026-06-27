package com.lagu.platform.record.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.record.client.ImageServiceClient;
import com.lagu.platform.record.client.MetadataClient;
import com.lagu.platform.record.domain.Record;
import com.lagu.platform.record.domain.RecordRepository;
import com.lagu.platform.record.dto.RecordResponse;
import com.lagu.platform.record.service.RecordService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/records")
@RequiredArgsConstructor
@Slf4j
public class RecordFileController {

    private static final Set<String> FILE_TYPES = Set.of("FILE", "IMAGE");

    private final RecordRepository   recordRepository;
    private final RecordService      recordService;
    private final MetadataClient     metadataClient;
    private final ImageServiceClient imageServiceClient;

    /**
     * Upload a file/image for a specific field of a record.
     * The field must be of type FILE or IMAGE in the object-type schema.
     * On success, the field value in the record's JSONB data is replaced with the
     * URL returned by image-service, and the updated record is returned.
     */
    @PostMapping(value = "/{id}/files/{fieldName}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission(resource = "RECORD", action = "UPDATE")
    public ResponseEntity<ApiResponse<RecordResponse>> uploadFile(
            @PathVariable UUID id,
            @PathVariable String fieldName,
            @RequestParam("file") MultipartFile file) {

        PlatformSecurityContext ctx = GatewayHeaderFilter.current();

        Record record = (ctx != null && ctx.getOrgId() != null && !ctx.isPlatformAdmin())
                ? recordRepository.findByIdAndOrgId(id, ctx.getOrgId())
                        .orElseThrow(() -> new com.lagu.platform.common.exception.ResourceNotFoundException("Record", id.toString()))
                : recordRepository.findById(id)
                        .orElseThrow(() -> new com.lagu.platform.common.exception.ResourceNotFoundException("Record", id.toString()));

        // Verify the field is of type FILE or IMAGE
        MetadataClient.ObjectTypeSchemaDto schema = metadataClient.getSchema(record.getObjectType());
        schema.fields().stream()
                .filter(f -> f.name().equals(fieldName))
                .findFirst()
                .ifPresentOrElse(
                        f -> {
                            if (!FILE_TYPES.contains(f.type())) {
                                throw new ValidationException("Field '" + fieldName + "' is not a FILE or IMAGE field");
                            }
                        },
                        () -> { throw new ValidationException("Field '" + fieldName + "' is not defined for " + record.getObjectType()); }
                );

        // Upload to image-service and get URL
        String fileUrl = imageServiceClient.upload(file, id, fieldName);
        log.info("Uploaded file for record={} field={} url={}", id, fieldName, fileUrl);

        // Merge URL into record data
        Map<String, Object> updated = new HashMap<>(record.getData());
        updated.put(fieldName, fileUrl);
        record.setData(updated);
        record.setUpdatedBy(ctx != null ? ctx.getUserId() : null);
        recordRepository.save(record);

        return ResponseEntity.ok(ApiResponse.ok(recordService.toResponse(record)));
    }
}
