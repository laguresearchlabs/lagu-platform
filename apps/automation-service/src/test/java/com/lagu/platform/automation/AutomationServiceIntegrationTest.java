//package com.lagu.platform.automation;
//
//import com.lagu.platform.automation.client.RecordServiceClient;
//import com.lagu.platform.automation.client.WorkflowServiceClient;
//import com.lagu.platform.automation.domain.AutomationRunRepository;
//import com.lagu.platform.automation.domain.TriggerDefinitionRepository;
//import com.lagu.platform.events.PlatformTopics;
//import com.lagu.platform.events.RecordEvent;
//import com.redis.testcontainers.RedisContainer;
//import org.awaitility.Awaitility;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
//import org.springframework.boot.test.web.server.LocalServerPort;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.kafka.core.KafkaTemplate;
//import org.springframework.kafka.test.context.EmbeddedKafka;
//import org.springframework.test.context.DynamicPropertyRegistry;
//import org.springframework.test.context.DynamicPropertySource;
//import org.springframework.test.context.bean.override.mockito.MockitoBean;
//import org.springframework.web.client.RestClient;
//import org.testcontainers.containers.PostgreSQLContainer;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//
//import java.time.Instant;
//import java.util.Map;
//import java.util.UUID;
//import java.util.concurrent.TimeUnit;
//
//import static org.assertj.core.api.Assertions.assertThat;
//
//@Testcontainers
//@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
//@EmbeddedKafka(partitions = 1, topics = {
//        PlatformTopics.RECORD_EVENTS,
//        PlatformTopics.WORKFLOW_EVENTS,
//        PlatformTopics.AUTOMATION_EVENTS,
//        PlatformTopics.RECORD_EVENTS + ".DLT"
//})
//class AutomationServiceIntegrationTest {
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
//        r.add("spring.datasource.url",      () -> postgres.getJdbcUrl() + "&currentSchema=automation");
//        r.add("spring.datasource.username", postgres::getUsername);
//        r.add("spring.datasource.password", postgres::getPassword);
//        r.add("spring.flyway.url",          postgres::getJdbcUrl);
//        r.add("spring.flyway.user",         postgres::getUsername);
//        r.add("spring.flyway.password",     postgres::getPassword);
//        r.add("spring.data.redis.host",     redis::getHost);
//        r.add("spring.data.redis.port",     () -> redis.getMappedPort(6379));
//    }
//
//    @MockitoBean RecordServiceClient   recordServiceClient;
//    @MockitoBean WorkflowServiceClient workflowServiceClient;
//
//    @Autowired AutomationRunRepository    runRepository;
//    @Autowired TriggerDefinitionRepository triggerRepository;
//    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
//
//    @LocalServerPort int port;
//
//    static final String ORG_ID  = UUID.randomUUID().toString();
//    static final String USER_ID = UUID.randomUUID().toString();
//
//    RestClient client;
//
//    @BeforeEach
//    void setUp() {
//        client = RestClient.builder()
//                .baseUrl("http://localhost:" + port)
//                .defaultHeader("X-User-Id",    USER_ID)
//                .defaultHeader("X-Org-Id",     ORG_ID)
//                .defaultHeader("X-User-Roles", "CONFIG_ADMIN")
//                .build();
//    }
//
//    // ── CRUD ──────────────────────────────────────────────────────────────────
//
//    @Test
//    void createTrigger_returns201() {
//        ResponseEntity<Map> resp = post("/api/v1/triggers", Map.of(
//                "name",      "test-trigger-" + UUID.randomUUID(),
//                "label",     "Test Trigger",
//                "eventType", "RECORD_CREATED",
//                "objectType","EMPLOYEE"
//        ));
//        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
//        assertThat(resp.getBody()).containsKey("id");
//    }
//
//    @Test
//    void getById_returnsTrigger() {
//        String id = createTrigger("gt-trigger-" + UUID.randomUUID());
//
//        ResponseEntity<Map> resp = client.get().uri("/api/v1/triggers/" + id)
//                .retrieve().toEntity(Map.class);
//        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
//        assertThat(resp.getBody().get("id")).isEqualTo(id);
//    }
//
//    @Test
//    void listTriggers_returnsPaginated() {
//        createTrigger("lt-a-" + UUID.randomUUID());
//        createTrigger("lt-b-" + UUID.randomUUID());
//
//        ResponseEntity<Map> resp = client.get().uri("/api/v1/triggers")
//                .retrieve().toEntity(Map.class);
//        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
//
//        @SuppressWarnings("unchecked")
//        Map<String, Object> page = resp.getBody();
//        assertThat(((Number) page.get("totalElements")).intValue()).isGreaterThanOrEqualTo(2);
//    }
//
//    @Test
//    void addAndUpdateAction() {
//        String triggerId = createTrigger("action-trigger-" + UUID.randomUUID());
//
//        ResponseEntity<Map> addResp = post("/api/v1/triggers/" + triggerId + "/actions", Map.of(
//                "actionType", "LOG_ACTIVITY",
//                "executionOrder", 0,
//                "config", Map.of("message", "Record created: {{recordId}}")
//        ));
//        assertThat(addResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
//        String actionId = (String) addResp.getBody().get("id");
//        assertThat(actionId).isNotNull();
//
//        ResponseEntity<Map> updateResp = client.put()
//                .uri("/api/v1/triggers/" + triggerId + "/actions/" + actionId)
//                .contentType(MediaType.APPLICATION_JSON)
//                .body(Map.of(
//                        "actionType", "LOG_ACTIVITY",
//                        "executionOrder", 0,
//                        "config", Map.of("message", "Updated: {{recordId}}")
//                ))
//                .retrieve().toEntity(Map.class);
//        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
//    }
//
////    @Test
////    void disableTrigger_setsInactive() {
////        String id = createTrigger("disable-trigger-" + UUID.randomUUID());
////
////        client.delete().uri("/api/v1/triggers/" + id).retrieve().toBodilessEntity();
////
////        var trigger = triggerRepository.findById(UUID.fromString(id)).orElseThrow();
////        assertThat(trigger.isActive()).isFalse();
////    }
//
//    // ── Kafka consumer: trigger fires on matching event ───────────────────────
//
//    @Test
//    void kafkaEvent_matchesTrigger_createsAutomationRun() throws Exception {
//        String uniqueName = "kafka-trigger-" + UUID.randomUUID();
//        String triggerId  = createTrigger(uniqueName, "RECORD_CREATED", "EMPLOYEE");
//        post("/api/v1/triggers/" + triggerId + "/actions", Map.of(
//                "actionType",     "LOG_ACTIVITY",
//                "executionOrder", 0,
//                "config",         Map.of("message", "Employee record created")
//        ));
//
//        UUID orgId    = UUID.fromString(ORG_ID);
//        UUID recordId = UUID.randomUUID();
//
//        RecordEvent event = RecordEvent.builder()
//                .eventType("RECORD_CREATED")
//                .orgId(orgId)
//                .recordId(recordId)
//                .objectType("EMPLOYEE")
//                .currentStatus("DRAFT")
//                .data(Map.of("name", "Alice"))
//                .occurredAt(Instant.now())
//                .build();
//
//        // JSON producer → consumer's StringDeserializer reads the UTF-8 JSON bytes as a String
//        kafkaTemplate.send(PlatformTopics.RECORD_EVENTS, ORG_ID, event);
//
//        Awaitility.await()
//                .atMost(10, TimeUnit.SECONDS)
//                .pollInterval(300, TimeUnit.MILLISECONDS)
//                .untilAsserted(() -> {
//                    var runs = runRepository.findAll().stream()
//                            .filter(r -> r.getOrgId().equals(orgId) && r.getRecordId().equals(recordId))
//                            .toList();
//                    assertThat(runs).isNotEmpty();
//                    assertThat(runs.get(0).getStatus()).isIn("SUCCESS", "RUNNING");
//                });
//    }
//
//    @Test
//    void kafkaEvent_noMatchingTrigger_createsNoRun() throws Exception {
//        long runsBefore = runRepository.count();
//
//        RecordEvent event = RecordEvent.builder()
//                .eventType("RECORD_CREATED")
//                .orgId(UUID.randomUUID())    // different org — no trigger exists for it
//                .recordId(UUID.randomUUID())
//                .objectType("UNKNOWN_TYPE")
//                .currentStatus("DRAFT")
//                .occurredAt(Instant.now())
//                .build();
//
//        kafkaTemplate.send(PlatformTopics.RECORD_EVENTS, "unmatched", event);
//
//        Thread.sleep(2000);
//        assertThat(runRepository.count()).isEqualTo(runsBefore);
//    }
//
//    // ── dry run ───────────────────────────────────────────────────────────────
//
//    @Test
//    void dryRun_returnsComplete() {
//        String id = createTrigger("dryrun-" + UUID.randomUUID());
//        post("/api/v1/triggers/" + id + "/actions", Map.of(
//                "actionType",     "LOG_ACTIVITY",
//                "executionOrder", 0,
//                "config",         Map.of("message", "Dry run check")
//        ));
//
//        ResponseEntity<Map> resp = post("/api/v1/triggers/" + id + "/test",
//                Map.of("name", "Alice", "status", "DRAFT"));
//        assertThat(resp.getBody().get("status")).isEqualTo("DRY_RUN_COMPLETE");
//    }
//
//    // ── helpers ───────────────────────────────────────────────────────────────
//
//    private String createTrigger(String name) {
//        return createTrigger(name, "RECORD_CREATED", "EMPLOYEE");
//    }
//
//    @SuppressWarnings("unchecked")
//    private String createTrigger(String name, String eventType, String objectType) {
//        return (String) post("/api/v1/triggers", Map.of(
//                "name",       name,
//                "label",      name,
//                "eventType",  eventType,
//                "objectType", objectType
//        )).getBody().get("id");
//    }
//
//    @SuppressWarnings("unchecked")
//    private ResponseEntity<Map> post(String uri, Map<String, Object> body) {
//        return client.post().uri(uri)
//                .contentType(MediaType.APPLICATION_JSON)
//                .body(body)
//                .retrieve()
//                .toEntity(Map.class);
//    }
//}
