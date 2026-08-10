package com.lagu.platform.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.document.domain.DocumentRepository;
import com.lagu.platform.document.service.DocumentTypeRegistry;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.storage.ObjectMeta;
import com.lagu.platform.storage.PresignedUpload;
import com.lagu.platform.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {
        PlatformTopics.DOCUMENT_EVENTS,
        PlatformTopics.DOCUMENT_EVENTS + ".DLT"
})
class DocumentServiceIntegrationTest {

    static final String TEST_GATEWAY_SECRET = "integration-test-shared-secret";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("platformdb")
            .withUsername("platform")
            .withPassword("platform");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        // ?TimeZone=UTC — see RecordServiceIntegrationTest for why this host needs it.
        r.add("spring.datasource.url",      () -> postgres.getJdbcUrl() + "?TimeZone=UTC");
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.flyway.url",          () -> postgres.getJdbcUrl() + "?TimeZone=UTC");
        r.add("spring.flyway.user",         postgres::getUsername);
        r.add("spring.flyway.password",     postgres::getPassword);
        r.add("platform.gateway.shared-secret", () -> TEST_GATEWAY_SECRET);
        // Neither backend configuration activates ("gcs" and "s3" are the only values that
        // match), so StorageConfig never tries to resolve Application Default Credentials —
        // which would fail outright in CI. Only StorageProperties and the mock below exist.
        r.add("platform.storage.provider", () -> "none");
    }

    /** Stub the storage backend — we test document lifecycle, not bucket I/O. Backed by
     *  {@link #storedBytes} so magic-byte checks at confirm time see real content. */
    @MockitoBean
    StorageService storage;

    /** What the "uploaded" object contains. Defaults to a valid PDF; a test that needs a
     *  mismatch overwrites it before calling the upload helper. */
    byte[] storedBytes;

    /** Stub instead of relying on DocumentTypeRegistry's real schema-registry-unreachable
     *  fallback list, which uses "HR_IDENTITY_PROOF" — DocumentService's identitySubType
     *  requirement special-cases the literal string "IDENTITY_PROOF", so this decouples the
     *  test from that fallback's exact contents. */
    @MockitoBean
    DocumentTypeRegistry documentTypeRegistry;

    @Autowired DocumentRepository documentRepository;
    @Autowired ObjectMapper json;

    @LocalServerPort int port;

    static final String USER_ID    = UUID.randomUUID().toString();
    static final String TENANT_ID     = UUID.randomUUID().toString();
    static final String HR_USER_ID = UUID.randomUUID().toString();

    RestClient userClient;
    RestClient hrClient;

    @BeforeEach
    void setUp() {
        userClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id",    USER_ID)
                .defaultHeader("X-Tenant-Id",     TENANT_ID)
                .defaultHeader("X-User-Roles", "ORG_STAFF")
                .defaultHeader("X-Platform-Gateway-Secret", TEST_GATEWAY_SECRET)
                .build();

        hrClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id",    HR_USER_ID)
                .defaultHeader("X-Tenant-Id",     TENANT_ID)
                .defaultHeader("X-User-Roles", "ORG_MANAGER")
                .defaultHeader("X-Platform-Gateway-Secret", TEST_GATEWAY_SECRET)
                .build();

        storedBytes = "%PDF-1.4\ntest content".getBytes();

        // Echo the key back so the confirm step sees the same one the service minted, and
        // serve stat/readRange from storedBytes so size and magic-byte checks are real.
        when(storage.presignUpload(anyString(), anyString(), any()))
                .thenAnswer(inv -> new PresignedUpload(
                        "https://bucket.example.com/put/" + inv.getArgument(0),
                        inv.getArgument(0),
                        inv.getArgument(1),
                        Instant.now().plusSeconds(900)));
        when(storage.stat(anyString()))
                .thenAnswer(inv -> Optional.of(new ObjectMeta(
                        inv.getArgument(0), storedBytes.length, "application/pdf", Instant.now())));
        when(storage.readRange(anyString(), anyInt()))
                .thenAnswer(inv -> Arrays.copyOf(
                        storedBytes, Math.min(storedBytes.length, (int) inv.getArgument(1))));
        when(storage.presignDownload(anyString(), any()))
                .thenAnswer(inv -> "https://bucket.example.com/get/" + inv.getArgument(0));
        // validCodes(listingType) — these uploads carry no listingType, so the generic set applies
        when(documentTypeRegistry.validCodes(any()))
                .thenReturn(Set.of("RESUME", "IDENTITY_PROOF", "PHOTOGRAPH"));
        // trailing null listingType == generic/HR document, available in every context;
        // null mime types and 0 max size == no admin override, so the platform default applies
        when(documentTypeRegistry.all()).thenReturn(List.of(
                new DocumentTypeRegistry.DocumentConfig("RESUME", "Resume / CV", true, false, null, null, 0),
                new DocumentTypeRegistry.DocumentConfig("IDENTITY_PROOF", "Identity Proof", true, false, null, null, 0),
                new DocumentTypeRegistry.DocumentConfig("PHOTOGRAPH", "Photograph", false, false, null, null, 0)));
        when(documentTypeRegistry.policyFor(any())).thenReturn(DocumentTypeRegistry.DEFAULT_POLICY);
    }

    // ── upload ────────────────────────────────────────────────────────────────

    @Test
    void uploadResume_returns201() {
        ResponseEntity<Map> resp = uploadFile(userClient, "RESUME", null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> data = extractData(resp);
        assertThat(data.get("documentType")).isEqualTo("RESUME");
        assertThat(data.get("status")).isEqualTo("UPLOADED");
        assertThat(data.get("id")).isNotNull();
    }

    @Test
    void uploadIdentityProof_withSubType_returns201() {
        ResponseEntity<Map> resp = uploadFile(userClient, "IDENTITY_PROOF", "AADHAAR");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> data = extractData(resp);
        assertThat(data.get("documentType")).isEqualTo("IDENTITY_PROOF");
        assertThat(data.get("identitySubType")).isEqualTo("AADHAAR");
    }

    @Test
    void uploadIdentityProof_missingSubType_returns400() {
        assertThatThrownBy(() -> uploadFile(userClient, "IDENTITY_PROOF", null))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void uploadInvalidType_returns400() {
        assertThatThrownBy(() -> uploadFile(userClient, "UNKNOWN_TYPE", null))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void confirmWithBytesNotMatchingDeclaredPdfType_returns400() {
        // Regression coverage for the review's finding: Content-Type/extension were both
        // client-supplied with no check the bytes actually matched. This is a real (renamed
        // executable-shaped) mismatch, not just a wrong extension.
        //
        // Presigned uploads make this check load-bearing in a way it wasn't before: the bytes
        // go straight to the bucket, so step 1 sees nothing but declarations and confirm is
        // the only place the actual content is ever examined.
        storedBytes = new byte[]{'M', 'Z', 0, 1};
        String key = requestUploadUrl(userClient, "RESUME", null, "resume.pdf", "application/pdf");

        assertThatThrownBy(() ->
                confirmUpload(userClient, key, "RESUME", null, "resume.pdf", "application/pdf"))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void confirmWithAnotherUsersKey_returns400() {
        // The key embeds the uploader's id. Without this check a caller could confirm an object
        // someone else uploaded and take ownership of the resulting document record.
        String foreignKey = "document/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "_test.pdf";

        assertThatThrownBy(() ->
                confirmUpload(userClient, foreignKey, "RESUME", null, "test.pdf", "application/pdf"))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void documentUrlIsSignedFreshOnRead_notPersisted() {
        // The defect that motivated storing keys instead of URLs: image-service returned a
        // 10-minute signed URL and callers persisted it, so every reference expired. fileUrl
        // must now be minted per request from the stored key.
        String id = extractId(uploadFile(userClient, "RESUME", null));

        Map<String, Object> data = extractData(userClient.get()
                .uri("/api/v1/documents/" + id)
                .retrieve().toEntity(Map.class));

        assertThat((String) data.get("fileUrl")).startsWith("https://bucket.example.com/get/document/");
        assertThat(documentRepository.findById(UUID.fromString(id)).orElseThrow().getFileKey())
                .startsWith("document/" + USER_ID + "/")
                .doesNotContain("http");
    }

    // ── getById / list ────────────────────────────────────────────────────────

    @Test
    void getById_returnsDocument() {
        String id = extractId(uploadFile(userClient, "RESUME", null));

        Map<String, Object> data = extractData(userClient.get()
                .uri("/api/v1/documents/" + id)
                .retrieve().toEntity(Map.class));
        assertThat(data.get("id")).isEqualTo(id);
        assertThat(data.get("documentType")).isEqualTo("RESUME");
    }

    @Test
    void getById_anotherUsersDocumentInSameOrg_returns404() {
        // Regression coverage for the review's finding: previously scoped by org only — any
        // colleague could read anyone else's uploaded identity document by id.
        String id = extractId(uploadFile(userClient, "RESUME", null));

        RestClient colleagueClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id",    UUID.randomUUID().toString())
                .defaultHeader("X-Tenant-Id",     TENANT_ID)
                .defaultHeader("X-User-Roles", "ORG_STAFF")
                .defaultHeader("X-Platform-Gateway-Secret", TEST_GATEWAY_SECRET)
                .build();

        assertThatThrownBy(() -> colleagueClient.get().uri("/api/v1/documents/" + id)
                .retrieve().toEntity(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getById_reviewerCanReadAnotherUsersDocument() {
        String id = extractId(uploadFile(userClient, "RESUME", null));

        Map<String, Object> data = extractData(hrClient.get()
                .uri("/api/v1/documents/" + id)
                .retrieve().toEntity(Map.class));
        assertThat(data.get("id")).isEqualTo(id);
    }

    @Test
    void listMyDocuments_includesUploaded() {
        uploadFile(userClient, "RESUME", null);
        uploadFile(userClient, "PHOTOGRAPH", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docs = (List<Map<String, Object>>)
                userClient.get().uri("/api/v1/documents").retrieve().toEntity(Map.class)
                        .getBody().get("data");
        assertThat(docs).hasSizeGreaterThanOrEqualTo(2);
    }

    // ── submission status ─────────────────────────────────────────────────────

    @Test
    void submissionStatus_missingRequired_notAllSubmitted() {
        Map<String, Object> status = extractData(userClient.get()
                .uri("/api/v1/documents/submission-status")
                .retrieve().toEntity(Map.class));

        assertThat(status.get("allRequiredSubmitted")).isEqualTo(false);
        assertThat(status.get("allRequiredVerified")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docs = (List<Map<String, Object>>) status.get("documents");
        Map<String, Object> resumeStatus = docs.stream()
                .filter(d -> "RESUME".equals(d.get("documentType")))
                .findFirst().orElseThrow();
        assertThat(resumeStatus.get("status")).isEqualTo("MISSING");
        assertThat(resumeStatus.get("required")).isEqualTo(true);
    }

    @Test
    void submissionStatus_afterUpload_showsUploaded() {
        uploadFile(userClient, "RESUME", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docs = (List<Map<String, Object>>)
                extractData(userClient.get().uri("/api/v1/documents/submission-status")
                        .retrieve().toEntity(Map.class)).get("documents");

        Map<String, Object> resumeStatus = docs.stream()
                .filter(d -> "RESUME".equals(d.get("documentType")))
                .findFirst().orElseThrow();
        assertThat(resumeStatus.get("status")).isEqualTo("UPLOADED");
        assertThat(resumeStatus.get("documentId")).isNotNull();
    }

    // ── HR review flow ────────────────────────────────────────────────────────

    @Test
    void hrReviewFlow_uploadThenVerify() {
        String id = extractId(uploadFile(userClient, "RESUME", null));

        // HR claims it
        Map<String, Object> underReview = extractData(hrClient.post()
                .uri("/api/v1/documents/" + id + "/review")
                .retrieve().toEntity(Map.class));
        assertThat(underReview.get("status")).isEqualTo("UNDER_REVIEW");

        // HR verifies it
        Map<String, Object> verified = extractData(hrClient.post()
                .uri("/api/v1/documents/" + id + "/verify")
                .retrieve().toEntity(Map.class));
        assertThat(verified.get("status")).isEqualTo("VERIFIED");
    }

    @Test
    void hrReviewFlow_reject_withReason() {
        String id = extractId(uploadFile(userClient, "PHOTOGRAPH", null));

        hrClient.post().uri("/api/v1/documents/" + id + "/review")
                .retrieve().toBodilessEntity();

        Map<String, Object> rejected = extractData(hrClient.post()
                .uri("/api/v1/documents/" + id + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("rejectionReason", "Photo too blurry"))
                .retrieve().toEntity(Map.class));

        assertThat(rejected.get("status")).isEqualTo("REJECTED");
        assertThat(rejected.get("rejectionReason")).isEqualTo("Photo too blurry");
    }

    @Test
    void pendingReview_listsPendingDocuments() {
        uploadFile(userClient, "RESUME", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> page = extractData(hrClient.get()
                .uri("/api/v1/documents/pending-review")
                .retrieve().toEntity(Map.class));
        assertThat(((Number) page.get("total")).intValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void staffUser_cannotAccessPendingReview() {
        assertThatThrownBy(() -> userClient.get()
                .uri("/api/v1/documents/pending-review")
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── submission status after verify ────────────────────────────────────────

    @Test
    void submissionStatus_afterVerify_reflectsVerified() {
        String id = extractId(uploadFile(userClient, "RESUME", null));
        hrClient.post().uri("/api/v1/documents/" + id + "/review").retrieve().toBodilessEntity();
        hrClient.post().uri("/api/v1/documents/" + id + "/verify").retrieve().toBodilessEntity();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docs = (List<Map<String, Object>>)
                extractData(userClient.get().uri("/api/v1/documents/submission-status")
                        .retrieve().toEntity(Map.class)).get("documents");

        assertThat(docs.stream()
                .filter(d -> "RESUME".equals(d.get("documentType")))
                .map(d -> d.get("status"))
                .findFirst()).hasValue("VERIFIED");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Drives the full three-step upload: request a URL, (the PUT is what the mocked backend
     * stands in for), then confirm. Returns the confirm response, which is the one carrying
     * the created document.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> uploadFile(RestClient client, String documentType, String subType) {
        String key = requestUploadUrl(client, documentType, subType, "test.pdf", "application/pdf");
        return confirmUpload(client, key, documentType, subType, "test.pdf", "application/pdf");
    }

    @SuppressWarnings("unchecked")
    private String requestUploadUrl(RestClient client, String documentType, String subType,
                                    String fileName, String contentType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentType", documentType);
        if (subType != null) body.put("identitySubType", subType);
        body.put("fileName", fileName);
        body.put("contentType", contentType);
        body.put("sizeBytes", storedBytes.length);

        Map<String, Object> data = extractData(client.post()
                .uri("/api/v1/documents/upload-url")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class));
        return (String) data.get("key");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> confirmUpload(RestClient client, String key, String documentType,
                                              String subType, String fileName, String contentType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("documentType", documentType);
        if (subType != null) body.put("identitySubType", subType);
        body.put("fileName", fileName);
        body.put("contentType", contentType);

        return client.post()
                .uri("/api/v1/documents/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractData(ResponseEntity<Map> resp) {
        return (Map<String, Object>) resp.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private String extractId(ResponseEntity<Map> resp) {
        return (String) extractData(resp).get("id");
    }

}
