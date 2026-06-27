//package com.lagu.platform.notification;
//
//import com.lagu.platform.events.AutomationEvent;
//import com.lagu.platform.events.PlatformTopics;
//import com.lagu.platform.notification.domain.NotificationRepository;
//import com.lagu.platform.notification.service.EmailDeliveryService;
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
//import java.util.List;
//import java.util.Map;
//import java.util.UUID;
//import java.util.concurrent.TimeUnit;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.ArgumentMatchers.anyString;
//import static org.mockito.Mockito.verify;
//import static org.mockito.Mockito.when;
//
//@Testcontainers
//@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
//@EmbeddedKafka(partitions = 1, topics = {
//        PlatformTopics.AUTOMATION_EVENTS,
//        PlatformTopics.AUTOMATION_EVENTS + ".DLT"
//})
//class NotificationServiceIntegrationTest {
//
//    @Container
//    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
//            .withDatabaseName("platformdb")
//            .withUsername("platform")
//            .withPassword("platform");
//
//    @DynamicPropertySource
//    static void configure(DynamicPropertyRegistry r) {
//        r.add("spring.datasource.url",      () -> postgres.getJdbcUrl() + "&currentSchema=notification");
//        r.add("spring.datasource.username", postgres::getUsername);
//        r.add("spring.datasource.password", postgres::getPassword);
//        r.add("spring.flyway.url",          postgres::getJdbcUrl);
//        r.add("spring.flyway.user",         postgres::getUsername);
//        r.add("spring.flyway.password",     postgres::getPassword);
//    }
//
//    /** Stub SMTP — tests verify in-app delivery, not email transport. */
//    @MockitoBean
//    EmailDeliveryService emailDeliveryService;
//
//    @Autowired NotificationRepository notificationRepository;
//    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
//
//    @LocalServerPort int port;
//
//    static final UUID   RECIPIENT_ID = UUID.randomUUID();
//    static final UUID   ORG_ID       = UUID.randomUUID();
//    static final String ORG_STR      = ORG_ID.toString();
//
//    RestClient client;
//
//    @BeforeEach
//    void setUp() {
//        client = RestClient.builder()
//                .baseUrl("http://localhost:" + port)
//                .defaultHeader("X-User-Id",    RECIPIENT_ID.toString())
//                .defaultHeader("X-Org-Id",     ORG_STR)
//                .defaultHeader("X-User-Roles", "ORG_STAFF")
//                .build();
//
//        when(emailDeliveryService.send(anyString(), anyString(), anyString())).thenReturn(true);
//    }
//
//    // ── Kafka consumer: IN_APP delivery ───────────────────────────────────────
//
//    @Test
//    void automationEvent_inApp_createsNotification() {
//        AutomationEvent event = buildEvent("IN_APP", RECIPIENT_ID, null);
//        kafkaTemplate.send(PlatformTopics.AUTOMATION_EVENTS, ORG_STR, event);
//
//        Awaitility.await()
//                .atMost(10, TimeUnit.SECONDS)
//                .pollInterval(300, TimeUnit.MILLISECONDS)
//                .untilAsserted(() -> {
//                    var notifications = notificationRepository.findAll().stream()
//                            .filter(n -> RECIPIENT_ID.equals(n.getRecipientUserId()))
//                            .toList();
//                    assertThat(notifications).isNotEmpty();
//                    var n = notifications.get(0);
//                    assertThat(n.getTitle()).isEqualTo("Test Notification");
//                    assertThat(n.getChannel()).isEqualTo("IN_APP");
//                    assertThat(n.isRead()).isFalse();
//                });
//    }
//
//    @Test
//    void automationEvent_email_callsEmailService() {
//        String recipientEmail = "user@example.com";
//        AutomationEvent event = AutomationEvent.builder()
//                .eventType("ACTION_SUCCEEDED")
//                .actionType("SEND_NOTIFICATION")
//                .orgId(ORG_ID)
//                .payload(Map.of(
//                        "title",          "Email Notification",
//                        "message",        "Please review your documents",
//                        "channel",        "EMAIL",
//                        "recipientEmail", recipientEmail,
//                        "subject",        "Action Required"
//                ))
//                .occurredAt(Instant.now())
//                .build();
//
//        kafkaTemplate.send(PlatformTopics.AUTOMATION_EVENTS, ORG_STR, event);
//
//        Awaitility.await()
//                .atMost(10, TimeUnit.SECONDS)
//                .pollInterval(300, TimeUnit.MILLISECONDS)
//                .untilAsserted(() ->
//                        verify(emailDeliveryService).send(recipientEmail, "Action Required",
//                                "Please review your documents"));
//    }
//
//    @Test
//    void automationEvent_both_storesAndSendsEmail() {
//        String recipientEmail = "both@example.com";
//        AutomationEvent event = AutomationEvent.builder()
//                .eventType("ACTION_SUCCEEDED")
//                .actionType("SEND_NOTIFICATION")
//                .orgId(ORG_ID)
//                .payload(Map.of(
//                        "title",           "BOTH Notification",
//                        "message",         "Check this out",
//                        "channel",         "BOTH",
//                        "recipientUserId", RECIPIENT_ID.toString(),
//                        "recipientEmail",  recipientEmail
//                ))
//                .occurredAt(Instant.now())
//                .build();
//
//        kafkaTemplate.send(PlatformTopics.AUTOMATION_EVENTS, ORG_STR, event);
//
//        Awaitility.await()
//                .atMost(10, TimeUnit.SECONDS)
//                .pollInterval(300, TimeUnit.MILLISECONDS)
//                .untilAsserted(() -> {
//                    var stored = notificationRepository.findAll().stream()
//                            .filter(n -> RECIPIENT_ID.equals(n.getRecipientUserId())
//                                    && "BOTH".equals(n.getChannel()))
//                            .toList();
//                    assertThat(stored).isNotEmpty();
//                    assertThat(stored.get(0).isEmailSent()).isTrue();
//                });
//    }
//
//    @Test
//    void nonNotificationAction_isIgnored() {
//        long countBefore = notificationRepository.count();
//
//        AutomationEvent event = AutomationEvent.builder()
//                .eventType("ACTION_SUCCEEDED")
//                .actionType("UPDATE_FIELD")   // not SEND_NOTIFICATION — should be ignored
//                .orgId(ORG_ID)
//                .payload(Map.of("field", "status", "value", "ACTIVE"))
//                .occurredAt(Instant.now())
//                .build();
//
//        kafkaTemplate.send(PlatformTopics.AUTOMATION_EVENTS, ORG_STR, event);
//
//        // Brief wait to let the consumer process, then assert count unchanged
//        Awaitility.await()
//                .during(1, TimeUnit.SECONDS)
//                .atMost(3, TimeUnit.SECONDS)
//                .pollInterval(200, TimeUnit.MILLISECONDS)
//                .untilAsserted(() ->
//                        assertThat(notificationRepository.count()).isEqualTo(countBefore));
//    }
//
//    // ── REST API ──────────────────────────────────────────────────────────────
//
//    @Test
//    void listNotifications_returnsForCurrentUser() {
//        AutomationEvent event = buildEvent("IN_APP", RECIPIENT_ID, null);
//        kafkaTemplate.send(PlatformTopics.AUTOMATION_EVENTS, ORG_STR, event);
//
//        Awaitility.await()
//                .atMost(10, TimeUnit.SECONDS)
//                .pollInterval(300, TimeUnit.MILLISECONDS)
//                .untilAsserted(() -> {
//                    ResponseEntity<Map> resp = client.get()
//                            .uri("/api/v1/notifications")
//                            .retrieve().toEntity(Map.class);
//                    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
//
//                    @SuppressWarnings("unchecked")
//                    Map<String, Object> page = (Map<String, Object>) resp.getBody().get("data");
//                    assertThat(((Number) page.get("totalElements")).intValue()).isGreaterThanOrEqualTo(1);
//                });
//    }
//
//    @Test
//    void unreadCount_returnsPositiveAfterDelivery() {
//        AutomationEvent event = buildEvent("IN_APP", RECIPIENT_ID, null);
//        kafkaTemplate.send(PlatformTopics.AUTOMATION_EVENTS, ORG_STR, event);
//
//        Awaitility.await()
//                .atMost(10, TimeUnit.SECONDS)
//                .pollInterval(300, TimeUnit.MILLISECONDS)
//                .untilAsserted(() -> {
//                    @SuppressWarnings("unchecked")
//                    Map<String, Object> data = (Map<String, Object>) client.get()
//                            .uri("/api/v1/notifications/unread-count")
//                            .retrieve().toEntity(Map.class).getBody().get("data");
//                    assertThat(((Number) data.get("count")).longValue()).isGreaterThan(0);
//                });
//    }
//
//    @Test
//    void markRead_updatesNotification() {
//        AutomationEvent event = buildEvent("IN_APP", RECIPIENT_ID, null);
//        kafkaTemplate.send(PlatformTopics.AUTOMATION_EVENTS, ORG_STR, event);
//
//        // Wait for delivery
//        Awaitility.await()
//                .atMost(10, TimeUnit.SECONDS)
//                .pollInterval(300, TimeUnit.MILLISECONDS)
//                .until(() -> !notificationRepository.findAll().stream()
//                        .filter(n -> RECIPIENT_ID.equals(n.getRecipientUserId()))
//                        .toList().isEmpty());
//
//        String notifId = notificationRepository.findAll().stream()
//                .filter(n -> RECIPIENT_ID.equals(n.getRecipientUserId()))
//                .toList().get(0).getId().toString();
//
//        ResponseEntity<Map> readResp = client.post()
//                .uri("/api/v1/notifications/" + notifId + "/read")
//                .retrieve().toEntity(Map.class);
//        assertThat(readResp.getStatusCode()).isEqualTo(HttpStatus.OK);
//
//        @SuppressWarnings("unchecked")
//        Map<String, Object> data = (Map<String, Object>) readResp.getBody().get("data");
//        assertThat(data.get("read")).isEqualTo(true);
//    }
//
//    @Test
//    void markAllRead_returnsUpdatedCount() {
//        // Deliver two notifications
//        kafkaTemplate.send(PlatformTopics.AUTOMATION_EVENTS, ORG_STR, buildEvent("IN_APP", RECIPIENT_ID, null));
//        kafkaTemplate.send(PlatformTopics.AUTOMATION_EVENTS, ORG_STR, buildEvent("IN_APP", RECIPIENT_ID, null));
//
//        Awaitility.await()
//                .atMost(10, TimeUnit.SECONDS)
//                .pollInterval(300, TimeUnit.MILLISECONDS)
//                .until(() -> notificationRepository.findAll().stream()
//                        .filter(n -> RECIPIENT_ID.equals(n.getRecipientUserId()))
//                        .count() >= 2);
//
//        @SuppressWarnings("unchecked")
//        Map<String, Object> data = (Map<String, Object>) client.post()
//                .uri("/api/v1/notifications/read-all")
//                .contentType(MediaType.APPLICATION_JSON)
//                .retrieve().toEntity(Map.class).getBody().get("data");
//        assertThat(((Number) data.get("updated")).intValue()).isGreaterThanOrEqualTo(2);
//    }
//
//    // ── helpers ───────────────────────────────────────────────────────────────
//
//    private AutomationEvent buildEvent(String channel, UUID recipientId, String recipientEmail) {
//        Map<String, Object> payload = new java.util.HashMap<>();
//        payload.put("title",   "Test Notification");
//        payload.put("message", "Integration test message");
//        payload.put("channel", channel);
//        if (recipientId != null)    payload.put("recipientUserId", recipientId.toString());
//        if (recipientEmail != null) payload.put("recipientEmail",  recipientEmail);
//
//        return AutomationEvent.builder()
//                .eventType("ACTION_SUCCEEDED")
//                .actionType("SEND_NOTIFICATION")
//                .orgId(ORG_ID)
//                .payload(payload)
//                .occurredAt(Instant.now())
//                .build();
//    }
//}
