package com.lagu.platform.record.api;

import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.record.client.MetadataClient;
import com.lagu.platform.record.domain.Record;
import com.lagu.platform.record.domain.RecordRepository;
import com.lagu.platform.record.dto.FileConfirmRequest;
import com.lagu.platform.record.dto.FileUploadUrlRequest;
import com.lagu.platform.record.service.RecordService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.storage.MediaIngest;
import com.lagu.platform.storage.MediaScanner;
import com.lagu.platform.storage.ObjectMeta;
import com.lagu.platform.storage.PresignedUpload;
import com.lagu.platform.storage.StorageKeys;
import com.lagu.platform.storage.StorageProperties;
import com.lagu.platform.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Content validation for record file fields.
 *
 * <p>An IMAGE field's value gets rendered by clients, and a signed bucket URL serves an object
 * with whatever Content-Type it was stored under — so SVG or HTML reaching an IMAGE field is a
 * stored-XSS vector. This was previously unchecked: uploads were proxied to image-service with
 * no content-type allowlist anywhere in the path.
 *
 * <p>With presigned uploads the bytes never reach this service, so the check has to run against
 * the stored object at confirm time or not at all.
 */
class RecordFileControllerTest {

    private final RecordRepository repository = mock(RecordRepository.class);
    private final RecordService recordService = mock(RecordService.class);
    private final MetadataClient metadataClient = mock(MetadataClient.class);
    private final StorageService storage = mock(StorageService.class);
    private final StorageProperties storageProperties = new StorageProperties();

    /**
     * The real ingest pipeline over the mocked bucket, with scanning stubbed clean — these tests
     * are about the controller's use of it, and replacing it with a mock would remove exactly
     * the sniffing and cleanup behaviour they exist to cover.
     */
    private final MediaIngest mediaIngest =
            new MediaIngest(storage, (content, key) -> MediaScanner.ScanResult.ok());

    private final RecordFileController controller = new RecordFileController(
            repository, recordService, metadataClient, storage, storageProperties, mediaIngest);

