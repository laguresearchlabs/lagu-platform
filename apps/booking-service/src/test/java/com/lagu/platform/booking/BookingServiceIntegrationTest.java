package com.lagu.platform.booking;

import com.lagu.platform.booking.client.ListingServiceClient;
import com.lagu.platform.booking.client.SchemaRegistryClient;
import com.lagu.platform.events.BookingEvent;
import com.lagu.platform.events.PlatformTopics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Real-Postgres, real-Flyway, real-outbox coverage for booking-service's HTTP API — the
 * BookingServiceTest unit tests already cover the state-machine/authorization logic exhaustively
 * against mocks, but nothing before this proved the JPA entity/migration actually round-trips
 * through a real database, or that a confirmed booking's BookingEvent genuinely reaches Kafka
 * (not just that TransactionalOutbox.stage() was called). ListingServiceClient/SchemaRegistryClient
 * are mocked since they'd otherwise need real listing-service/schema-registry instances.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {
        PlatformTopics.BOOKING_EVENTS
})
class BookingServiceIntegrationTest {

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
        // Default is 1000ms — speed the relay up so the Kafka-arrival assertion doesn't have to
        // wait a full second on top of Awaitility's own poll interval.
        r.add("platform.outbox.poll-interval-ms", () -> "200");
    }

    @MockitoBean
    ListingServiceClient listingServiceClient;

    @MockitoBean
    SchemaRegistryClient schemaRegistryClient;

    @Autowired
    EmbeddedKafkaBroker embeddedKafkaBroker;

    @LocalServerPort int port;

    static final UUID CONSUMER_ID = UUID.randomUUID();
    static final UUID VENDOR_ID = UUID.randomUUID();
    static final UUID LISTING_RECORD_ID = UUID.randomUUID();

    RestClient consumerClient;
    RestClient vendorClient;
    KafkaConsumer<String, BookingEvent> bookingEvents;

    @BeforeEach
    void setUp() {
        when(listingServiceClient.getSnapshot(LISTING_RECORD_ID)).thenReturn(Optional.of(
                new ListingServiceClient.ListingInfo(LISTING_RECORD_ID, VENDOR_ID, "VENUE", "PUBLISHED", "BASIC")));
        when(schemaRegistryClient.getCommissionRate(anyString(), anyString()))
                .thenReturn(new BigDecimal("15.00"));

        consumerClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", CONSUMER_ID.toString())
                .defaultHeader("X-Platform-Gateway-Secret", TEST_GATEWAY_SECRET)
                .build();

        vendorClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", UUID.randomUUID().toString())
                .defaultHeader("X-Tenant-Id", VENDOR_ID.toString())
                .defaultHeader("X-Platform-Gateway-Secret", TEST_GATEWAY_SECRET)
                .build();

        Map<String, Object> consumerProps = new HashMap<>(KafkaTestUtils.consumerProps(
                "it-booking-events-" + UUID.randomUUID(), "true", embeddedKafkaBroker));
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        consumerProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.lagu.platform.events");
        consumerProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, BookingEvent.class.getName());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        bookingEvents = new KafkaConsumer<>(consumerProps);
        bookingEvents.subscribe(java.util.List.of(PlatformTopics.BOOKING_EVENTS));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createInquiry() {
        ResponseEntity<Map> resp = consumerClient.post()
                .uri("/api/v1/bookings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "listingRecordId", LISTING_RECORD_ID.toString(),
                        "eventDate", LocalDate.now().plusDays(30).toString(),
                        "inquiryMessage", "please"))
                .retrieve().toEntity(Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (Map<String, Object>) resp.getBody().get("data");
    }

    @Test
    void fullLifecycle_inquireQuoteConfirm_persistsThroughRealDbAndReachesKafka() {
        Map<String, Object> booking = createInquiry();
        String id = (String) booking.get("id");
        assertThat(booking.get("status")).isEqualTo("INQUIRY");
        assertThat(booking.get("consumerUserId")).isEqualTo(CONSUMER_ID.toString());
        assertThat(booking.get("vendorId")).isEqualTo(VENDOR_ID.toString());

        // ── vendor quotes ──────────────────────────────────────────────────────────────────
        Map<String, Object> quoted = quote(id, "1000.00", "here's the price");
        assertThat(quoted.get("status")).isEqualTo("QUOTED");
        assertThat(new BigDecimal(quoted.get("quotedPrice").toString())).isEqualByComparingTo("1000.00");
        assertThat(new BigDecimal(quoted.get("commissionRate").toString())).isEqualByComparingTo("15.00");
        assertThat(new BigDecimal(quoted.get("commissionAmount").toString())).isEqualByComparingTo("150.00");

        // ── consumer confirms — this is the transactional-outbox-staging path we care about ──
        when(listingServiceClient.bookSlot(eq(LISTING_RECORD_ID), any(), eq(UUID.fromString(id))))
                .thenReturn(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> confirmed = (Map<String, Object>) consumerClient.post()
                .uri("/api/v1/bookings/{id}/confirm", id)
                .retrieve().toEntity(Map.class).getBody().get("data");
        assertThat(confirmed.get("status")).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("availabilityClaimed")).isEqualTo(true);

        // ── the BookingEvent(CONFIRMED) must genuinely reach Kafka via the outbox relay, not
        // just have been staged in the DB — proves platform.outbox.enabled wiring actually works
        // for this service (booking-service is the first of its shape to use it, see
        // BookingEventPublisher's Javadoc). ─────────────────────────────────────────────────
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            ConsumerRecords<String, BookingEvent> records = bookingEvents.poll(Duration.ofMillis(300));
            boolean found = false;
            for (ConsumerRecord<String, BookingEvent> record : records) {
                if ("CONFIRMED".equals(record.value().getEventType())
                        && id.equals(record.value().getBookingId().toString())) {
                    found = true;
                }
            }
            assertThat(found).as("BookingEvent(CONFIRMED) for %s on %s", id, PlatformTopics.BOOKING_EVENTS)
                    .isTrue();
        });
    }

    @Test
    void confirm_whenSlotLostRace_leavesBookingQuotedNotConfirmed() {
        Map<String, Object> booking = createInquiry();
        String id = (String) booking.get("id");
        quote(id, "500.00", null);

        when(listingServiceClient.bookSlot(eq(LISTING_RECORD_ID), any(), eq(UUID.fromString(id))))
                .thenReturn(false);

        assertThatThrownBy(() -> consumerClient.post().uri("/api/v1/bookings/{id}/confirm", id)
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        // Confirmed via a fresh GET that the row genuinely persisted as QUOTED, not CONFIRMED —
        // this is exactly the invariant a mocked-repository unit test can't prove.
        @SuppressWarnings("unchecked")
        Map<String, Object> reloaded = (Map<String, Object>) consumerClient.get()
                .uri("/api/v1/bookings/{id}", id).retrieve().toEntity(Map.class).getBody().get("data");
        assertThat(reloaded.get("status")).isEqualTo("QUOTED");
    }

    @Test
    void quote_rejectsCallerNotInTheVendorOrg() {
        Map<String, Object> booking = createInquiry();
        String id = (String) booking.get("id");

        RestClient strangerVendorClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("X-User-Id", UUID.randomUUID().toString())
                .defaultHeader("X-Tenant-Id", UUID.randomUUID().toString())
                .defaultHeader("X-Platform-Gateway-Secret", TEST_GATEWAY_SECRET)
                .build();

        assertThatThrownBy(() -> strangerVendorClient.post().uri("/api/v1/bookings/{id}/quote", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("price", new BigDecimal("1000.00")))
                .retrieve().toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void cancel_fromConfirmed_callsReleaseSlot() {
        Map<String, Object> booking = createInquiry();
        String id = (String) booking.get("id");
        quote(id, "750.00", null);

        when(listingServiceClient.bookSlot(eq(LISTING_RECORD_ID), any(), eq(UUID.fromString(id))))
                .thenReturn(true);
        consumerClient.post().uri("/api/v1/bookings/{id}/confirm", id).retrieve().toBodilessEntity();

        when(listingServiceClient.releaseSlot(eq(LISTING_RECORD_ID), any(), eq(UUID.fromString(id))))
                .thenReturn(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> cancelled = (Map<String, Object>) consumerClient.post()
                .uri("/api/v1/bookings/{id}/cancel", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "change of plans"))
                .retrieve().toEntity(Map.class).getBody().get("data");

        assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancelReason")).isEqualTo("change of plans");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> quote(String id, String price, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("price", new BigDecimal(price));
        if (note != null) body.put("quoteNote", note);
        return (Map<String, Object>) vendorClient.post()
                .uri("/api/v1/bookings/{id}/quote", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().toEntity(Map.class).getBody().get("data");
    }
}
