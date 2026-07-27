package com.lagu.platform.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots real schema-registry, listing-service and booking-service processes (one container each,
 * from their actual bootJar) against real Postgres/Redis/Kafka containers, and drives the actual
 * booking-service flow — inquire, quote, confirm — purely over HTTP, with booking-service calling
 * the REAL listing-service and schema-registry rather than mocks (unlike BookingServiceIntegrationTest
 * and ListingServiceIntegrationTest, which each test one service in isolation with its peers
 * mocked). This is the only place that proves ListingServiceClient/SchemaRegistryClient's request/
 * response shapes actually match what the real services return.
 *
 * <p>record-service and workflow-service are deliberately NOT started — the listing being booked
 * is published via listing-service's manual admin endpoint (POST /{recordId}/publish), which
 * (verified by reading ListingSnapshotService) only calls schema-registry, not record-service.
 * See PlatformEndToEndIT for the full record→workflow→listing pipeline and the general container-
 * orchestration approach this file reuses.
 */
class BookingFlowEndToEndIT {

    private static final String GATEWAY_SECRET = "it-booking-e2e-gateway-secret";
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

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("kafka")
                    .withListener("kafka:19092");

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
            List.of("--spring.cloud.discovery.client.simple.instances.record-service[0].uri=http://record-service:8080",
                    "--spring.cloud.discovery.client.simple.instances.schema-registry[0].uri=http://schema-registry:8080"));

    private static final GenericContainer<?> BOOKING_SERVICE = appContainer(
            "it.bookingServiceJarDir", "booking-service",
            Map.of(
                    "SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/platformdb",
                    "SPRING_DATASOURCE_USERNAME", "postgres",
                    "SPRING_DATASOURCE_PASSWORD", "postgres",
                    "SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:19092",
                    "PLATFORM_GATEWAY_SHARED_SECRET", GATEWAY_SECRET,
                    "EUREKA_CLIENT_ENABLED", "false"
            ),
            List.of("--spring.cloud.discovery.client.simple.instances.listing-service[0].uri=http://listing-service:8080",
                    "--spring.cloud.discovery.client.simple.instances.schema-registry[0].uri=http://schema-registry:8080"));

    private static RestClient schemaRegistryClient;
    private static RestClient listingServiceClient;
    private static RestClient bookingServiceClient;

    @BeforeAll
    static void startPlatform() {
        Startables.deepStart(Stream.of(POSTGRES, REDIS, KAFKA)).join();
        SCHEMA_REGISTRY.start();
        // listing-service resolves record-service statically too (for fetching a record when
        // snapshotting via the workflow path) but never calls it on the manual-publish path this
        // test uses, so it's fine that nothing is actually listening at that address.
        LISTING_SERVICE.start();
        BOOKING_SERVICE.start();

        schemaRegistryClient = restClientFor(SCHEMA_REGISTRY);
        listingServiceClient = restClientFor(LISTING_SERVICE);
        bookingServiceClient = restClientFor(BOOKING_SERVICE);
    }

    @AfterAll
    static void stopPlatform() {
        Stream.of(BOOKING_SERVICE, LISTING_SERVICE, SCHEMA_REGISTRY, KAFKA, REDIS, POSTGRES)
                .forEach(GenericContainer::stop);
        NETWORK.close();
    }

    @Test
    void inquireQuoteConfirm_claimsRealAvailabilitySlotAcrossServices() {
        String vendorTenantId = UUID.randomUUID().toString();
        String adminUserId = UUID.randomUUID().toString();
        String vendorUserId = UUID.randomUUID().toString();
        String consumerUserId = UUID.randomUUID().toString();
        UUID listingRecordId = UUID.randomUUID();
        LocalDate eventDate = LocalDate.now().plusDays(45);

        // ── 1. schema-registry: a listing-type definition (publishable=true — this is what
        // listing-service's real SchemaRegistryClient.getFlags() checks before it will publish a
        // snapshot at all, so this isn't optional setup) and a tier config booking-service's
        // Quote step will look up. ──────────────────────────────────────────────────────────
        post(schemaRegistryClient, adminUserId, null, "PLATFORM_ADMIN", "/api/v1/fields", Map.ofEntries(
                Map.entry("name", "name"), Map.entry("label", "Name"), Map.entry("fieldType", "TEXT"),
                Map.entry("required", true), Map.entry("unique", false), Map.entry("searchable", true),
                Map.entry("filterable", true), Map.entry("sortable", false), Map.entry("facetable", false),
                Map.entry("promoted", false), Map.entry("rangeFilterable", false), Map.entry("arrayManageable", false)
        ));
        post(schemaRegistryClient, adminUserId, null, "PLATFORM_ADMIN", "/api/v1/field-groups", Map.of(
                "name", "it-booking-basic-info", "label", "Basic Info",
                "entries", List.of(Map.of("fieldName", "name", "displayOrder", 0, "required", true))
        ));
        post(schemaRegistryClient, adminUserId, null, "PLATFORM_ADMIN", "/api/v1/listing-types", Map.of(
                "name", "IT_BOOKING_VENUE", "label", "IT Booking Venue",
                "publishable", true, "consumerSearchable", true,
                "sections", List.of(Map.of(
                        "fieldGroupName", "it-booking-basic-info", "label", "Basic Info",
                        "sectionKey", "basic", "displayOrder", 0, "collapsible", false))
        ));
        post(schemaRegistryClient, adminUserId, null, "PLATFORM_ADMIN", "/api/v1/tier-configs", Map.of(
                "tierName", "BASIC", "commissionRate", 15.00, "maxActiveBookings", 10,
                "searchBoostFactor", 1.5, "responseSlaHours", 48, "expiryDays", 0
        ));

        // ── 2. listing-service: publish a listing directly (admin path — no record-service
        // needed), then open one date of availability. ─────────────────────────────────────
        JsonNode published = post(listingServiceClient, adminUserId, null, "CONFIG_ADMIN",
                "/api/v1/listings/" + listingRecordId + "/publish", Map.of(
                        "tenantId", vendorTenantId, "objectType", "IT_BOOKING_VENUE",
                        "data", Map.of("name", "Grand Hall"), "verificationTier", "BASIC"));
        assertThat(published.get("status").asText()).isEqualTo("PUBLISHED");

        put(listingServiceClient, vendorUserId, vendorTenantId, null,
                "/api/v1/listings/" + listingRecordId + "/availability", Map.of(
                        "from", eventDate.toString(), "to", eventDate.toString(), "slotType", "AVAILABLE"));

        // ── 3. booking-service: inquire, quote, confirm — talking to the REAL listing-service
        // and schema-registry above, not mocks. ─────────────────────────────────────────────
        JsonNode inquiry = post(bookingServiceClient, consumerUserId, null, null, "/api/v1/bookings", Map.of(
                "listingRecordId", listingRecordId.toString(),
                "eventDate", eventDate.toString(),
                "inquiryMessage", "Is this available?"));
        String bookingId = inquiry.get("id").asText();
        assertThat(inquiry.get("status").asText()).isEqualTo("INQUIRY");

        JsonNode quoted = post(bookingServiceClient, vendorUserId, vendorTenantId, null,
                "/api/v1/bookings/" + bookingId + "/quote",
                Map.of("price", 2000.00, "quoteNote", "here you go"));
        assertThat(quoted.get("status").asText()).isEqualTo("QUOTED");
        assertThat(quoted.get("commissionAmount").asDouble()).isEqualTo(300.00); // 2000 * 15%

        JsonNode confirmed = post(bookingServiceClient, consumerUserId, null, null,
                "/api/v1/bookings/" + bookingId + "/confirm", Map.of());
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("availabilityClaimed").asBoolean()).isTrue();

        // ── 4. Cross-service proof: listing-service's OWN availability records now show this
        // exact date as BOOKED with this booking's id as the bookingRef — confirming
        // booking-service's internal-service call to listing-service's atomic-claim endpoint
        // genuinely reached it over the network and the two services agree on the outcome. ───
        JsonNode availability = get(listingServiceClient, adminUserId, vendorTenantId, "CONFIG_ADMIN",
                "/api/v1/listings/" + listingRecordId + "/availability?from=" + eventDate + "&to=" + eventDate);
        assertThat(availability).hasSize(1);
        JsonNode slot = availability.get(0);
        assertThat(slot.get("slotType").asText()).isEqualTo("BOOKED");
        assertThat(slot.get("bookingRef").asText()).isEqualTo(bookingId);
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
                    + " — run ./gradlew :apps:integration-test:test rather than this test directly.");
        }
        return jars[0].getAbsolutePath();
    }

    private static RestClient restClientFor(GenericContainer<?> container) {
        return RestClient.builder()
                .baseUrl("http://" + container.getHost() + ":" + container.getMappedPort(8080))
                .build();
    }

    /** userId/tenantId/roles are each nullable — only the headers that are non-null get sent. */
    private static JsonNode post(RestClient client, String userId, String tenantId, String roles, String uri, Object body) {
        return exchange(client.post().uri(uri), userId, tenantId, roles, body);
    }

    private static JsonNode put(RestClient client, String userId, String tenantId, String roles, String uri, Object body) {
        return exchange(client.put().uri(uri), userId, tenantId, roles, body);
    }

    private static JsonNode exchange(RestClient.RequestBodySpec spec, String userId, String tenantId, String roles,
                                     Object body) {
        if (userId != null) spec = spec.header("X-User-Id", userId);
        if (tenantId != null) spec = spec.header("X-Tenant-Id", tenantId);
        if (roles != null) spec = spec.header("X-User-Roles", roles);
        spec = spec.header("X-Platform-Gateway-Secret", GATEWAY_SECRET).contentType(MediaType.APPLICATION_JSON);
        String raw;
        try {
            raw = spec.body(body).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw new AssertionError("request failed: " + e.getStatusCode()
                    + " " + e.getResponseBodyAsString(), e);
        }
        try {
            return JSON.readTree(raw).get("data");
        } catch (Exception e) {
            throw new AssertionError("Could not parse response: " + raw, e);
        }
    }

    private static JsonNode get(RestClient client, String userId, String tenantId, String roles, String uri) {
        RestClient.RequestHeadersSpec<?> spec = client.get().uri(uri);
        if (userId != null) spec = spec.header("X-User-Id", userId);
        if (tenantId != null) spec = spec.header("X-Tenant-Id", tenantId);
        if (roles != null) spec = spec.header("X-User-Roles", roles);
        spec = spec.header("X-Platform-Gateway-Secret", GATEWAY_SECRET);
        String raw;
        try {
            raw = spec.retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            throw new AssertionError("GET " + uri + " failed: " + e.getStatusCode()
                    + " " + e.getResponseBodyAsString(), e);
        }
        try {
            return JSON.readTree(raw).get("data");
        } catch (Exception e) {
            throw new AssertionError("Could not parse response from " + uri + ": " + raw, e);
        }
    }
}
