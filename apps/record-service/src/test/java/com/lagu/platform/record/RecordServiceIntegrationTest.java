//package com.lagu.platform.record;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.lagu.platform.events.PlatformTopics;
//import com.lagu.platform.record.client.MetadataClient;
//import com.redis.testcontainers.RedisContainer;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
//import org.springframework.test.context.bean.override.mockito.MockitoBean;
//import org.springframework.boot.test.web.server.LocalServerPort;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.kafka.test.context.EmbeddedKafka;
//import org.springframework.test.context.DynamicPropertyRegistry;
//import org.springframework.test.context.DynamicPropertySource;
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
//import static org.mockito.Mockito.when;
//
//@Testcontainers
//@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
//@EmbeddedKafka(partitions = 1, topics = {
//        PlatformTopics.RECORD_EVENTS,
//        PlatformTopics.WORKFLOW_EVENTS,
//        PlatformTopics.RECORD_EVENTS + ".DLT"
//})
//class RecordServiceIntegrationTest {
//
//    @Container
//    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
//            .withDatabaseName("platformdb")
//            .withUsername("platform")
//            .withPassword("platform");
//
//    @Container
//    static final RedisContainer redis = new RedisContainer("redis:7-alpine");
//
//    @DynamicPropertySource
//    static void configure(DynamicPropertyRegistry r) {
//        r.add("spring.datasource.url",      () -> postgres.getJdbcUrl() + "&currentSchema=records");
//        r.add("spring.datasource.username", postgres::getUsername);
//        r.add("spring.datasource.password", postgres::getPassword);
//        r.add("spring.flyway.url",          postgres::getJdbcUrl);
//        r.add("spring.flyway.user",         postgres::getUsername);
//        r.add("spring.flyway.password",     postgres::getPassword);
//        r.add("spring.data.redis.host",     redis::getHost);
//        r.add("spring.data.redis.port",     () -> redis.getMappedPort(6379));
//    }
//
//    @MockitoBean
//    MetadataClient metadataClient;
//
//    @LocalServerPort int port;
//    @Autowired ObjectMapper json;
//
//    static final String USER_ID = UUID.randomUUID().toString();
//    static final String ORG_ID  = UUID.randomUUID().toString();
//
//    static final MetadataClient.ObjectTypeSchemaDto VENUE_SCHEMA =
//            new MetadataClient.ObjectTypeSchemaDto("VENUE", List.of(
//                    new MetadataClient.FieldSchemaDto(
//                            "name", "Name", "TEXT", true, true, true, true, false, null,
//                            Map.of("maxLength", 255), null),
//                    new MetadataClient.FieldSchemaDto(
//                            "capacity", "Capacity", "NUMBER", false, false, true, true, false,
//                            null, null, null)
//            ));
//
//    RestClient client;
//
//    @BeforeEach
//    void setup() {
//        client = RestClient.builder()
//                .baseUrl("http://localhost:" + port)
//                .defaultHeader("X-User-Id",    USER_ID)
//                .defaultHeader("X-Org-Id",     ORG_ID)
//                .defaultHeader("X-User-Roles", "ORG_MANAGER")
//                .build();
//        when(metadataClient.getSchema("VENUE")).thenReturn(VENUE_SCHEMA);
//    }
//
//    // ── tests ─────────────────────────────────────────────────────────────────
//
//    @Test
//    void createRecord_withValidData_returns201() {
//        ResponseEntity<Map> resp = post("/api/v1/records", Map.of(
//                "objectType", "VENUE",
//                "data", Map.of("name", "Grand Hall", "capacity", 500)
//        ));
//        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
//
//        @SuppressWarnings("unchecked")
//        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
//        assertThat(data.get("objectType")).isEqualTo("VENUE");
//        assertThat(data.get("status")).isEqualTo("DRAFT");
//        assertThat(data.get("id")).isNotNull();
//    }
//
//    @Test
//    void createRecord_missingRequiredField_returns400() {
//        assertThatThrownBy(() -> post("/api/v1/records", Map.of(
//                "objectType", "VENUE",
//                "data", Map.of("capacity", 200)   // missing required "name"
//        ))).isInstanceOf(HttpClientErrorException.class)
//                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
//                        .isEqualTo(HttpStatus.BAD_REQUEST));
//    }
//
//    @Test
//    void getById_existingRecord_returns200() {
//        String id = extractId(post("/api/v1/records", Map.of(
//                "objectType", "VENUE",
//                "data", Map.of("name", "Test Venue")
//        )));
//
//        ResponseEntity<Map> resp = get("/api/v1/records/" + id);
//        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
//
//        @SuppressWarnings("unchecked")
//        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
//        assertThat(data.get("id")).isEqualTo(id);
//    }
//
//    @Test
//    void updateRecord_replacesData() {
//        String id = extractId(post("/api/v1/records", Map.of(
//                "objectType", "VENUE",
//                "data", Map.of("name", "Old Name")
//        )));
//
//        ResponseEntity<Map> resp = put("/api/v1/records/" + id, Map.of(
//                "data", Map.of("name", "New Name", "capacity", 300)
//        ));
//        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
//
//        @SuppressWarnings("unchecked")
//        Map<String, Object> outer = (Map<String, Object>) resp.getBody().get("data");
//        @SuppressWarnings("unchecked")
//        Map<String, Object> data = (Map<String, Object>) outer.get("data");
//        assertThat(data.get("name")).isEqualTo("New Name");
//    }
//
//    @Test
//    void patchRecord_mergesData() {
//        String id = extractId(post("/api/v1/records", Map.of(
//                "objectType", "VENUE",
//                "data", Map.of("name", "Hall", "capacity", 100)
//        )));
//
//        ResponseEntity<Map> resp = patch("/api/v1/records/" + id,
//                Map.of("name", "Updated Hall"));
//        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
//
//        @SuppressWarnings("unchecked")
//        Map<String, Object> outer = (Map<String, Object>) resp.getBody().get("data");
//        @SuppressWarnings("unchecked")
//        Map<String, Object> data = (Map<String, Object>) outer.get("data");
//        assertThat(data.get("name")).isEqualTo("Updated Hall");
//        assertThat(data.get("capacity")).isEqualTo(100);   // preserved from original
//    }
//
//    @Test
//    void deleteRecord_softDeletes() {
//        String id = extractId(post("/api/v1/records", Map.of(
//                "objectType", "VENUE",
//                "data", Map.of("name", "To Delete")
//        )));
//
//        client.delete().uri("/api/v1/records/" + id).retrieve().toBodilessEntity();
//
//        @SuppressWarnings("unchecked")
//        Map<String, Object> data = (Map<String, Object>) get("/api/v1/records/" + id)
//                .getBody().get("data");
//        assertThat(data.get("status")).isEqualTo("DELETED");
//    }
//
//    @Test
//    void requestTransition_returns200() {
//        String id = extractId(post("/api/v1/records", Map.of(
//                "objectType", "VENUE",
//                "data", Map.of("name", "Transition Venue")
//        )));
//
//        ResponseEntity<Map> resp = post("/api/v1/records/" + id + "/status", Map.of(
//                "trigger", "SUBMIT",
//                "comment", "Ready for review"
//        ));
//        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
//    }
//
//    @Test
//    void listRecords_byObjectType_returns200() {
//        post("/api/v1/records", Map.of("objectType", "VENUE", "data", Map.of("name", "Venue A")));
//        post("/api/v1/records", Map.of("objectType", "VENUE", "data", Map.of("name", "Venue B")));
//
//        ResponseEntity<Map> resp = get("/api/v1/records?objectType=VENUE");
//        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
//
//        @SuppressWarnings("unchecked")
//        Map<String, Object> page = (Map<String, Object>) resp.getBody().get("data");
//        assertThat(((Number) page.get("total")).intValue()).isGreaterThanOrEqualTo(2);
//    }
//
//    // ── helpers ───────────────────────────────────────────────────────────────
//
//    @SuppressWarnings("unchecked")
//    private ResponseEntity<Map> post(String uri, Map<String, Object> body) {
//        return client.post().uri(uri)
//                .contentType(MediaType.APPLICATION_JSON)
//                .body(body).retrieve().toEntity(Map.class);
//    }
//
//    @SuppressWarnings("unchecked")
//    private ResponseEntity<Map> get(String uri) {
//        return client.get().uri(uri).retrieve().toEntity(Map.class);
//    }
//
//    @SuppressWarnings("unchecked")
//    private ResponseEntity<Map> put(String uri, Map<String, Object> body) {
//        return client.put().uri(uri)
//                .contentType(MediaType.APPLICATION_JSON)
//                .body(body).retrieve().toEntity(Map.class);
//    }
//
//    @SuppressWarnings("unchecked")
//    private ResponseEntity<Map> patch(String uri, Map<String, Object> body) {
//        return client.patch().uri(uri)
//                .contentType(MediaType.APPLICATION_JSON)
//                .body(body).retrieve().toEntity(Map.class);
//    }
//
//    @SuppressWarnings("unchecked")
//    private String extractId(ResponseEntity<Map> resp) {
//        return (String) ((Map<String, Object>) resp.getBody().get("data")).get("id");
//    }
//}
