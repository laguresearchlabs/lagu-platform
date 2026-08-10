package com.lagu.platform.record.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.record.client.MetadataClient;
import com.lagu.platform.record.client.MetadataClient.FieldSchemaDto;
import com.lagu.platform.record.domain.Record;
import com.lagu.platform.record.domain.RecordRepository;
import com.lagu.platform.record.dto.FileConfirmRequest;
import com.lagu.platform.record.dto.FileUploadUrlRequest;
import com.lagu.platform.record.dto.FileUploadUrlResponse;
import com.lagu.platform.record.dto.RecordResponse;
import com.lagu.platform.record.service.MediaFieldRules;
import com.lagu.platform.record.service.RecordService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.security.RequirePermission;
import com.lagu.platform.storage.MediaIngest;
import com.lagu.platform.storage.PresignedUpload;
import com.lagu.platform.storage.StorageKeys;
import com.lagu.platform.storage.StorageProperties;
import com.lagu.platform.storage.StorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * File and image fields on records.
 *
 * <p>Bytes go straight from the client to the storage bucket via a presigned URL; this service
 * never handles them. The record's JSONB field holds the storage <b>key</b>, and a download URL
 * is signed on demand by {@link #getFileUrl}.
 *
 * <p>Previously this proxied uploads to image-service and stored whatever came back — which was
 * a signed URL with a ten-minute lifetime, so the value persisted in the record stopped
 * resolving almost immediately.
 */
@RestController
@RequestMapping("/api/v1/records")
@RequiredArgsConstructor
@Slf4j
public class RecordFileController {

    /** Single-object fields. A MEDIA_GALLERY holds many and is served by
     *  {@link RecordGalleryController}, whose items are addressed individually. */
    private static final Set<String> FILE_TYPES =
            Set.of(MediaFieldRules.TYPE_FILE, MediaFieldRules.TYPE_IMAGE);

    private final RecordRepository    recordRepository;
    private final RecordService       recordService;
    private final MetadataClient      metadataClient;
    private final StorageService      storage;
    private final StorageProperties   storageProperties;
    private final MediaIngest         mediaIngest;

    /**
     * Step 1 — presigned upload URL for a FILE/IMAGE field.
     *
     * <p>Authorization is the record's: the caller must be able to see and update this record,
     * which {@code findForContext} plus {@code @RequirePermission} establish. The key is then
     * scoped to the record id, so step 3 can check the object belongs to it.
     */
    @PostMapping("/{id}/files/{fieldName}/upload-url")
    @RequirePermission(resource = "RECORD", action = "UPDATE")
    public ResponseEntity<ApiResponse<FileUploadUrlResponse>> requestUploadUrl(
            @PathVariable UUID id,
            @PathVariable String fieldName,
            @Valid @RequestBody FileUploadUrlRequest request) {

        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        Record record = recordService.findForContext(id, ctx);
        FieldSchemaDto field = requireFileField(record, fieldName);

        String contentType = request.getContentType().toLowerCase();
        MediaFieldRules.policyFor(field).checkDeclared(request.getFileName(), contentType, request.getSizeBytes());

        String key = StorageKeys.buildPending(storageProperties.getDomain(), id, request.getFileName());
        PresignedUpload upload = storage.presignUpload(
                key, contentType, storageProperties.getUploadUrlTtl());

        return ResponseEntity.ok(ApiResponse.ok(FileUploadUrlResponse.builder()
                .uploadUrl(upload.url())
                .key(upload.key())
                .contentType(upload.contentType())
                .expiresAt(upload.expiresAt())
                .build()));
    }

    /**
     * Step 3 — record the uploaded object against the field.
     *
     * <p>Stores the key, not a URL. The object is confirmed to exist and be non-empty first, so
     * a field never ends up pointing at something that was never uploaded.
     */
    @PostMapping("/{id}/files/{fieldName}/confirm")
    @RequirePermission(resource = "RECORD", action = "UPDATE")
    public ResponseEntity<ApiResponse<RecordResponse>> confirmUpload(
            @PathVariable UUID id,
            @PathVariable String fieldName,
            @Valid @RequestBody FileConfirmRequest request) {

        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        Record record = recordService.findForContext(id, ctx);
        FieldSchemaDto field = requireFileField(record, fieldName);

        String pendingKey = request.getKey();
        requireKeyBelongsTo(pendingKey, id);
        if (!StorageKeys.isPending(pendingKey)) {
            // Confirming a durable key would re-adopt an object that is already referenced,
            // and MediaIngest would reject it as a programming error rather than bad input.
            throw new ValidationException("Key is not an uploaded object awaiting confirmation");
        }

        MediaIngest.Result ingested;
        try {
            ingested = mediaIngest.confirm(MediaIngest.Request.builder()
                    .pendingKey(pendingKey)
                    .policy(MediaFieldRules.policyFor(field))
                    .image(MediaFieldRules.imageConstraintsFor(field))
                    .derivatives(MediaFieldRules.wantsDerivatives(field))
                    .build());
        } catch (ValidationException e) {
            throw new ValidationException(
                    "Rejected upload for " + field.type() + " field '" + fieldName + "': "
                            + e.getMessage());
        }

        // Replacing a value has to delete what it replaced. Without this, re-uploading a logo
        // ten times left nine objects in the bucket that nothing referenced and nothing would
        // ever clean up — they are past the pending stage, so the lifecycle rule does not
        // reach them either.
        Map<String, Object> updated = new HashMap<>(record.getData());
        Object previous = updated.put(fieldName, ingested.key());

        record.setData(updated);
        record.setUpdatedBy(ctx != null ? ctx.getUserId() : null);
        recordRepository.save(record);

        deleteReplaced(previous, ingested.key(), id);

        log.info("Stored file for record={} field={} key={}", id, fieldName, ingested.key());
        return ResponseEntity.ok(ApiResponse.ok(recordService.toResponse(record)));
    }

    /**
     * Removes the object a field used to point at, after the new value is safely saved.
     *
     * <p>Deliberately after the save, and deliberately not fatal: a delete that fails leaves an
     * orphan, which is untidy, while a delete that runs before a failed save would destroy the
     * file the field still references. Its derivatives go too — they are only meaningful
     * alongside the original.
     */
    private void deleteReplaced(Object previousValue, String newKey, UUID recordId) {
        if (!(previousValue instanceof String previousKey)
                || previousKey.isBlank() || previousKey.equals(newKey)) {
            return;
        }
        if (!StorageKeys.isOwnedBy(previousKey, storageProperties.getDomain(), recordId)) return;

        try {
            storage.delete(previousKey);
            storage.delete(StorageKeys.variantOf(previousKey, MediaIngest.CARD_VARIANT));
            storage.delete(StorageKeys.variantOf(previousKey, MediaIngest.FULL_VARIANT));
        } catch (RuntimeException e) {
            log.warn("Could not delete replaced object {}: {}", previousKey, e.toString());
        }
    }

    /**
     * A freshly signed, short-lived download URL for a file field.
     *
     * <p>Separate endpoint rather than signing inside {@code toResponse}: working out which
     * fields are FILE/IMAGE needs the object-type schema, and doing that per record would put a
     * metadata lookup on every list response to sign URLs most callers never use.
     */
    @GetMapping("/{id}/files/{fieldName}")
    @RequirePermission(resource = "RECORD", action = "READ")
    public ResponseEntity<ApiResponse<Map<String, String>>> getFileUrl(
            @PathVariable UUID id,
            @PathVariable String fieldName) {

        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        Record record = recordService.findForContext(id, ctx);
        requireFileField(record, fieldName);

        Object value = record.getData().get(fieldName);

        if (value == null) {
            throw new ValidationException("Field '" + fieldName + "' has no file");
        }

        // Re-check the prefix on the way out, not only at confirm. The field's value lives in
        // the record's JSONB, and the generic record write path reaches that same JSONB — so
        // confirm-time validation alone would leave signing driven by a value a caller could
        // have written directly, letting someone with UPDATE on their own record name another
        // record's key here and receive a signed URL for it.
        String key = value.toString();
        requireKeyBelongsTo(key, id);

        String url = storage.presignDownload(key, storageProperties.getDownloadUrlTtl());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("url", url, "expiresIn",
                String.valueOf(storageProperties.getDownloadUrlTtl().toSeconds()))));
    }

    /**
     * The field must exist on the record's object type and be declared FILE or IMAGE.
     *
     * @return the field's schema, which carries both its type and its media rules
     */
    private FieldSchemaDto requireFileField(Record record, String fieldName) {
        MetadataClient.ObjectTypeSchemaDto schema = metadataClient.getSchema(record.getObjectType());
        FieldSchemaDto field = schema.fields().stream()
                .filter(f -> f.name().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new ValidationException(
                        "Field '" + fieldName + "' is not defined for " + record.getObjectType()));
        if (!FILE_TYPES.contains(field.type())) {
            throw new ValidationException("Field '" + fieldName + "' is not a FILE or IMAGE field");
        }
        return field;
    }

    /**
     * Object keys are scoped to the record, so a caller with rights on record A cannot reach an
     * object belonging to record B — on either the write or the read side.
     */
    private void requireKeyBelongsTo(String key, UUID recordId) {
        if (!StorageKeys.isOwnedBy(key, storageProperties.getDomain(), recordId)) {
            throw new ValidationException("Key does not belong to record " + recordId);
        }
    }

}
