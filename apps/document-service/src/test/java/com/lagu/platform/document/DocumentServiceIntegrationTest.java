//package com.lagu.platform.document;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.lagu.platform.document.domain.DocumentRepository;
//import com.lagu.platform.document.service.DocumentStorageService;
//import com.lagu.platform.events.PlatformTopics;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
//import org.springframework.boot.test.web.server.LocalServerPort;
//import org.springframework.core.io.ByteArrayResource;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.kafka.test.context.EmbeddedKafka;
//import org.springframework.test.context.DynamicPropertyRegistry;
//import org.springframework.test.context.DynamicPropertySource;
//import org.springframework.test.context.bean.override.mockito.MockitoBean;
//import org.springframework.util.LinkedMultiValueMap;
//import org.springframework.util.MultiValueMap;
//import org.springframework.web.client.HttpClientErrorException;
//import org.springframework.web.client.RestClient;
//import org.testcontainers.containers.PostgreSQLContainer;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//
//import java.util.List;
//import java.util.Map;
//import java.util.UUID;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.assertj.core.api.Assertions.assertThatThrownBy;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.ArgumentMatchers.anyString;
//import static org.mockito.Mockito.when;
//
//@Testcontainers
//@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
//@EmbeddedKafka(partitions = 1, topics = {
//        PlatformTopics.DOCUMENT_EVENTS,
//        PlatformTopics.DOCUMENT_EVENTS + ".DLT"
//})
//class DocumentServiceIntegrationTest {
//
//    @Container
//    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
//            .withDatabaseName("platformdb")
//            .withUsername("platform")
//            .withPassword("platform");
//
//    @DynamicPropertySource
//    static void configure(DynamicPropertyRegistry r) {
//        r.add("spring.datasource.url",      () -> postgres.getJdbcUrl() + "&currentSchema=documents");
//        r.add("spring.datasource.username", postgres::getUsername);
//        r.add("spring.datasource.password", postgres::getPassword);
//        r.add("spring.flyway.url",          postgres::getJdbcUrl);
//        r.add("spring.flyway.user",         postgres::getUsername);
//        r.add("spring.flyway.password",     postgres::getPassword);
//    }
//
//    /** Stub out the image-service proxy — we test document lifecycle, not file storage. */
//    @MockitoBean
//    DocumentStorageService documentStorageService;
//
//    @Autowired DocumentRepository documentRepository;
//    @Autowired ObjectMapper json;
//
//    @LocalServerPort int port;
//
//    static final String USER_ID    = UUID.randomUUID().toString();
//    static final String ORG_ID     = UUID.randomUUID().toString();
//    static final String HR_USER_ID = UUID.randomUUID().toString();
//
//    RestClient userClient;
//    RestClient hrClient;
//
//    @BeforeEach
//    void setUp() {
//        userClient = RestClient.builder()
//                .baseUrl("http://localhost:" + port)
//                .defaultHeader("X-User-Id",    USER_ID)
//                .defaultHeader("X-Org-Id",     ORG_ID)
//                .defaultHeader("X-User-Roles", "ORG_STAFF")
//                .build();
//
//        hrClient = RestClient.builder()
//                .baseUrl("http://localhost:" + port)
//                .defaultHeader("X-User-Id",    HR_USER_ID)
//                .defaultHeader("X-Org-Id",     ORG_ID)
//                .defaultHeader("X-User-Roles", "ORG_MANAGER")
//                .build();
//
//        when(documentStorageService.upload(any(), any(), anyString()))
//                .thenAnswer(inv -> "https://storage.example.com/" + UUID.randomUUID() + ".pdf");
//    }
//
//    // ── upload ────────────────────────────────────────────────────────────────
//
//    @Test
//    void uploadResume_returns201() {
//        ResponseEntity<Map> resp = uploadFile(userClient, "RESUME", null);
//        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
//
//        Map<String, Object> data = extractData(resp);
//        assertThat(data.get("documentType")).isEqualTo("RESUME");
//        assertThat(data.get("status")).isEqualTo("UPLOADED");
//        assertThat(data.get("id")).isNotNull();
//    }
//
//    @Test
//    void uploadIdentityProof_withSubType_returns201() {
//        ResponseEntity<Map> resp = uploadFile(userClient, "IDENTITY_PROOF", "AADHAAR");
//        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
//
//        Map<String, Object> data = extractData(resp);
//        assertThat(data.get("documentType")).isEqualTo("IDENTITY_PROOF");
//        assertThat(data.get("identitySubType")).isEqualTo("AADHAAR");
//    }
//
//    @Test
//    void uploadIdentityProof_missingSubType_returns400() {
//        assertThatThrownBy(() -> uploadFile(userClient, "IDENTITY_PROOF", null))
//                .isInstanceOf(HttpClientErrorException.class)
//                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
//                        .isEqualTo(HttpStatus.BAD_REQUEST));
//    }
//
//    @Test
//    void uploadInvalidType_returns400() {
//        assertThatThrownBy(() -> uploadFile(userClient, "UNKNOWN_TYPE", null))
//                .isInstanceOf(HttpClientErrorException.class)
//                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
//                        .isEqualTo(HttpStatus.BAD_REQUEST));
//    }
//
//    // ── getById / list ────────────────────────────────────────────────────────
//
//    @Test
//    void getById_returnsDocument() {
//        String id = extractId(uploadFile(userClient, "RESUME", null));
//
//        Map<String, Object> data = extractData(userClient.get()
//                .uri("/api/v1/documents/" + id)
//                .retrieve().toEntity(Map.class));
//        assertThat(data.get("id")).isEqualTo(id);
//        assertThat(data.get("documentType")).isEqualTo("RESUME");
//    }
//
//    @Test
//    void listMyDocuments_includesUploaded() {
//        uploadFile(userClient, "RESUME", null);
//        uploadFile(userClient, "PHOTOGRAPH", null);
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> docs = (List<Map<String, Object>>)
//                userClient.get().uri("/api/v1/documents").retrieve().toEntity(Map.class)
//                        .getBody().get("data");
//        assertThat(docs).hasSizeGreaterThanOrEqualTo(2);
//    }
//
//    // ── submission status ─────────────────────────────────────────────────────
//
//    @Test
//    void submissionStatus_missingRequired_notAllSubmitted() {
//        @SuppressWarnings("unchecked")
//        Map<String, Object> status = extractData(userClient.get()
//                .uri("/api/v1/documents/submission-status")
//                .retrieve().toEntity(Map.class));
//
//        assertThat(status.get("allRequiredSubmitted")).isEqualTo(false);
//        assertThat(status.get("allRequiredVerified")).isEqualTo(false);
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> docs = (List<Map<String, Object>>) status.get("documents");
//        Map<String, Object> resumeStatus = docs.stream()
//                .filter(d -> "RESUME".equals(d.get("documentType")))
//                .findFirst().orElseThrow();
//        assertThat(resumeStatus.get("status")).isEqualTo("MISSING");
//        assertThat(resumeStatus.get("required")).isEqualTo(true);
//    }
//
//    @Test
//    void submissionStatus_afterUpload_showsUploaded() {
//        uploadFile(userClient, "RESUME", null);
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> docs = (List<Map<String, Object>>)
//                extractData(userClient.get().uri("/api/v1/documents/submission-status")
//                        .retrieve().toEntity(Map.class)).get("documents");
//
//        Map<String, Object> resumeStatus = docs.stream()
//                .filter(d -> "RESUME".equals(d.get("documentType")))
//                .findFirst().orElseThrow();
//        assertThat(resumeStatus.get("status")).isEqualTo("UPLOADED");
//        assertThat(resumeStatus.get("documentId")).isNotNull();
//    }
//
//    // ── HR review flow ────────────────────────────────────────────────────────
//
//    @Test
//    void hrReviewFlow_uploadThenVerify() {
//        String id = extractId(uploadFile(userClient, "RESUME", null));
//
//        // HR claims it
//        Map<String, Object> underReview = extractData(hrClient.post()
//                .uri("/api/v1/documents/" + id + "/review")
//                .retrieve().toEntity(Map.class));
//        assertThat(underReview.get("status")).isEqualTo("UNDER_REVIEW");
//
//        // HR verifies it
//        Map<String, Object> verified = extractData(hrClient.post()
//                .uri("/api/v1/documents/" + id + "/verify")
//                .retrieve().toEntity(Map.class));
//        assertThat(verified.get("status")).isEqualTo("VERIFIED");
//    }
//
//    @Test
//    void hrReviewFlow_reject_withReason() {
//        String id = extractId(uploadFile(userClient, "PHOTOGRAPH", null));
//
//        hrClient.post().uri("/api/v1/documents/" + id + "/review")
//                .retrieve().toBodilessEntity();
//
//        Map<String, Object> rejected = extractData(hrClient.post()
//                .uri("/api/v1/documents/" + id + "/reject")
//                .contentType(MediaType.APPLICATION_JSON)
//                .body(Map.of("rejectionReason", "Photo too blurry"))
//                .retrieve().toEntity(Map.class));
//
//        assertThat(rejected.get("status")).isEqualTo("REJECTED");
//        assertThat(rejected.get("rejectionReason")).isEqualTo("Photo too blurry");
//    }
//
//    @Test
//    void pendingReview_listsPendingDocuments() {
//        uploadFile(userClient, "RESUME", null);
//
//        @SuppressWarnings("unchecked")
//        Map<String, Object> page = extractData(hrClient.get()
//                .uri("/api/v1/documents/pending-review")
//                .retrieve().toEntity(Map.class));
//        assertThat(((Number) page.get("total")).intValue()).isGreaterThanOrEqualTo(1);
//    }
//
//    @Test
//    void staffUser_cannotAccessPendingReview() {
//        assertThatThrownBy(() -> userClient.get()
//                .uri("/api/v1/documents/pending-review")
//                .retrieve().toBodilessEntity())
//                .isInstanceOf(HttpClientErrorException.class)
//                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
//                        .isEqualTo(HttpStatus.FORBIDDEN));
//    }
//
//    // ── submission status after verify ────────────────────────────────────────
//
//    @Test
//    void submissionStatus_afterVerify_reflectsVerified() {
//        String id = extractId(uploadFile(userClient, "RESUME", null));
//        hrClient.post().uri("/api/v1/documents/" + id + "/review").retrieve().toBodilessEntity();
//        hrClient.post().uri("/api/v1/documents/" + id + "/verify").retrieve().toBodilessEntity();
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> docs = (List<Map<String, Object>>)
//                extractData(userClient.get().uri("/api/v1/documents/submission-status")
//                        .retrieve().toEntity(Map.class)).get("documents");
//
//        assertThat(docs.stream()
//                .filter(d -> "RESUME".equals(d.get("documentType")))
//                .map(d -> d.get("status"))
//                .findFirst()).hasValue("VERIFIED");
//    }
//
//    // ── helpers ───────────────────────────────────────────────────────────────
//
//    @SuppressWarnings("unchecked")
//    private ResponseEntity<Map> uploadFile(RestClient client, String documentType, String subType) {
//        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
//        form.add("file", new NamedByteArrayResource("test.pdf", "PDF content".getBytes()));
//        form.add("documentType", documentType);
//        if (subType != null) form.add("identitySubType", subType);
//
//        return client.post()
//                .uri("/api/v1/documents")
//                .contentType(MediaType.MULTIPART_FORM_DATA)
//                .body(form)
//                .retrieve()
//                .toEntity(Map.class);
//    }
//
//    @SuppressWarnings("unchecked")
//    private Map<String, Object> extractData(ResponseEntity<Map> resp) {
//        return (Map<String, Object>) resp.getBody().get("data");
//    }
//
//    @SuppressWarnings("unchecked")
//    private String extractId(ResponseEntity<Map> resp) {
//        return (String) extractData(resp).get("id");
//    }
//
//    /** ByteArrayResource with a filename so Spring sets Content-Disposition correctly. */
//    static class NamedByteArrayResource extends ByteArrayResource {
//        private final String filename;
//        NamedByteArrayResource(String filename, byte[] content) {
//            super(content);
//            this.filename = filename;
//        }
//        @Override public String getFilename() { return filename; }
//    }
//}
