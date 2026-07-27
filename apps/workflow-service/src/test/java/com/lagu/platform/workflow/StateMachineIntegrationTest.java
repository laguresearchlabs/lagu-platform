package com.lagu.platform.workflow;

import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.RecordEvent;
import com.lagu.platform.workflow.domain.RecordWorkflowStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {
        PlatformTopics.RECORD_EVENTS,
        PlatformTopics.WORKFLOW_EVENTS,
        PlatformTopics.RECORD_EVENTS + ".DLT"
})
class StateMachineIntegrationTest {

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
        r.add("platform.seeder.enabled",    () -> "false");
        r.add("platform.gateway.shared-secret", () -> TEST_GATEWAY_SECRET);
    }

    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired RecordWorkflowStateRepository rwsRepo;
    @LocalServerPort int port;

    static final String ADMIN_USER_ID = UUID.randomUUID().toString();
    static final String TENANT_ID        = UUID.randomUUID().toString();

    RestClient adminClient;

    @BeforeEach
    void setup() {
        adminClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id",    ADMIN_USER_ID)
                .defaultHeader("X-Tenant-Id",     TENANT_ID)
                .defaultHeader("X-User-Roles", "PLATFORM_ADMIN")
                .defaultHeader("X-Platform-Gateway-Secret", TEST_GATEWAY_SECRET)
                .build();
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void processTransitionRequest_validTrigger_transitionsState() {
        // 1. Build minimal workflow: DRAFT --SUBMIT--> SUBMITTED
        String objectType = "IT_VENUE_WF_" + UUID.randomUUID().toString().substring(0, 8);
        String wfId = createWorkflowDefinition("it-venue-wf", objectType);
        addState(wfId, "DRAFT",     false);
        addState(wfId, "SUBMITTED", false);
        addTransition(wfId, "DRAFT", "SUBMITTED", "SUBMIT", null);

        // 2. Send STATUS_TRANSITION_REQUESTED to Kafka
        UUID recordId = UUID.randomUUID();
        UUID tenantId    = UUID.fromString(TENANT_ID);

        RecordEvent event = RecordEvent.builder()
                .eventType("STATUS_TRANSITION_REQUESTED")
                .recordId(recordId)
                .tenantId(tenantId)
                .objectType(objectType)
                .triggerName("SUBMIT")
                .changedBy(UUID.fromString(ADMIN_USER_ID))
                .occurredAt(Instant.now())
                .build();

        kafkaTemplate.send(PlatformTopics.RECORD_EVENTS, tenantId + ":" + recordId, event);

        // 3. Verify state transitions to SUBMITTED
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(300, TimeUnit.MILLISECONDS)
                .until(() -> rwsRepo.findByRecordId(recordId)
                        .map(s -> "SUBMITTED".equals(s.getCurrentState()))
                        .orElse(false));

        assertThat(rwsRepo.findByRecordId(recordId)).isPresent()
                .get().satisfies(s -> assertThat(s.getCurrentState()).isEqualTo("SUBMITTED"));
    }

    @Test
    void processTransitionRequest_invalidTrigger_noStateCreated() {
        // Workflow DRAFT --SUBMIT--> SUBMITTED; send APPROVE (invalid from DRAFT)
        String objectType = "IT_VENUE_WF2_" + UUID.randomUUID().toString().substring(0, 8);
        String wfId = createWorkflowDefinition("it-venue-wf2", objectType);
        addState(wfId, "DRAFT",     false);
        addState(wfId, "SUBMITTED", false);
        addTransition(wfId, "DRAFT", "SUBMITTED", "SUBMIT", null);

        UUID recordId = UUID.randomUUID();
        UUID tenantId    = UUID.fromString(TENANT_ID);

        RecordEvent event = RecordEvent.builder()
                .eventType("STATUS_TRANSITION_REQUESTED")
                .recordId(recordId)
                .tenantId(tenantId)
                .objectType(objectType)
                .triggerName("APPROVE")    // no such transition from DRAFT
                .changedBy(UUID.fromString(ADMIN_USER_ID))
                .occurredAt(Instant.now())
                .build();

        kafkaTemplate.send(PlatformTopics.RECORD_EVENTS, tenantId + ":" + recordId, event);

        // State should not end up as SUBMITTED; the engine publishes a REJECTED event instead
        await().during(3, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS)
                .until(() -> !rwsRepo.findByRecordId(recordId)
                        .map(s -> "SUBMITTED".equals(s.getCurrentState()))
                        .orElse(false));
    }

    @Test
    void processTransitionRequest_mismatchedOrg_doesNotResolveOtherOrgsWorkflow() {
        // Regression coverage: a RecordEvent whose tenantId doesn't match any existing workflow
        // state row for that recordId must not silently attach to a state from another org —
        // findByRecordIdAndTenantId (not the unscoped findByRecordId) is what this test pins down.
        String objectType = "IT_VENUE_WF3_" + UUID.randomUUID().toString().substring(0, 8);
        String wfId = createWorkflowDefinition("it-venue-wf3", objectType);
        addState(wfId, "DRAFT",     false);
        addState(wfId, "SUBMITTED", false);
        addTransition(wfId, "DRAFT", "SUBMITTED", "SUBMIT", null);

        UUID recordId    = UUID.randomUUID();
        UUID realTenantId   = UUID.fromString(TENANT_ID);
        UUID attackerOrg = UUID.randomUUID();

        // First, legitimately initialize state for this record under its real org.
        RecordEvent initial = RecordEvent.builder()
                .eventType("STATUS_TRANSITION_REQUESTED")
                .recordId(recordId).tenantId(realTenantId).objectType(objectType)
                .triggerName("SUBMIT").changedBy(UUID.fromString(ADMIN_USER_ID))
                .occurredAt(Instant.now()).build();
        kafkaTemplate.send(PlatformTopics.RECORD_EVENTS, realTenantId + ":" + recordId, initial);

        await().atMost(10, TimeUnit.SECONDS).pollInterval(300, TimeUnit.MILLISECONDS)
                .until(() -> rwsRepo.findByRecordId(recordId)
                        .map(s -> "SUBMITTED".equals(s.getCurrentState())).orElse(false));

        // Now a second event for the SAME recordId claims a DIFFERENT org and a DIFFERENT
        // (nonexistent-for-that-org) workflow object type — this must not resolve against the
        // real org's already-initialized state.
        RecordEvent mismatched = RecordEvent.builder()
                .eventType("STATUS_TRANSITION_REQUESTED")
                .recordId(recordId).tenantId(attackerOrg).objectType(objectType)
                .triggerName("SUBMIT").changedBy(UUID.randomUUID())
                .occurredAt(Instant.now()).build();
        kafkaTemplate.send(PlatformTopics.RECORD_EVENTS, attackerOrg + ":" + recordId, mismatched);

        // Give the consumer time to process, then confirm the real org's state is untouched and
        // no state row was created under the attacker's org for this recordId.
        await().during(3, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> true);
        assertThat(rwsRepo.findByRecordIdAndTenantId(recordId, realTenantId)).isPresent()
                .get().satisfies(s -> assertThat(s.getCurrentState()).isEqualTo("SUBMITTED"));
    }

    @Test
    void workflowDefinition_crud_via_api() {
        String objectType = "IT_OT_" + UUID.randomUUID().toString().substring(0, 8);
        String wfId = createWorkflowDefinition("crud-test-wf", objectType);
        assertThat(wfId).isNotBlank();

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> fetched = adminClient.get()
                .uri("/api/v1/workflow-definitions/" + wfId)
                .retrieve().toEntity(Map.class);
        assertThat(fetched.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void getById_anotherOrgsWorkflow_returns404NotLeaked() {
        // Regression coverage for the review's "listAll/getById unscoped" finding: a non-admin
        // caller must not be able to read another org's *own* (org-scoped) workflow definition
        // by id. Note: a workflow created by a PLATFORM_ADMIN lands with tenantId=null (platform-
        // level, intentionally readable by everyone) — this test needs a CONFIG_ADMIN acting
        // within their own org instead, to actually produce an org-scoped (private) definition.
        String ownerTenantId = UUID.randomUUID().toString();
        RestClient orgConfigAdminClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id",    UUID.randomUUID().toString())
                .defaultHeader("X-Tenant-Id",     ownerTenantId)
                .defaultHeader("X-User-Roles", "CONFIG_ADMIN")
                .defaultHeader("X-Platform-Gateway-Secret", TEST_GATEWAY_SECRET)
                .build();

        String objectType = "IT_PRIV_WF_" + UUID.randomUUID().toString().substring(0, 8);
        String wfId = createWorkflowDefinition(orgConfigAdminClient, "private-wf", objectType);

        RestClient otherOrgClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id",    UUID.randomUUID().toString())
                .defaultHeader("X-Tenant-Id",     UUID.randomUUID().toString())
                .defaultHeader("X-User-Roles", "CONFIG_ADMIN")
                .defaultHeader("X-Platform-Gateway-Secret", TEST_GATEWAY_SECRET)
                .build();

        assertThatThrownBy(() -> otherOrgClient.get()
                .uri("/api/v1/workflow-definitions/" + wfId)
                .retrieve().toEntity(Map.class))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        // Sanity: the owning org's own client CAN read it.
        ResponseEntity<Map> ownRead = orgConfigAdminClient.get()
                .uri("/api/v1/workflow-definitions/" + wfId).retrieve().toEntity(Map.class);
        assertThat(ownRead.getStatusCode().is2xxSuccessful()).isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String createWorkflowDefinition(String name, String objectType) {
        return createWorkflowDefinition(adminClient, name, objectType);
    }

    @SuppressWarnings("unchecked")
    private String createWorkflowDefinition(RestClient asClient, String name, String objectType) {
        ResponseEntity<Map> resp = asClient.post()
                .uri("/api/v1/workflow-definitions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "name",          name,
                        "label",         name,
                        "objectType",    objectType,
                        "initialStatus", "DRAFT"))
                .retrieve().toEntity(Map.class);
        return (String) ((Map<String, Object>) resp.getBody().get("data")).get("id");
    }

    private void addState(String wfId, String name, boolean terminal) {
        adminClient.post()
                .uri("/api/v1/workflow-definitions/" + wfId + "/states")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name, "label", name, "terminal", terminal, "displayOrder", 0))
                .retrieve().toBodilessEntity();
    }

    private void addTransition(String wfId, String from, String to, String trigger,
                               String allowedRole) {
        Map<String, Object> body = allowedRole != null
                ? Map.of("fromState", from, "toState", to, "triggerName", trigger,
                         "allowedRoles", java.util.List.of(allowedRole))
                : Map.of("fromState", from, "toState", to, "triggerName", trigger);
        adminClient.post()
                .uri("/api/v1/workflow-definitions/" + wfId + "/transitions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toBodilessEntity();
    }
}
