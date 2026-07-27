package com.lagu.platform.document.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.document.domain.Document;
import com.lagu.platform.document.domain.DocumentRepository;
import com.lagu.platform.document.event.DocumentEventPublisher;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for two review findings still live in this service:
 *  1. findForContext() scoped reads by org only — any employee could read a colleague's identity
 *     document (and its signed fileUrl) within the same org, since DefaultPermissionEvaluator's
 *     DOCUMENT:READ grant is role-shaped ("any authenticated user"), not ownership-shaped.
 *  2. validateFile() trusted the client-supplied Content-Type/extension with no check that the
 *     bytes actually matched — a renamed executable sent as "x.pdf" with
 *     Content-Type: application/pdf passed both checks.
 */
class DocumentServiceTest {

    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final DocumentStorageService storageService = mock(DocumentStorageService.class);
    private final DocumentEventPublisher publisher = mock(DocumentEventPublisher.class);
    private final DocumentTypeRegistry docTypeRegistry = mock(DocumentTypeRegistry.class);

    private final DocumentService service =
            new DocumentService(repository, storageService, publisher, docTypeRegistry);

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
    private static final byte[] REAL_PNG_HEADER = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n'};
    private static final byte[] FAKE_EXE_HEADER = new byte[]{'M', 'Z', 0x00, 0x01}; // Windows PE header

    private void stubValidDocType() {
        when(docTypeRegistry.validCodes()).thenReturn(Set.of("PASSPORT_PHOTO"));
    }

    @Test
    void uploadRejectsExecutableRenamedWithPdfContentTypeAndExtension() {
        stubValidDocType();
        asCaller(ctx(UUID.randomUUID(), UUID.randomUUID(), "USER"));
        MockMultipartFile fakePdf = new MockMultipartFile(
                "file", "resume.pdf", "application/pdf", FAKE_EXE_HEADER);

        assertThatThrownBy(() -> service.upload(fakePdf, "PASSPORT_PHOTO", null, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not match its declared type");
        verifyNoInteractions(storageService);
    }

    @Test
    void uploadAcceptsARealPdf() {
        stubValidDocType();
        asCaller(ctx(UUID.randomUUID(), UUID.randomUUID(), "USER"));
        when(storageService.upload(any(), any(), any())).thenReturn("https://cdn/doc.pdf");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile realPdf = new MockMultipartFile(
                "file", "id.pdf", "application/pdf", REAL_PDF_HEADER);

        service.upload(realPdf, "PASSPORT_PHOTO", null, null); // must not throw
        verify(storageService).upload(any(), any(), any());
    }

    @Test
    void uploadAcceptsARealPng() {
        stubValidDocType();
        asCaller(ctx(UUID.randomUUID(), UUID.randomUUID(), "USER"));
        when(storageService.upload(any(), any(), any())).thenReturn("https://cdn/doc.png");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        MockMultipartFile realPng = new MockMultipartFile(
                "file", "id.png", "image/png", REAL_PNG_HEADER);

        service.upload(realPng, "PASSPORT_PHOTO", null, null); // must not throw
    }

    @Test
    void uploadRejectsPngContentTypeWithPdfBytes() {
        stubValidDocType();
        asCaller(ctx(UUID.randomUUID(), UUID.randomUUID(), "USER"));
        MockMultipartFile mismatched = new MockMultipartFile(
                "file", "id.png", "image/png", REAL_PDF_HEADER);

        assertThatThrownBy(() -> service.upload(mismatched, "PASSPORT_PHOTO", null, null))
                .isInstanceOf(ValidationException.class);
    }
}