    private static final byte[] REAL_PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] REAL_PDF = "%PDF-1.4\n".getBytes();
    private static final byte[] SVG      = "<svg xmlns=\"http://www.w3.org/2000/svg\">".getBytes();

    private static final UUID RECORD_ID = UUID.randomUUID();

    private MockedStatic<GatewayHeaderFilter> gatewayMock;

    @BeforeEach
    void setUp() {
        storageProperties.setDomain("record");

        gatewayMock = Mockito.mockStatic(GatewayHeaderFilter.class);
        gatewayMock.when(GatewayHeaderFilter::current).thenReturn(
                PlatformSecurityContext.builder()
                        .userId(UUID.randomUUID())
                        .tenantId(UUID.randomUUID())
                        .roles(Set.of("USER"))
                        .build());

        Record record = new Record();
        record.setId(RECORD_ID);
        record.setObjectType("VENUE");
        record.setData(new HashMap<>());
        when(recordService.findForContext(eq(RECORD_ID), any())).thenReturn(record);

        when(metadataClient.getSchema("VENUE")).thenReturn(
                new MetadataClient.ObjectTypeSchemaDto("VENUE", List.of(
                        field("logo", "IMAGE"),
                        field("brochure", "FILE"),
                        field("name", "TEXT"))));

        when(storage.presignUpload(anyString(), anyString(), any())).thenAnswer(inv ->
                new PresignedUpload("https://bucket/put", inv.getArgument(0),
                        inv.getArgument(1), Instant.now().plusSeconds(900)));
    }

    @AfterEach
    void tearDown() {
        gatewayMock.close();
    }

    private static MetadataClient.FieldSchemaDto field(String name, String type) {
        return new MetadataClient.FieldSchemaDto(
                name, name, type, false, false, true, true, false, null, null, null, null);
    }

    /**
     * Puts content behind a pending key, as the bucket would report it after a signed PUT.
     * Uploads always land under {@code pending/} and are promoted by confirm.
     */
    private String storedObject(String contentType, byte[] content) {
        String key = StorageKeys.buildPending("record", RECORD_ID, "f.png");
        when(storage.stat(key)).thenReturn(
                Optional.of(new ObjectMeta(key, content.length, contentType, Instant.now())));
        when(storage.readRange(eq(key), anyInt())).thenAnswer(inv ->
                Arrays.copyOf(content, Math.min(content.length, (int) inv.getArgument(1))));
        when(storage.readAll(eq(key), anyLong())).thenReturn(content);
        return key;
    }

    private static FileUploadUrlRequest urlRequest(String fileName, String contentType) {
        FileUploadUrlRequest r = new FileUploadUrlRequest();
        r.setFileName(fileName);
        r.setContentType(contentType);
        r.setSizeBytes(1024);
        return r;
    }

    private static FileConfirmRequest confirmRequest(String key) {
        FileConfirmRequest r = new FileConfirmRequest();
        r.setKey(key);
        return r;
    }

    // ── step 1: declared content type ─────────────────────────────────────────

    @Test
    void uploadUrlRejectsSvgForAnImageField() {
        assertThatThrownBy(() ->
                controller.requestUploadUrl(RECORD_ID, "logo", urlRequest("x.svg", "image/svg+xml")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unsupported file type");
        verifyNoInteractions(storage);
    }

    @Test
    void uploadUrlRejectsHtmlForAnImageField() {
        assertThatThrownBy(() ->
                controller.requestUploadUrl(RECORD_ID, "logo", urlRequest("x.html", "text/html")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void uploadUrlRejectsPdfForAnImageFieldButAllowsItForAFileField() {
        assertThatThrownBy(() ->
                controller.requestUploadUrl(RECORD_ID, "logo", urlRequest("x.pdf", "application/pdf")))
                .isInstanceOf(ValidationException.class);

        assertThat(controller.requestUploadUrl(RECORD_ID, "brochure",
                urlRequest("x.pdf", "application/pdf")).getBody()).isNotNull();
    }

    @Test
    void uploadUrlAcceptsPngForAnImageField() {
        var resp = controller.requestUploadUrl(RECORD_ID, "logo", urlRequest("x.png", "image/png"));
        assertThat(resp.getBody()).isNotNull();
        // Uploads are presigned against a pending key and promoted at confirm.
        assertThat(resp.getBody().getData().getKey())
                .startsWith("record/pending/" + RECORD_ID + "/");
    }

    @Test
    void uploadUrlRejectsANonFileField() {
        assertThatThrownBy(() ->
                controller.requestUploadUrl(RECORD_ID, "name", urlRequest("x.png", "image/png")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not a FILE or IMAGE field");
    }

    // ── step 3: actual stored bytes ───────────────────────────────────────────

    @Test
    void confirmRejectsSvgBytesStoredUnderAnImageContentType() {
        // The signature binds Content-Type, so the stored type is one this service approved —
        // which is exactly why the bytes still have to be checked separately.
        String key = storedObject("image/png", SVG);

        assertThatThrownBy(() -> controller.confirmUpload(RECORD_ID, "logo", confirmRequest(key)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not match its declared type")
                .hasMessageContaining("IMAGE field 'logo'");
        verify(storage).delete(key);
        verify(repository, never()).save(any());
    }

    @Test
    void confirmRejectsAnObjectStoredWithADisallowedContentType() {
        String key = storedObject("image/svg+xml", SVG);

        assertThatThrownBy(() -> controller.confirmUpload(RECORD_ID, "logo", confirmRequest(key)))
                .isInstanceOf(ValidationException.class);
        verify(storage).delete(key);
    }

    @Test
    void confirmFailsClosedWhenTheBucketReportsNoContentType() {
        String key = storedObject(null, REAL_PNG);

        assertThatThrownBy(() -> controller.confirmUpload(RECORD_ID, "logo", confirmRequest(key)))
                .isInstanceOf(ValidationException.class);
        verify(storage).delete(key);
    }

    @Test
    void confirmAcceptsARealPngAndStoresTheKeyNotAUrl() {
        String key = storedObject("image/png", REAL_PNG);

        var resp = controller.confirmUpload(RECORD_ID, "logo", confirmRequest(key));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        var saved = org.mockito.ArgumentCaptor.forClass(Record.class);
        verify(repository).save(saved.capture());
        // The stored value is the promoted key, not the pending one it was uploaded to.
        assertThat(saved.getValue().getData().get("logo")).isEqualTo(StorageKeys.promote(key));
        assertThat(saved.getValue().getData().get("logo").toString()).doesNotContain("http");
        assertThat(saved.getValue().getData().get("logo").toString()).doesNotContain("/pending/");
        verify(storage).move(key, StorageKeys.promote(key));
    }

    @Test
    void confirmAcceptsARealPdfForAFileField() {
        String key = storedObject("application/pdf", REAL_PDF);

        controller.confirmUpload(RECORD_ID, "brochure", confirmRequest(key));
        verify(repository).save(any());
    }

    @Test
    void confirmRejectsAKeyBelongingToAnotherRecord() {
        // Update rights on this record must not let a caller adopt another record's object.
        String foreign = StorageKeys.buildPending("record", UUID.randomUUID(), "f.png");

        assertThatThrownBy(() -> controller.confirmUpload(RECORD_ID, "logo", confirmRequest(foreign)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not belong to record");
        verify(repository, never()).save(any());
    }

    @Test
    void confirmRejectsAnObjectThatWasNeverUploaded() {
        String key = StorageKeys.buildPending("record", RECORD_ID, "f.png");
        when(storage.stat(key)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.confirmUpload(RECORD_ID, "logo", confirmRequest(key)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No uploaded object found");
    }

    @Test
    void confirmRejectsAnEmptyObject() {
        String key = storedObject("image/png", new byte[0]);

        assertThatThrownBy(() -> controller.confirmUpload(RECORD_ID, "logo", confirmRequest(key)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must not be empty");
        verify(storage).delete(key);
    }

    // ── read path ─────────────────────────────────────────────────────────────

    @Test
    void getFileUrlSignsFromTheStoredKey() {
        String key = "record/" + RECORD_ID + "/abc_logo.png";
        Record record = new Record();
        record.setId(RECORD_ID);
        record.setObjectType("VENUE");
        record.setData(new HashMap<>(Map.of("logo", key)));
        when(recordService.findForContext(eq(RECORD_ID), any())).thenReturn(record);
        when(storage.presignDownload(eq(key), any())).thenReturn("https://bucket/get/signed");

        var body = controller.getFileUrl(RECORD_ID, "logo").getBody();

        assertThat(body).isNotNull();
        assertThat(body.getData().get("url")).isEqualTo("https://bucket/get/signed");
    }

    /**
     * The confirm-time prefix check is not sufficient on its own, because the field's value
     * lives in the record's JSONB and the generic record write reaches the same place. If the
     * read path signed whatever it found there, someone with UPDATE on their own record could
     * write another record's key by hand and collect a signed URL for it.
     *
     * <p>{@code RecordService.preserveServerOwnedFields} is the other half of this and stops the
     * value being written at all; both are here because either alone leaves the object reachable
     * if the other is ever bypassed.
     */
    @Test
    void getFileUrlRefusesToSignAKeyBelongingToAnotherRecord() {
        String foreign = "record/" + UUID.randomUUID() + "/abc_confidential.pdf";
        Record record = new Record();
        record.setId(RECORD_ID);
        record.setObjectType("VENUE");
        record.setData(new HashMap<>(Map.of("logo", foreign)));
        when(recordService.findForContext(eq(RECORD_ID), any())).thenReturn(record);

        assertThatThrownBy(() -> controller.getFileUrl(RECORD_ID, "logo"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not belong to record");
        verify(storage, never()).presignDownload(anyString(), any());
    }

    // ── admin-configured limits ───────────────────────────────────────────────

    /** Media rules ride in the field's validationRules, which schema-registry already exposes
     *  for editing and already delivers with the schema — so narrowing a field is configuration,
     *  not a release. */
    @Test
    void fieldValidationRulesNarrowTheAllowedTypes() {
        when(metadataClient.getSchema("VENUE")).thenReturn(
                new MetadataClient.ObjectTypeSchemaDto("VENUE", List.of(
                        fieldWithRules("brochure", "FILE",
                                Map.of("allowedMimeTypes", List.of("application/pdf"))))));

        assertThat(controller.requestUploadUrl(RECORD_ID, "brochure",
                urlRequest("x.pdf", "application/pdf")).getBody()).isNotNull();

        assertThatThrownBy(() -> controller.requestUploadUrl(RECORD_ID, "brochure",
                urlRequest("x.png", "image/png")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    void fieldValidationRulesSetTheSizeCap() {
        when(metadataClient.getSchema("VENUE")).thenReturn(
                new MetadataClient.ObjectTypeSchemaDto("VENUE", List.of(
                        fieldWithRules("logo", "IMAGE", Map.of("maxSizeMb", 1)))));

        FileUploadUrlRequest tooBig = urlRequest("x.png", "image/png");
        tooBig.setSizeBytes(2L * 1024 * 1024);

        assertThatThrownBy(() -> controller.requestUploadUrl(RECORD_ID, "logo", tooBig))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceeds maximum size of 1MB");
    }

    /**
     * The declared size is a claim; the bucket's measurement is not. An upload that understates
     * its size at step 1 has to be caught once the object exists — this endpoint previously
     * checked only that the object was non-empty, so a record file field had no size limit at
     * all.
     */
    @Test
    void confirmEnforcesTheSizeCapAgainstTheStoredObject() {
        when(metadataClient.getSchema("VENUE")).thenReturn(
                new MetadataClient.ObjectTypeSchemaDto("VENUE", List.of(
                        fieldWithRules("logo", "IMAGE", Map.of("maxSizeMb", 1)))));

        String key = StorageKeys.buildPending("record", RECORD_ID, "f.png");
        when(storage.stat(key)).thenReturn(Optional.of(
                new ObjectMeta(key, 5L * 1024 * 1024, "image/png", Instant.now())));
        when(storage.readRange(eq(key), anyInt())).thenReturn(REAL_PNG);

        assertThatThrownBy(() -> controller.confirmUpload(RECORD_ID, "logo", confirmRequest(key)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceeds maximum size");
        verify(storage).delete(key);
        verify(repository, never()).save(any());
    }

    /** A malformed rule must fall back to the built-in default rather than taking uploads down. */
    @Test
    void malformedValidationRulesFallBackToTheDefaultPolicy() {
        when(metadataClient.getSchema("VENUE")).thenReturn(
                new MetadataClient.ObjectTypeSchemaDto("VENUE", List.of(
                        fieldWithRules("logo", "IMAGE",
                                Map.of("allowedMimeTypes", "not-a-list", "maxSizeMb", "huge")))));

        assertThat(controller.requestUploadUrl(RECORD_ID, "logo",
                urlRequest("x.png", "image/png")).getBody()).isNotNull();
    }

    private static MetadataClient.FieldSchemaDto fieldWithRules(
            String name, String type, Map<String, Object> rules) {
        return new MetadataClient.FieldSchemaDto(
                name, name, type, false, false, true, true, false, null, rules, null, null);
    }
}
