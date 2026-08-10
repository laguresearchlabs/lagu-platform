package com.lagu.platform.document.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.document.domain.Document;
import com.lagu.platform.document.domain.DocumentRepository;
import com.lagu.platform.document.event.DocumentEventPublisher;
import com.lagu.platform.document.dto.ConfirmUploadRequest;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.storage.MediaIngest;
import com.lagu.platform.storage.MediaScanner;
import com.lagu.platform.storage.ObjectMeta;
import com.lagu.platform.storage.StorageKeys;
import com.lagu.platform.storage.StorageProperties;
import com.lagu.platform.storage.StorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for two review findings still live in this service:
 *  1. findForContext() scoped reads by org only — any employee could read a colleague's identity
 *     document (and its signed fileUrl) within the same org, since DefaultPermissionEvaluator's
 *     DOCUMENT:READ grant is role-shaped ("any authenticated user"), not ownership-shaped.
 *  2. Upload validation trusted the client-supplied Content-Type/extension with no check that the
 *     bytes actually matched — a renamed executable sent as "x.pdf" with
 *     Content-Type: application/pdf passed both checks.
 *
 * <p>(2) now runs at confirm time against the stored object rather than against a multipart
 * body, because uploads go directly to the bucket and the service never sees the payload.
 */
class DocumentServiceTest {

    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final DocumentEventPublisher publisher = mock(DocumentEventPublisher.class);
    private final DocumentTypeRegistry docTypeRegistry = mock(DocumentTypeRegistry.class);

    /** Real, not mocked — the domain prefix drives key ownership checks under test. */
    private final StorageProperties storageProperties = new StorageProperties();

    /** The real ingest pipeline over the mocked bucket, scanning stubbed clean — mocking it out
     *  would remove the sniffing and cleanup behaviour these tests exist to cover. */
    private final MediaIngest mediaIngest =
            new MediaIngest(storage, (content, key) -> MediaScanner.ScanResult.ok());

    private final DocumentService service = new DocumentService(
            repository, storage, storageProperties, publisher, docTypeRegistry, mediaIngest);

    {
        storageProperties.setDomain("document");
    }

    private MockedStatic<GatewayHeaderFilter> gatewayMock;

    private void asCaller(PlatformSecurityContext ctx) {
        gatewayMock = Mockito.mockStatic(GatewayHeaderFilter.class);
        gatewayMock.when(GatewayHeaderFilter::current).thenReturn(ctx);
    }

    @AfterEach
    void tearDown() {
        if (gatewayMock != null) gatewayMock.close();
    }

    private static PlatformSecurityContext ctx(UUID userId, UUID tenantId, String... roles) {
        return PlatformSecurityContext.builder().userId(userId).tenantId(tenantId).roles(Set.of(roles)).build();
    }

    private static Document doc(UUID id, UUID tenantId, UUID ownerUserId) {
        Document d = new Document();
        d.setId(id);
        d.setTenantId(tenantId);
        d.setUserId(ownerUserId);
        d.setStatus("UPLOADED");
        return d;
    }

    // ---- ownership check ----

    @Test
    void ownerCanReadTheirOwnDocument() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        when(repository.findByIdAndTenantId(docId, tenantId)).thenReturn(Optional.of(doc(docId, tenantId, userId)));
        asCaller(ctx(userId, tenantId));

