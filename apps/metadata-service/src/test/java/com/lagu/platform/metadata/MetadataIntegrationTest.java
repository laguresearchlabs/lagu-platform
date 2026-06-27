//package com.lagu.platform.metadata;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.redis.testcontainers.RedisContainer;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.test.web.server.LocalServerPort;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.kafka.test.context.EmbeddedKafka;
//import org.springframework.test.context.DynamicPropertyRegistry;
//import org.springframework.test.context.DynamicPropertySource;
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
//
//@Testcontainers
//@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
//@EmbeddedKafka(partitions = 1, topics = {"platform.metadata.changed"})
//class MetadataIntegrationTest {
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
//        r.add("spring.datasource.url",      () -> postgres.getJdbcUrl() + "&currentSchema=metadata");
//        r.add("spring.datasource.username", postgres::getUsername);
//        r.add("spring.datasource.password", postgres::getPassword);
//        r.add("spring.flyway.url",          postgres::getJdbcUrl);
//        r.add("spring.flyway.user",         postgres::getUsername);
//        r.add("spring.flyway.password",     postgres::getPassword);
//        r.add("spring.data.redis.host",     redis::getHost);
//        r.add("spring.data.redis.port",     () -> redis.getMappedPort(6379));
//        r.add("platform.seeder.enabled",    () -> "false");
//    }
//
//    @LocalServerPort
//    int port;
//
//    @Autowired
//    ObjectMapper json;
//
//    static final String USER_ID = UUID.randomUUID().toString();
//    static final String ORG_ID  = UUID.randomUUID().toString();
//
//    RestClient client;
//
//    @BeforeEach
//    void setup() {
//        client = RestClient.builder()
//                .baseUrl("http://localhost:" + port)
//                .defaultHeader("X-User-Id",    USER_ID)
//                .defaultHeader("X-Org-Id",     ORG_ID)
//                .defaultHeader("X-User-Roles", "PLATFORM_ADMIN")
//                .build();
//    }
//
//    // ── helpers ───────────────────────────────────────────────────────────────
//
//    @SuppressWarnings("unchecked")
//    private ResponseEntity<Map> post(String uri, Map<String, Object> body) {
//        return client.post()
//                .uri(uri)
//                .contentType(MediaType.APPLICATION_JSON)
//                .body(body)
//                .retrieve()
//                .toEntity(Map.class);
//    }
//
//    @SuppressWarnings("unchecked")
//    private ResponseEntity<Map> get(String uri) {
//        return client.get()
//                .uri(uri)
//                .retrieve()
//                .toEntity(Map.class);
//    }
//
//    @SuppressWarnings("unchecked")
//    private String id(ResponseEntity<Map> resp) {
//        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
//        return (String) data.get("id");
//    }
//
//    // ── tests ─────────────────────────────────────────────────────────────────
//
//    @Test
//    void createAndGetAttribute() {
//        ResponseEntity<Map> created = post("/api/v1/attributes", Map.of(
//                "name", "test_venue_name",
//                "label", "Venue Name",
//                "attributeType", "TEXT",
//                "required", true,
//                "searchable", true,
//                "validationRules", Map.of("maxLength", 200)
//        ));
//
//        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
//        String attrId = id(created);
//        assertThat(attrId).isNotBlank();
//
//        ResponseEntity<Map> fetched = get("/api/v1/attributes/" + attrId);
//        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
//
//        @SuppressWarnings("unchecked")
//        Map<String, Object> data = (Map<String, Object>) fetched.getBody().get("data");
//        assertThat(data.get("name")).isEqualTo("test_venue_name");
//        assertThat(data.get("attributeType")).isEqualTo("TEXT");
//    }
//
//    @Test
//    void enumAttributeRequiresEnumValues() {
//        // ENUM without enumValues should be rejected by AttributeService validation
//        try {
//            post("/api/v1/attributes", Map.of(
//                    "name", "bad_enum",
//                    "label", "Bad Enum",
//                    "attributeType", "ENUM"
//            ));
//            // If we reach here without exception, the service didn't reject it — fail the test
//            assertThat(false).as("Expected 400 but no exception thrown").isTrue();
//        } catch (org.springframework.web.client.HttpClientErrorException e) {
//            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
//        }
//    }
//
//    @Test
//    void createObjectTypeAndGetSchema() {
//        // 1 — attribute
//        String attrId = id(post("/api/v1/attributes", Map.of(
//                "name", "venue_capacity_it",
//                "label", "Capacity",
//                "attributeType", "NUMBER",
//                "filterable", true,
//                "sortable", true
//        )));
//
//        // 2 — entity
//        String entityId = id(post("/api/v1/entities", Map.of(
//                "name", "venue_info_it",
//                "label", "Venue Info"
//        )));
//
//        // 3 — link attribute → entity
//        ResponseEntity<Map> linked = post("/api/v1/entities/" + entityId + "/attributes",
//                Map.of("attributeId", attrId, "displayOrder", 0, "required", false));
//        assertThat(linked.getStatusCode()).isEqualTo(HttpStatus.OK);
//
//        // 4 — object type
//        String otId = id(post("/api/v1/object-types", Map.of(
//                "name", "IT_VENUE",
//                "label", "Integration Test Venue"
//        )));
//
//        // 5 — section linking entity → object type
//        ResponseEntity<Map> section = post("/api/v1/object-types/" + otId + "/sections",
//                Map.of("entityId", entityId, "label", "Details", "displayOrder", 0));
//        assertThat(section.getStatusCode()).isEqualTo(HttpStatus.OK);
//
//        // 6 — schema endpoint (the one record-service calls)
//        ResponseEntity<Map> schemaResp = get("/api/v1/object-types/by-name/IT_VENUE/schema");
//        assertThat(schemaResp.getStatusCode()).isEqualTo(HttpStatus.OK);
//
//        @SuppressWarnings("unchecked")
//        Map<String, Object> schema = (Map<String, Object>) schemaResp.getBody().get("data");
//        assertThat(schema.get("objectType")).isEqualTo("IT_VENUE");
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> fields = (List<Map<String, Object>>) schema.get("fields");
//        assertThat(fields).isNotEmpty();
//        assertThat(fields).anyMatch(f -> "venue_capacity_it".equals(f.get("name")));
//    }
//}
