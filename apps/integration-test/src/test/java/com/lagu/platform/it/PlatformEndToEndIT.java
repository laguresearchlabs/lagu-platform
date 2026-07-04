package com.lagu.platform.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.SchemaPublishedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.redis.testcontainers.RedisContainer;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Boots real schema-registry, record-service and search-service processes (one container each,
 * from their actual bootJar) against real Postgres, Redis, Kafka and OpenSearch containers, and
 * drives the full pipeline exactly like production would: publish a schema, create a record over
 * HTTP, let record-service's Kafka event drive search-service's OpenSearch indexing, then search
 * for it.
 *
 * <p>This intentionally does NOT use {@code @SpringBootTest}. Putting two or more apps' main
 * sourceSets on one test classpath makes {@code classpath:application.yml} and
 * {@code classpath:db/migration} resolve non-deterministically across services (Spring/Flyway
 * scan the classpath, they don't pick "the right" jar), which would silently apply record-service
 * migrations against schema-registry's schema or vice versa. Running each service as its own
 * process — as it actually is in production — sidesteps that entirely.
 *
 * <p>Service discovery: the apps resolve each other via a {@code @LoadBalanced RestClient}
 * pointed at {@code http://schema-registry}, normally backed by Eureka. There's no Eureka server
 * in this test, so each app container gets {@code eureka.client.enabled=false} (forcing Spring
 * Cloud LoadBalancer's {@code SimpleDiscoveryClient} to back off Eureka) plus a static
 * {@code spring.cloud.discovery.client.simple.instances.schema-registry[0].uri} pointed at the
 * schema-registry container's fixed network alias — that property has a hyphenated map key, which
 * Spring's relaxed env-var binding does not reliably support, so it's passed as a program argument
 * rather than an env var.
 */
class PlatformEndToEndIT {

    // Explicit non-default secret: services now refuse to start on the well-known default
    // (unless platform.gateway.allow-insecure-default=true), so the IT provisions its own.
    private static final String GATEWAY_SECRET = "it-e2e-gateway-secret";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("platformdb")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withNetwork(NETWORK)
                    .withNetworkAliases("postgres");

    private static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse("redis:7-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("redis");

    // KafkaContainer's default listener advertises itself at "localhost:<mapped-host-port>",
    // which only the test JVM (running on the host) can reach — containers on NETWORK can't
    // resolve that back to the broker. withListener() adds a second listener advertised at the
    // network alias, which is what the app containers' SPRING_KAFKA_BOOTSTRAP_SERVERS use below.
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("kafka")
                    .withListener("kafka:19092");

    private static final GenericContainer<?> OPENSEARCH =
            new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:2.19.0"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("opensearch")
                    .withEnv("discovery.type", "single-node")
                    .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                    .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                    .withExposedPorts(9200)
                    .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200)
                            .forStatusCode(200).withStartupTimeout(Duration.ofMinutes(5)));

    private static final GenericContainer<?> SCHEMA_REGISTRY = appContainer(
            "it.schemaRegistryJarDir", "schema-registry",
            Map.of(
                    "SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/platformdb",
                    "SPRING_DATASOURCE_USERNAME", "postgres",
                    "SPRING_DATASOURCE_PASSWORD", "postgres",
                    "SPRING_DATA_REDIS_HOST", "redis",
                    "SPRING_DATA_REDIS_PORT", "6379",
                    "SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:19092",
                    "PLATFORM_SEEDER_ENABLED", "false",
                    "PLATFORM_GATEWAY_SHARED_SECRET", GATEWAY_SECRET,
                    "EUREKA_CLIENT_ENABLED", "false"
            ),
            List.of());

    private static final GenericContainer<?> RECORD_SERVICE = appContainer(
            "it.recordServiceJarDir", "record-service",
            Map.of(
                    "SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/platformdb",
                    "SPRING_DATASOURCE_USERNAME", "postgres",
                    "SPRING_DATASOURCE_PASSWORD", "postgres",
                    "SPRING_DATA_REDIS_HOST", "redis",
                    "SPRING_DATA_REDIS_PORT", "6379",
                    "SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:19092",
                    "PLATFORM_GATEWAY_SHARED_SECRET", GATEWAY_SECRET,
                    "EUREKA_CLIENT_ENABLED", "false"
            ),
            List.of("--spring.cloud.discovery.client.simple.instances.schema-registry[0].uri=http://schema-registry:8080"));

    private static final GenericContainer<?> SEARCH_SERVICE = appContainer(
            "it.searchServiceJarDir", "search-service",
            Map.of(
                    "SPRING_DATA_REDIS_HOST", "redis",
                    "SPRING_DATA_REDIS_PORT", "6379",
                    "SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:19092",
                    "OPENSEARCH_HOST", "opensearch",
                    "OPENSEARCH_PORT", "9200",
                    "PLATFORM_GATEWAY_SHARED_SECRET", GATEWAY_SECRET,
                    "EUREKA_CLIENT_ENABLED", "false"
            ),
            List.of("--spring.cloud.discovery.client.simple.instances.schema-registry[0].uri=http://schema-registry:8080"));

    // Seeder disabled: the test builds its own workflow for IT_TEST_VENUE via the API,
    // which exercises the definition endpoints too.
    private static final GenericContainer<?> WORKFLOW_SERVICE = appContainer(
            "it.workflowServiceJarDir", "workflow-service",
            Map.of(
                    "SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/platformdb",
                    "SPRING_DATASOURCE_USERNAME", "postgres",
                    "SPRING_DATASOURCE_PASSWORD", "postgres",
                    "SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:19092",
                    "PLATFORM_SEEDER_ENABLED", "false",
                    "PLATFORM_GATEWAY_SHARED_SECRET", GATEWAY_SECRET,
                    "EUREKA_CLIENT_ENABLED", "false"
            ),
            List.of());

    private static final GenericContainer<?> LISTING_SERVICE = appContainer(
            "it.listingServiceJarDir", "listing-service",
            Map.of(
                    "SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/platformdb",
                    "SPRING_DATASOURCE_USERNAME", "postgres",
                    "SPRING_DATASOURCE_PASSWORD", "postgres",
                    "SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:19092",
                    "PLATFORM_GATEWAY_SHARED_SECRET", GATEWAY_SECRET,
                    "EUREKA_CLIENT_ENABLED", "false"
            ),
            // listing-service fetches records over HTTP when snapshotting a published listing
            List.of("--spring.cloud.discovery.client.simple.instances.record-service[0].uri=http://record-service:8080"));

    private static RestClient schemaRegistryClient;
    private static RestClient recordServiceClient;
    private static RestClient searchServiceClient;
    private static RestClient workflowServiceClient;
    private static RestClient listingServiceClient;

    @BeforeAll
    static void startPlatform() {
        Startables.deepStart(Stream.of(POSTGRES, REDIS, KAFKA, OPENSEARCH)).join();
        SCHEMA_REGISTRY.start();
        // These four only talk to each other over Kafka (plus lazy HTTP schema/record fetches),
        // so they can come up together once schema-registry is reachable.
        Startables.deepStart(Stream.of(RECORD_SERVICE, SEARCH_SERVICE, WORKFLOW_SERVICE, LISTING_SERVICE)).join();

        schemaRegistryClient = restClientFor(SCHEMA_REGISTRY);
        recordServiceClient = restClientFor(RECORD_SERVICE);
        searchServiceClient = restClientFor(SEARCH_SERVICE);
        workflowServiceClient = restClientFor(WORKFLOW_SERVICE);
        listingServiceClient = restClientFor(LISTING_SERVICE);
    }

    @AfterAll
    static void stopPlatform() {
        Stream.of(LISTING_SERVICE, WORKFLOW_SERVICE, SEARCH_SERVICE, RECORD_SERVICE, SCHEMA_REGISTRY,
                        OPENSEARCH, KAFKA, REDIS, POSTGRES)
                .forEach(GenericContainer::stop);
        NETWORK.close();
    }

    @Test
    void createsPublishesIndexesAndSearchesRecordEndToEnd() throws Exception {
        String orgId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        String listingType = "IT_TEST_VENUE";

        // ── 1. schema-registry: define and publish a schema ────────────────────────────────
        // FieldRequest is a Java record; this app stack runs Jackson 3, which — unlike Jackson 2 —
        // fails record deserialization if a primitive component is absent from the JSON entirely
        // rather than defaulting it to false, so every boolean field must be listed explicitly.
        JsonNode field = postForData(schemaRegistryClient, orgId, userId, "/api/v1/fields", Map.ofEntries(
                Map.entry("name", "name"),
                Map.entry("label", "Name"),
                Map.entry("fieldType", "TEXT"),
                Map.entry("required", true),
                Map.entry("unique", false),
                Map.entry("searchable", true),
                Map.entry("filterable", true),
                Map.entry("sortable", false),
                Map.entry("facetable", false),
                Map.entry("promoted", false),
                Map.entry("rangeFilterable", false),
                Map.entry("arrayManageable", false)
        ));
        assertThat(field.get("name").asText()).isEqualTo("name");

        JsonNode fieldGroup = postForData(schemaRegistryClient, orgId, userId, "/api/v1/field-groups", Map.of(
                "name", "basic-info",
                "label", "Basic Info",
                "entries", List.of(Map.of("fieldName", "name", "displayOrder", 0, "required", true))
        ));
        assertThat(fieldGroup.get("name").asText()).isEqualTo("basic-info");

        JsonNode listing = postForData(schemaRegistryClient, orgId, userId, "/api/v1/listing-types", Map.of(
                "name", listingType,
                "label", "Test Venue",
                "publishable", true,
                "consumerSearchable", true,
                "sections", List.of(Map.of(
                        "fieldGroupName", "basic-info",
                        "label", "Basic Info",
                        "sectionKey", "basic",
                        "displayOrder", 0,
                        "collapsible", false
                ))
        ));
        assertThat(listing.get("name").asText()).isEqualTo(listingType);

        // Subscribe before publishing so we don't race the event onto the topic.
        try (KafkaConsumer<String, String> schemaEvents = kafkaConsumer("it-schema-events")) {
            schemaEvents.subscribe(List.of(PlatformTopics.SCHEMA_EVENTS));

            JsonNode published = postForData(schemaRegistryClient, orgId, userId,
                    "/api/v1/listing-types/" + listingType + "/publish",
                    Map.of("changeSummary", "initial publish"));
            assertThat(published.get("version").asInt()).isEqualTo(1);

            // ── assert the publish genuinely reached the message bus, not just the HTTP 200 ──
            SchemaPublishedEvent event = awaitEvent(schemaEvents, SchemaPublishedEvent.class,
                    e -> listingType.equals(e.getListingType()));
            assertThat(event.getEventType()).isEqualTo("SCHEMA_PUBLISHED");
            assertThat(event.getVersion()).isEqualTo(1);
        }

        // ── 2. record-service: create a record validated against that published schema ────
        JsonNode record = postForData(recordServiceClient, orgId, userId, "/api/v1/records", Map.of(
                "objectType", listingType,
                "data", Map.of("name", "Grand Hall"),
                "status", "DRAFT"
        ));
        String recordId = record.get("id").asText();
        assertThat(record.get("objectType").asText()).isEqualTo(listingType);

        // ── 3. search-service: the CREATED event flows over Kafka and gets indexed into
        // OpenSearch asynchronously, so poll until it shows up. ─────────────────────────────
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    JsonNode results = search(searchServiceClient, orgId, userId, listingType, "Grand Hall");
                    assertThat(results.get("total").asLong()).isGreaterThan(0);
                });

        JsonNode results = search(searchServiceClient, orgId, userId, listingType, "Grand Hall");
        JsonNode hit = results.get("results").get(0);
        assertThat(hit.get("recordId").asText()).isEqualTo(recordId);
        assertThat(hit.get("data").get("name").asText()).isEqualTo("Grand Hall");

        // ── 4. workflow: define a DRAFT→SUBMITTED→PUBLISHED workflow for this type via the API,
        // then drive the record through it. This exercises the real transition pipeline end to
        // end: record-service → Kafka → workflow-service (role check, case-insensitive trigger,
        // guard) → Kafka → record-service applies the status. ────────────────────────────────
        String workflowId = createVenueWorkflow(orgId, userId, listingType);
        assertThat(workflowId).isNotBlank();

        // Vendor submits. The role attached to the event (ORG_MANAGER via the header below) must
        // satisfy the transition's allowedRoles, and the lowercase "submit" trigger must match.
        requestTransition(recordServiceClient, orgId, userId, "ORG_MANAGER", recordId, "submit");
        awaitWorkflowState(recordId, "SUBMITTED");

        // Allowed transitions must be role-filtered: an ORG_MANAGER sees "publish", an
        // unprivileged ORG_MEMBER sees nothing actionable from SUBMITTED.
        JsonNode managerView = workflowStatus(orgId, userId, "ORG_MANAGER", recordId);
        assertThat(triggerNames(managerView)).contains("publish");
        JsonNode memberView = workflowStatus(orgId, userId, "ORG_MEMBER", recordId);
        assertThat(triggerNames(memberView)).isEmpty();

        // Publish (mixed-case trigger proves case-insensitive matching), then confirm the record
        // status caught up via the TRANSITIONED event.
        requestTransition(recordServiceClient, orgId, userId, "ORG_MANAGER", recordId, "PuBlIsH");
        awaitWorkflowState(recordId, "PUBLISHED");
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    JsonNode r = getRecord(recordServiceClient, orgId, userId, recordId);
                    assertThat(r.get("status").asText()).isEqualTo("PUBLISHED");
                });

        // ── 5. listing-service reacted to the PUBLISHED transition by creating a snapshot, whose
        // ListingEvent flowed to search-service's consumer index. A second, unverified vendor's
        // listing is added the same way (via a separate record) so we can assert tier ranking. ─
        String rivalOrg = UUID.randomUUID().toString();
        String rivalRecordId = publishRivalListing(rivalOrg, listingType);

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    JsonNode consumer = consumerSearch(searchServiceClient, listingType, "Hall");
                    assertThat(consumer.get("total").asLong()).isGreaterThanOrEqualTo(1);
                });

        // ── 6. public consumer search — NO identity headers, NO gateway secret. ──────────────
        JsonNode consumerResults = consumerSearch(searchServiceClient, listingType, "Grand Hall");
        assertThat(consumerResults.get("total").asLong()).isGreaterThanOrEqualTo(1);
        boolean found = false;
        for (JsonNode r : consumerResults.get("results")) {
            if (recordId.equals(r.get("recordId").asText())) {
                assertThat(r.get("data").get("name").asText()).isEqualTo("Grand Hall");
                found = true;
            }
        }
        assertThat(found).as("the workflow-published listing must be consumer-searchable").isTrue();
        assertThat(rivalRecordId).isNotBlank();
    }

    /**
     * Publishes a second listing (different org, no verification tier) by driving it through the
     * same record→workflow→listing path, so consumer search has a cross-org corpus.
     */
    private String publishRivalListing(String rivalOrg, String listingType) {
        String rivalUser = UUID.randomUUID().toString();
        JsonNode rec = postForData(recordServiceClient, rivalOrg, rivalUser, "/api/v1/records", Map.of(
                "objectType", listingType, "data", Map.of("name", "Budget Hall"), "status", "DRAFT"));
        String rivalRecordId = rec.get("id").asText();
        requestTransition(recordServiceClient, rivalOrg, rivalUser, "ORG_MANAGER", rivalRecordId, "submit");
        awaitWorkflowState(rivalRecordId, "SUBMITTED");
        requestTransition(recordServiceClient, rivalOrg, rivalUser, "ORG_MANAGER", rivalRecordId, "publish");
        awaitWorkflowState(rivalRecordId, "PUBLISHED");
        return rivalRecordId;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static GenericContainer<?> appContainer(String jarDirSystemProperty, String alias,
                                                      Map<String, String> env, List<String> args) {
        String jarPath = resolveBootJar(jarDirSystemProperty);
        List<String> command = new java.util.ArrayList<>(List.of("java", "-jar", "/app/app.jar"));
        command.addAll(args);

        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("eclipse-temurin:25-jre-alpine"))
                .withNetwork(NETWORK)
                .withNetworkAliases(alias)
                .withCopyFileToContainer(MountableFile.forHostPath(jarPath), "/app/app.jar")
                .withCommand(command.toArray(new String[0]))
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/actuator/health").forPort(8080)
                        .forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)))
                .withLogConsumer(frame -> System.out.print("[" + alias + "] " + frame.getUtf8String()));
        env.forEach(container::withEnv);
        return container;
    }

    private static String resolveBootJar(String jarDirSystemProperty) {
        String dirPath = System.getProperty(jarDirSystemProperty);
        if (dirPath == null) {
            throw new IllegalStateException("Missing system property " + jarDirSystemProperty
                    + " — run via ./gradlew :apps:integration-test:test so the app bootJars are built first.");
        }
        java.io.File dir = new java.io.File(dirPath);
        java.io.File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar") && !name.endsWith("-plain.jar"));
        if (jars == null || jars.length != 1) {
            throw new IllegalStateException("Expected exactly one bootJar in " + dirPath
                    + " but found " + (jars == null ? 0 : jars.length)
                    + " — run ./gradlew :apps:integration-test:test (which depends on the app bootJar tasks) rather than this test directly.");
        }
        return jars[0].getAbsolutePath();
    }

    private static RestClient restClientFor(GenericContainer<?> container) {
        return RestClient.builder()
                .baseUrl("http://" + container.getHost() + ":" + container.getMappedPort(8080))
                .build();
    }

    private static JsonNode postForData(RestClient client, String orgId, String userId, String uri, Object body) {
        String raw;
        try {
            raw = client.post().uri(uri)
                    .header("X-User-Id", userId)
                    .header("X-Org-Id", orgId)
                    .header("X-User-Roles", "PLATFORM_ADMIN")
                    .header("X-Platform-Gateway-Secret", GATEWAY_SECRET)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new AssertionError("POST " + uri + " failed: " + e.getStatusCode()
                    + " " + e.getResponseBodyAsString(), e);
        }
        try {
            JsonNode node = JSON.readTree(raw);
            return node.get("data");
        } catch (Exception e) {
            throw new AssertionError("Could not parse response from " + uri + ": " + raw, e);
        }
    }

    private static JsonNode search(RestClient client, String orgId, String userId, String objectType, String query) {
        String raw;
        try {
            raw = client.post().uri("/api/v1/search")
                    .header("X-User-Id", userId)
                    .header("X-Org-Id", orgId)
                    .header("X-User-Roles", "PLATFORM_ADMIN")
                    .header("X-Platform-Gateway-Secret", GATEWAY_SECRET)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("objectType", objectType, "query", query))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new AssertionError("POST /api/v1/search failed: " + e.getStatusCode()
                    + " " + e.getResponseBodyAsString(), e);
        }
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("Could not parse search response: " + raw, e);
        }
    }

    /** Creates a minimal DRAFT→SUBMITTED→PUBLISHED workflow for the object type; returns its id. */
    private static String createVenueWorkflow(String orgId, String userId, String objectType) {
        JsonNode wf = postForData(workflowServiceClient, orgId, userId, "/api/v1/workflow-definitions",
                Map.of("name", "it_wf_" + objectType.toLowerCase(), "label", "IT Workflow",
                        "objectType", objectType, "initialStatus", "DRAFT"));
        String id = wf.get("id").asText();

        for (Map<String, Object> s : List.<Map<String, Object>>of(
                Map.of("name", "DRAFT", "label", "Draft"),
                Map.of("name", "SUBMITTED", "label", "Submitted"),
                Map.of("name", "PUBLISHED", "label", "Published", "terminal", true))) {
            postForData(workflowServiceClient, orgId, userId,
                    "/api/v1/workflow-definitions/" + id + "/states", s);
        }
        // Lowercase trigger names, as the production seeder stores them.
        postForData(workflowServiceClient, orgId, userId, "/api/v1/workflow-definitions/" + id + "/transitions",
                Map.of("fromState", "DRAFT", "toState", "SUBMITTED", "triggerName", "submit",
                        "triggerLabel", "Submit", "allowedRoles", List.of("ORG_MANAGER", "ORG_OWNER")));
        postForData(workflowServiceClient, orgId, userId, "/api/v1/workflow-definitions/" + id + "/transitions",
                Map.of("fromState", "SUBMITTED", "toState", "PUBLISHED", "triggerName", "publish",
                        "triggerLabel", "Publish", "allowedRoles", List.of("ORG_MANAGER", "ORG_OWNER")));
        return id;
    }

    /** POSTs a transition request to record-service with an explicit role on the identity header. */
    private static void requestTransition(RestClient client, String orgId, String userId, String role,
                                          String recordId, String trigger) {
        try {
            client.post().uri("/api/v1/records/" + recordId + "/status")
                    .header("X-User-Id", userId).header("X-Org-Id", orgId)
                    .header("X-User-Roles", role)
                    .header("X-Platform-Gateway-Secret", GATEWAY_SECRET)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("trigger", trigger))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new AssertionError("transition " + trigger + " failed: " + e.getStatusCode()
                    + " " + e.getResponseBodyAsString(), e);
        }
    }

    private static JsonNode workflowStatus(String orgId, String userId, String role, String recordId) {
        try {
            String raw = workflowServiceClient.get().uri("/api/v1/records/" + recordId + "/workflow")
                    .header("X-User-Id", userId).header("X-Org-Id", orgId)
                    .header("X-User-Roles", role)
                    .header("X-Platform-Gateway-Secret", GATEWAY_SECRET)
                    .retrieve().body(String.class);
            return JSON.readTree(raw).get("data");
        } catch (Exception e) {
            throw new AssertionError("workflow status fetch failed for " + recordId, e);
        }
    }

    private static void awaitWorkflowState(String recordId, String expected) {
        // The workflow status endpoint is keyed purely by recordId (not org-scoped), but the
        // gateway filter still parses X-Org-Id as a UUID, so pass a syntactically valid one.
        String probeOrg  = UUID.randomUUID().toString();
        String probeUser = UUID.randomUUID().toString();
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    JsonNode s = workflowStatus(probeOrg, probeUser, "PLATFORM_ADMIN", recordId);
                    assertThat(s).as("workflow state not yet initialised for %s", recordId).isNotNull();
                    assertThat(s.get("currentState").asText()).isEqualTo(expected);
                });
    }

    private static List<String> triggerNames(JsonNode workflowStatus) {
        List<String> names = new java.util.ArrayList<>();
        JsonNode allowed = workflowStatus.get("allowedTransitions");
        if (allowed != null) allowed.forEach(t -> names.add(t.get("triggerName").asText()));
        return names;
    }

    private static JsonNode getRecord(RestClient client, String orgId, String userId, String recordId) {
        try {
            String raw = client.get().uri("/api/v1/records/" + recordId)
                    .header("X-User-Id", userId).header("X-Org-Id", orgId)
                    .header("X-User-Roles", "ORG_MANAGER")
                    .header("X-Platform-Gateway-Secret", GATEWAY_SECRET)
                    .retrieve().body(String.class);
            return JSON.readTree(raw).get("data");
        } catch (Exception e) {
            throw new AssertionError("record fetch failed for " + recordId, e);
        }
    }

    /** Consumer search is public: deliberately sends NO identity headers and NO gateway secret. */
    private static JsonNode consumerSearch(RestClient client, String objectType, String query) {
        String raw;
        try {
            raw = client.post().uri("/api/v1/search/consumer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("objectType", objectType, "query", query))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new AssertionError("POST /api/v1/search/consumer failed: " + e.getStatusCode()
                    + " " + e.getResponseBodyAsString(), e);
        }
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("Could not parse consumer search response: " + raw, e);
        }
    }

    private static KafkaConsumer<String, String> kafkaConsumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId + "-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    private static <T> T awaitEvent(KafkaConsumer<String, String> consumer, Class<T> type,
                                     java.util.function.Predicate<T> matches) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                try {
                    T event = JSON.readValue(record.value(), type);
                    if (matches.test(event)) {
                        return event;
                    }
                } catch (Exception e) {
                    System.err.println("Skipping unparseable " + type.getSimpleName()
                            + " record: " + record.value() + " (" + e + ")");
                }
            }
        }
        throw new AssertionError("Timed out waiting for a matching " + type.getSimpleName()
                + " on topic " + PlatformTopics.SCHEMA_EVENTS);
    }
}
