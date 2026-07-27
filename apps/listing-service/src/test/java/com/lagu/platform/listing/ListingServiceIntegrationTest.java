package com.lagu.platform.listing;

import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.listing.client.SchemaRegistryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Real-Postgres coverage for the atomic availability-claim primitive booking-service depends on
 * (bookSlot/releaseSlot, added 2026-07-27 alongside apps/booking-service — previously only
 * verified against a mocked repository, which cannot prove the underlying conditional UPDATE is
 * actually race-safe against a real database engine).
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {
        PlatformTopics.RECORD_EVENTS,
        PlatformTopics.WORKFLOW_EVENTS,
        PlatformTopics.LISTING_EVENTS,
        PlatformTopics.RECORD_EVENTS + ".DLT",
        PlatformTopics.WORKFLOW_EVENTS + ".DLT"
})
class ListingServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("platformdb")
            .withUsername("platform")
            .withPassword("platform");

    static final String TEST_GATEWAY_SECRET = "integration-test-shared-secret";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      () -> postgres.getJdbcUrl() + "?TimeZone=UTC");
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.flyway.url",          () -> postgres.getJdbcUrl() + "?TimeZone=UTC");
        r.add("spring.flyway.user",         postgres::getUsername);
        r.add("spring.flyway.password",     postgres::getPassword);
        r.add("platform.gateway.shared-secret", () -> TEST_GATEWAY_SECRET);
    }

    /** Would otherwise need a real schema-registry to answer whether IT_TEST_VENUE is publishable. */
    @MockitoBean
    SchemaRegistryClient schemaRegistryClient;

    @LocalServerPort int port;

    static final String TENANT_ID = UUID.randomUUID().toString();

    RestClient adminClient;
    RestClient internalClient;

    @BeforeEach
    void setUp() {
        when(schemaRegistryClient.getFlags(anyString()))
                .thenReturn(new SchemaRegistryClient.ListingTypeFlags(true, true));

        adminClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", UUID.randomUUID().toString())
                .defaultHeader("X-Tenant-Id", TENANT_ID)
                .defaultHeader("X-User-Roles", "CONFIG_ADMIN")
                .defaultHeader("X-Platform-Gateway-Secret", TEST_GATEWAY_SECRET)
                .build();

        internalClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-Internal-Service", "booking-service")
                .defaultHeader("X-Platform-Gateway-Secret", TEST_GATEWAY_SECRET)
                .build();
    }

    private UUID publishListing() {
        UUID recordId = UUID.randomUUID();
        ResponseEntity<Map> resp = adminClient.post()
                .uri("/api/v1/listings/{id}/publish", recordId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantId", TENANT_ID, "objectType", "IT_TEST_VENUE",
                        "data", Map.of("name", "Test Venue"), "verificationTier", "BASIC"))
                .retrieve().toEntity(Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return recordId;
    }

    @Test
    void bookThenReleaseThenRebook_roundTripsCleanly() {
        UUID recordId = publishListing();
        LocalDate date = LocalDate.now().plusDays(10);
        UUID bookingRefA = UUID.randomUUID();

        setAvailable(recordId, date);

        assertThat(book(recordId, date, bookingRefA)).isTrue();
        // A second, different bookingRef trying to claim the same already-BOOKED date must fail.
        assertThat(book(recordId, date, UUID.randomUUID())).isFalse();

        assertThat(release(recordId, date, bookingRefA)).isTrue();
        // Now that it's released, a new claim succeeds.
        UUID bookingRefB = UUID.randomUUID();
        assertThat(book(recordId, date, bookingRefB)).isTrue();
    }

    @Test
    void release_withWrongBookingRef_doesNotClearSomeoneElsesClaim() {
        UUID recordId = publishListing();
        LocalDate date = LocalDate.now().plusDays(11);
        UUID realBookingRef = UUID.randomUUID();

        setAvailable(recordId, date);
        assertThat(book(recordId, date, realBookingRef)).isTrue();

        // A stale/wrong bookingRef must not release someone else's real claim.
        assertThat(release(recordId, date, UUID.randomUUID())).isFalse();
        // Proven by: the real claimant still can't be displaced by a new booker.
        assertThat(book(recordId, date, UUID.randomUUID())).isFalse();
    }

    @Test
    void concurrentBookAttemptsOnTheSameSlot_exactlyOneWins() throws InterruptedException {
        // This is the actual safety property the whole booking feature depends on: two
        // consumers racing to confirm the same date on the same listing must not both succeed.
        UUID recordId = publishListing();
        LocalDate date = LocalDate.now().plusDays(12);
        setAvailable(recordId, date);

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();

        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (book(recordId, date, UUID.randomUUID())) {
                        successCount.incrementAndGet();
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(successCount.get()).isEqualTo(1);
    }

    @Test
    void internalEndpoints_rejectNonInternalCallers() {
        UUID recordId = publishListing();
        LocalDate date = LocalDate.now().plusDays(13);
        setAvailable(recordId, date);

        org.junit.jupiter.api.Assertions.assertThrows(HttpClientErrorException.class, () ->
                adminClient.post()
                        .uri("/internal/listings/{id}/availability/{date}/book", recordId, date)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("bookingRef", UUID.randomUUID()))
                        .retrieve().toBodilessEntity());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void setAvailable(UUID recordId, LocalDate date) {
        adminClient.put().uri("/api/v1/listings/{id}/availability", recordId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("from", date.toString(), "to", date.toString(), "slotType", "AVAILABLE"))
                .retrieve().toBodilessEntity();
    }

    @SuppressWarnings("unchecked")
    private boolean book(UUID recordId, LocalDate date, UUID bookingRef) {
        Map<String, Object> resp = internalClient.post()
                .uri("/internal/listings/{id}/availability/{date}/book", recordId, date)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("bookingRef", bookingRef))
                .retrieve().body(Map.class);
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        return Boolean.TRUE.equals(data.get("claimed"));
    }

    @SuppressWarnings("unchecked")
    private boolean release(UUID recordId, LocalDate date, UUID bookingRef) {
        Map<String, Object> resp = internalClient.post()
                .uri("/internal/listings/{id}/availability/{date}/release", recordId, date)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("bookingRef", bookingRef))
                .retrieve().body(Map.class);
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        return Boolean.TRUE.equals(data.get("released"));
    }
}