        assertThat(service.getById(docId)).isNotNull(); // must not throw
    }

    @Test
    void colleagueInSameOrgCannotReadAnotherUsersDocument() {
        // The exact bug: same org, different user, no special role — previously allowed.
        UUID tenantId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        UUID colleagueUserId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        when(repository.findByIdAndTenantId(docId, tenantId)).thenReturn(Optional.of(doc(docId, tenantId, ownerUserId)));
        asCaller(ctx(colleagueUserId, tenantId, "USER"));

        assertThatThrownBy(() -> service.getById(docId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reviewerInSameOrgCanReadAnotherUsersDocument() {
        UUID tenantId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        UUID reviewerUserId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        when(repository.findByIdAndTenantId(docId, tenantId)).thenReturn(Optional.of(doc(docId, tenantId, ownerUserId)));
        asCaller(ctx(reviewerUserId, tenantId, "ORG_MANAGER"));

        assertThat(service.getById(docId)).isNotNull(); // must not throw
    }

    @Test
    void platformAdminCanReadAnyDocumentRegardlessOfOrg() {
        UUID docId = UUID.randomUUID();
        when(repository.findById(docId)).thenReturn(Optional.of(doc(docId, UUID.randomUUID(), UUID.randomUUID())));
        asCaller(ctx(UUID.randomUUID(), UUID.randomUUID(), "PLATFORM_ADMIN"));

        assertThat(service.getById(docId)).isNotNull();
    }

    // ---- magic-byte file validation ----

    private static final byte[] REAL_PDF_HEADER = "%PDF-1.4\n".getBytes();
    // The full 8-byte PNG signature. Its trailing CRLF/EOF bytes are part of what the sniffer
    // checks — they catch a PNG mangled by a text-mode transfer.
    private static final byte[] REAL_PNG_HEADER =
            new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] FAKE_EXE_HEADER = new byte[]{'M', 'Z', 0x00, 0x01}; // Windows PE header

    private void stubValidDocType() {
        // validCodes(listingType) — these tests exercise file validation, not listing-type
        // routing, and a null listingType resolves to the generic set.
        when(docTypeRegistry.validCodes(any())).thenReturn(Set.of("PASSPORT_PHOTO"));
        // A type with no admin configuration of its own gets the platform default.
        when(docTypeRegistry.policyFor(any())).thenReturn(DocumentTypeRegistry.DEFAULT_POLICY);
    }

    /**
     * Puts {@code content} behind {@code key} so stat/readRange behave like a real bucket.
     *
     * <p>{@code contentType} is what the bucket would report, which in production is the type
     * bound into the upload signature — so it is the one the service treats as authoritative,
     * and the one whose bytes get sniffed.
     */
    private void stubStoredObject(String key, String contentType, byte[] content) {
        when(storage.stat(key)).thenReturn(
                Optional.of(new ObjectMeta(key, content.length, contentType, Instant.now())));
        when(storage.readRange(eq(key), anyInt())).thenAnswer(inv ->
                Arrays.copyOf(content, Math.min(content.length, (int) inv.getArgument(1))));
        when(storage.readAll(eq(key), anyLong())).thenReturn(content);
        when(storage.presignDownload(anyString(), any())).thenReturn("https://bucket/get");
    }

    /** Uploads land under {@code pending/} and are promoted by confirm. */
    private static String keyFor(UUID userId, String fileName) {
        return StorageKeys.buildPending("document", userId, fileName);
    }

    private static ConfirmUploadRequest confirm(String key, String fileName, String contentType) {
        ConfirmUploadRequest req = new ConfirmUploadRequest();
        req.setKey(key);
        req.setDocumentType("PASSPORT_PHOTO");
        req.setFileName(fileName);
        req.setContentType(contentType);
        return req;
    }

    @Test
    void confirmRejectsExecutableRenamedWithPdfContentTypeAndExtension() {
        stubValidDocType();
        UUID userId = UUID.randomUUID();
        asCaller(ctx(userId, UUID.randomUUID(), "USER"));
        String key = keyFor(userId, "resume.pdf");
        stubStoredObject(key, "application/pdf", FAKE_EXE_HEADER);

        assertThatThrownBy(() -> service.confirmUpload(confirm(key, "resume.pdf", "application/pdf")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not match its declared type");
        verify(repository, never()).save(any());
        // The object is already in the bucket, so rejecting it has to remove it too —
        // otherwise it lingers unreferenced but still readable via a signed URL.
        verify(storage).delete(key);
    }

    @Test
    void confirmAcceptsARealPdf() {
        stubValidDocType();
        UUID userId = UUID.randomUUID();
        asCaller(ctx(userId, UUID.randomUUID(), "USER"));
        String key = keyFor(userId, "id.pdf");
        stubStoredObject(key, "application/pdf", REAL_PDF_HEADER);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.confirmUpload(confirm(key, "id.pdf", "application/pdf")); // must not throw
        verify(repository).save(any());
    }

    @Test
    void confirmAcceptsARealPng() {
        stubValidDocType();
        UUID userId = UUID.randomUUID();
        asCaller(ctx(userId, UUID.randomUUID(), "USER"));
        String key = keyFor(userId, "id.png");
        stubStoredObject(key, "image/png", REAL_PNG_HEADER);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.confirmUpload(confirm(key, "id.png", "image/png")); // must not throw
    }

    @Test
    void confirmRejectsPngContentTypeWithPdfBytes() {
        stubValidDocType();
        UUID userId = UUID.randomUUID();
        asCaller(ctx(userId, UUID.randomUUID(), "USER"));
        String key = keyFor(userId, "id.png");
        stubStoredObject(key, "image/png", REAL_PDF_HEADER);

        assertThatThrownBy(() -> service.confirmUpload(confirm(key, "id.png", "image/png")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void confirmRejectsAKeyBelongingToAnotherUser() {
        stubValidDocType();
        asCaller(ctx(UUID.randomUUID(), UUID.randomUUID(), "USER"));
        // A well-formed key under the right domain, but a different uploader.
        String foreignKey = keyFor(UUID.randomUUID(), "id.pdf");
        stubStoredObject(foreignKey, "application/pdf", REAL_PDF_HEADER);

        assertThatThrownBy(() -> service.confirmUpload(confirm(foreignKey, "id.pdf", "application/pdf")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not belong to this user");
        verify(repository, never()).save(any());
    }

    @Test
    void confirmRejectsAnObjectThatWasNeverUploaded() {
        stubValidDocType();
        UUID userId = UUID.randomUUID();
        asCaller(ctx(userId, UUID.randomUUID(), "USER"));
        String key = keyFor(userId, "id.pdf");
        when(storage.stat(key)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmUpload(confirm(key, "id.pdf", "application/pdf")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No uploaded object found");
        verify(repository, never()).save(any());
    }

    @Test
    void confirmRejectsAnObjectLargerThanTheDeclaredLimit() {
        // The size that matters is the bucket's, not the one declared at step 1 — a client can
        // request a URL for a 1KB file and then PUT 50MB through it.
        stubValidDocType();
        UUID userId = UUID.randomUUID();
        asCaller(ctx(userId, UUID.randomUUID(), "USER"));
        String key = keyFor(userId, "id.pdf");
        when(storage.stat(key)).thenReturn(Optional.of(
                new ObjectMeta(key, 21L * 1024 * 1024, "application/pdf", Instant.now())));

        assertThatThrownBy(() -> service.confirmUpload(confirm(key, "id.pdf", "application/pdf")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceeds maximum size");
        verify(repository, never()).save(any());
        verify(storage).delete(key);
    }

    // ---- admin-configured limits ----

    /**
     * schema-registry has stored {@code allowed_mime_types} and {@code max_size_mb} per document
     * type since it was built, and the admin API has always accepted edits to them — while this
     * service enforced compiled-in constants and ignored the configuration entirely. So the
     * screen saved, and nothing changed. These two cover the wiring that closes that gap.
     */
    @Test
    void adminConfiguredMimeTypesAreEnforced() {
        when(docTypeRegistry.validCodes(any())).thenReturn(Set.of("PASSPORT_PHOTO"));
        when(docTypeRegistry.policyFor("PASSPORT_PHOTO")).thenReturn(
                DocumentTypeRegistry.DEFAULT_POLICY.overriddenBy(List.of("image/jpeg"), null));

        UUID userId = UUID.randomUUID();
        asCaller(ctx(userId, UUID.randomUUID(), "USER"));
        String key = keyFor(userId, "id.pdf");
        stubStoredObject(key, "application/pdf", REAL_PDF_HEADER);

        // A real PDF, which the platform default would have accepted — refused because this
        // document type is configured for photographs only.
        assertThatThrownBy(() -> service.confirmUpload(confirm(key, "id.pdf", "application/pdf")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unsupported file type");
        verify(repository, never()).save(any());
        verify(storage).delete(key);
    }

    @Test
    void adminConfiguredSizeCapIsEnforced() {
        when(docTypeRegistry.validCodes(any())).thenReturn(Set.of("PASSPORT_PHOTO"));
        when(docTypeRegistry.policyFor("PASSPORT_PHOTO")).thenReturn(
                DocumentTypeRegistry.DEFAULT_POLICY.overriddenBy(null, 1));

        UUID userId = UUID.randomUUID();
        asCaller(ctx(userId, UUID.randomUUID(), "USER"));
        String key = keyFor(userId, "id.png");
        when(storage.stat(key)).thenReturn(Optional.of(
                new ObjectMeta(key, 3L * 1024 * 1024, "image/png", Instant.now())));

        // 3MB: fine under the 20MB platform default, over the 1MB an admin set for this type.
        assertThatThrownBy(() -> service.confirmUpload(confirm(key, "id.png", "image/png")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceeds maximum size of 1MB");
        verify(storage).delete(key);
    }
}
