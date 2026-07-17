# notification-service

## What it does

`notification-service` delivers user-facing notifications for the lagu-platform. It listens for
`AutomationEvent`s produced by `automation-service` on the `platform.automation.events` Kafka topic,
and when an automation rule fires a `SEND_NOTIFICATION` action, it stores an in-app notification
row, sends a plain-text email, or both. It also exposes a small REST API so a frontend can list a
user's notifications, show an unread count, and mark them read. It exists so that "notify someone
when X happens" logic lives in one place instead of every other service having to know how to
write to a notifications table or talk to SMTP directly — automation-service just emits an event
describing the desired notification, and this service is the only thing that turns it into a
stored record and/or an outbound email.

Note: the module lives under a Kotlin/Gradle multi-module repo (Gradle Kotlin DSL build scripts),
but the service's own source code is plain Java, not Kotlin.

## Architecture / responsibilities

- **Channels implemented:** `IN_APP` and `EMAIL`, plus `BOTH` (store in-app AND send an email).
  There is no SMS, push, or webhook channel — those are not implemented anywhere in this module,
  regardless of what the broader platform naming ("notification") might suggest.
- **No templating engine.** There is no Thymeleaf/Freemarker/HTML-template dependency in
  `build.gradle.kts`, and `EmailDeliveryService` sends a `SimpleMailMessage` (plain text only).
  Title/message/subject text is not rendered from a template inside this service — it is passed
  through verbatim from the `AutomationEvent` payload (presumably rendered upstream, e.g. in
  automation-service).
- **Channel selection is a free-text column**, not an enum: `Notification.channel` is a
  `VARCHAR(20)` with a code comment `IN_APP | EMAIL | BOTH` (see
  `domain/Notification.java`). `NotificationDeliveryService.deliver()` upper-cases the incoming
  `channel` payload value and checks it with `if`/`else if` against those three strings. Any other
  value (including a typo) matches none of the branches, so the event is silently dropped —
  nothing is stored and no email is sent, with no error or log at that point.
- **Two service classes carry the actual logic:**
  - `NotificationDeliveryService` — consumes the automation event's payload map, decides
    in-app vs. email vs. both, persists a `Notification` row when appropriate, and calls
    `EmailDeliveryService` when appropriate. For `EMAIL`-only channel, it still writes an audit
    row after a successful send (there is no notification row for an in-app view, but the send
    is recorded).
  - `NotificationQueryService` — read/update side for the REST API: paginated listing (optionally
    unread-only), unread count, mark-one-read, mark-all-read. All queries are scoped to the
    calling user's `recipientUserId`; `markRead` throws `ResourceNotFoundException` (not e.g. 403)
    if the notification exists but belongs to a different user, to avoid confirming existence to
    an unauthorized caller.
- **Email delivery** (`EmailDeliveryService`) is a thin wrapper over Spring's `JavaMailSender`
  with two feature flags:
  - `platform.notification.email.enabled` (default `false`) — if false, `send()` is a no-op that
    returns `false` and logs at debug level.
  - `platform.notification.email.dry-run` (default `true`) — if true, nothing is sent over SMTP;
    the message is logged as `[EMAIL DRY-RUN] To: ... | Subject: ... | Body: ...` and treated as
    "sent" for bookkeeping purposes.
  - A real SMTP send failure throws `EmailDeliveryException` rather than being swallowed, so it
    propagates out of the Kafka listener and triggers the retry/DLT path described below (the
    code comment in `EmailDeliveryService` is explicit about this being intentional: silently
    returning `false` would ack the Kafka message as handled while the email was actually lost).

## REST API

All endpoints are under `/api/v1/notifications` (`NotificationController`). Every endpoint requires
an authenticated caller (identity comes from gateway-injected headers — see Security below) and is
gated by `@RequirePermission(resource = "NOTIFICATION", ...)`; per `DefaultPermissionEvaluator` in
`libs/security`, **any authenticated user** may read/manage their own notifications — this is a
looser check than most other resources in the platform, since users only ever see their own rows.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/notifications` | List the current user's notifications, paginated (`page`, `size` query params, default `0`/`20`). Optional `unreadOnly=true` filters to unread only. Ordered by `createdAt` descending. |
| `GET` | `/api/v1/notifications/unread-count` | Returns `{"count": <long>}` — unread notification count for the current user. |
| `POST` | `/api/v1/notifications/{id}/read` | Marks a single notification read (sets `read=true`, `readAt=now`) if it belongs to the current user; returns the updated notification. Not-mine or not-found both surface as a 404-style `ResourceNotFoundException`. |
| `POST` | `/api/v1/notifications/read-all` | Marks all of the current user's unread notifications as read; returns `{"updated": <count>}`. |

Responses are wrapped in the shared `ApiResponse<T>` envelope (`{"success": true, "data": ...}`)
from `libs/common`. Swagger UI is available at `/swagger-ui.html` and OpenAPI JSON at
`/v3/api-docs` (springdoc is on the classpath and configured in `application.yml`).

There is no endpoint to create a notification directly via REST — the only way notifications get
created is via the Kafka consumer described below.

## Kafka topics

- **Consumes:** `platform.automation.events` (`PlatformTopics.AUTOMATION_EVENTS`, constant value
  `"platform.automation.events"`), consumer group `notification-service`. Handled by
  `AutomationEventConsumer`, which only acts when
  `event.eventType == "ACTION_SUCCEEDED" && event.actionType == "SEND_NOTIFICATION"`; every other
  event on the topic is ignored (acked without side effects). The event payload
  (`AutomationEvent.payload`, a `Map<String,Object>`) is expected to carry:
  - `title` (defaults to `"Platform Notification"`)
  - `message` (defaults to empty string)
  - `channel` — `IN_APP` (default) | `EMAIL` | `BOTH`
  - `recipientUserId` — UUID string; required to associate an in-app row with a user
  - `recipientEmail` — required for `EMAIL`/`BOTH`
  - `subject` — email subject; falls back to `title` if absent
- **Produces:** none directly from application logic. The only outbound Kafka traffic is the
  dead-letter topic below, which Spring Kafka's error-handling infrastructure produces to, not
  application code.
- **Error handling / DLT** (`config/KafkaConfig.java`): a `DefaultErrorHandler` with a
  `DeadLetterPublishingRecoverer` retries a failed message 3 times with a 1-second fixed backoff,
  then republishes it to `<topic>.DLT` (partition 0). Listener ack mode is `MANUAL`
  (`spring.kafka.listener.ack-mode: MANUAL`), and the consumer explicitly calls
  `ack.acknowledge()` only after successful processing — exceptions are rethrown from the listener
  method specifically to engage this retry/DLT machinery.
- Kafka consumer/producer JSON (de)serialization trusts only the `com.lagu.platform.events`
  package (`spring.json.trusted.packages`) and defaults incoming messages to
  `com.lagu.platform.events.AutomationEvent` (`spring.json.value.default.type`) with type headers
  disabled, i.e. the topic is expected to carry only `AutomationEvent` payloads.

## Configuration

Config lives in `src/main/resources/application.yml` (base) and `application-loc.yml` (the `loc`
profile, for local non-Docker development). **No `application-docker.yml` or
`application-prod.yml` exists in this module** — `docker-compose.yml` sets
`SPRING_PROFILES_ACTIVE=docker` for this service, but since there is no matching profile document,
that just means the `loc` profile's overrides don't apply; docker-compose instead supplies every
value the base `application.yml` needs directly as environment variables.

Key properties / env vars (base `application.yml`):

| Property | Env var | Default | Notes |
|---|---|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | none (required) | PostgreSQL JDBC URL |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | none (required) | |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | none (required) | |
| `spring.kafka.bootstrap-servers` | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | none (required) | |
| `spring.mail.host` | `SPRING_MAIL_HOST` | none (required) | |
| `spring.mail.port` | `SPRING_MAIL_PORT` | none (required) | |
| `spring.mail.username` / `.password` | `SPRING_MAIL_USERNAME` / `SPRING_MAIL_PASSWORD` | none | |
| `platform.notification.email.enabled` | `PLATFORM_EMAIL_ENABLED` | `false` | master switch for sending real email |
| `platform.notification.email.dry-run` | `PLATFORM_EMAIL_DRY_RUN` | `false` in base yml (compose overrides default to `true`) | logs instead of sending |
| `platform.notification.email.from` | `PLATFORM_EMAIL_FROM` | none (required in base yml) | |
| `server.port` | `SERVER_PORT` | `8080` | |
| `eureka.client.service-url.defaultZone` | `EUREKA_SERVER_URL` | `http://localhost:8761/eureka` | service registers with Eureka |
| `platform.gateway.shared-secret` (from `libs/security`) | `PLATFORM_GATEWAY_SHARED_SECRET` | insecure well-known placeholder | required for the gateway-header trust model; service refuses to start with the placeholder unless `platform.gateway.allow-insecure-default=true` |
| — | `PLATFORM_GATEWAY_ALLOW_INSECURE_DEFAULT` | `false` | escape hatch used by `loc` profile and docker-compose |

Flyway runs migrations in the `notification` Postgres schema (`spring.flyway.schemas`,
`create-schemas: true`, `baseline-on-migrate: true`); `hibernate.ddl-auto` is `none`, so schema
changes must go through a Flyway migration under `src/main/resources/db/migration`
(currently just `V1__notification_schema.sql`). Actuator exposes `health`, `info`, `prometheus`.

### `loc` profile (`application-loc.yml`)

Used for local, non-Docker development (IDE run or `./gradlew bootRun`, which the root build
defaults to `-Dspring.profiles.active=loc`):

- Postgres: `jdbc:postgresql://localhost:5435/platformdb`, user/password `postgres`/`postgres`
  (matches the port docker-compose publishes for its `postgres` container, `5435:5432`, so the
  `loc` profile is meant to run against a compose-started Postgres from the host).
- Kafka: `localhost:9092`
- Mail: `localhost:1025` (a local SMTP catcher, e.g. MailHog/Mailpit — not started by this repo's
  compose file as far as this module's config shows)
- `platform.gateway.allow-insecure-default: true` — trusts the well-known placeholder gateway
  secret locally
- Email `enabled: false`, `dry-run: true` — no real email sent locally
- `server.port: 8084`
- Verbose logging: `com.lagu.platform` at DEBUG, plus Hibernate SQL/binding trace

### docker-compose

`docker-compose.yml` defines a `notification-service` entry (`platform-notification` container,
profiles `apps`/`full`) mapping host port **8105** to container port 8080, wired to the compose
`postgres` and `kafka` services, Eureka, and an OTel collector
(`OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317`).

## Running locally

Requires PostgreSQL and Kafka reachable (either via this repo's `docker-compose.yml` or standalone
instances), and a Java 25 toolchain (set at the root `build.gradle.kts` for all subprojects).

```bash
# from the repo root, start Postgres + Kafka (and friends) via compose, or point at your own
docker compose up -d postgres kafka

# run the service with the `loc` profile (root build.gradle.kts already sets this for bootRun)
./gradlew :apps:notification-service:bootRun
```

This binds to port `8084` per `application-loc.yml`, using `localhost:5435` for Postgres and
`localhost:9092` for Kafka. If SMTP isn't running locally, email stays disabled/dry-run per the
`loc` profile defaults, so nothing will actually fail for lack of a mail server.

To run the packaged jar the way the Docker image does (base `application.yml`, no `loc` overrides),
every required env var above (`SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`,
`SPRING_MAIL_HOST`/`PORT`, `PLATFORM_EMAIL_FROM`, etc.) must be set explicitly, e.g. via
`docker compose --profile apps up notification-service`.

## Running tests

```bash
./gradlew :apps:notification-service:test
```

**Gotcha:** the only test in this module,
`src/test/java/com/lagu/platform/notification/NotificationServiceIntegrationTest.java`, is
**entirely commented out** (all ~300 lines, the whole class body including its `package`
statement). As written today, `./gradlew :apps:notification-service:test` compiles and runs zero
tests for this service. The commented-out code describes what was clearly intended as the test
suite — a `Testcontainers` + `@EmbeddedKafka` Spring Boot integration test covering:
in-app delivery from an `AUTOMATION_EVENTS` message, email-channel delivery (with
`EmailDeliveryService` mocked), `BOTH`-channel delivery, ignoring non-`SEND_NOTIFICATION` actions,
and the four REST endpoints (list, unread-count, mark-read, mark-all-read) exercised through a
running server on a random port. Re-enabling it (uncommenting) is the fastest way to get real
coverage back; it depends on `testcontainers-junit`, `testcontainers-postgresql`, and
`spring-kafka-test`, all already declared in `build.gradle.kts`, plus Awaitility, which is used in
the commented-out code but is **not** currently listed as a test dependency in
`build.gradle.kts` — it would need to be added for the test to compile.

## Notable design decisions and gotchas

- **Channel is a plain string, not a real enum/type**, both in the JPA entity and the Kafka
  payload contract — a caller (automation-service) sending an unrecognized `channel` value
  results in the event being fully accepted and acked with *no* notification stored and *no* email
  sent, and no log line indicating anything was skipped for that reason.
- **Dry-run and enabled/disabled email are independent flags** — `dry-run` is only consulted after
  `enabled` is true; if email is disabled, dry-run is irrelevant and nothing is logged beyond a
  debug line.
- **Retry-then-DLT relies on exceptions propagating out of the `@KafkaListener` method.**
  `EmailDeliveryService.send()` deliberately throws rather than returning `false` on SMTP failure,
  specifically so `AutomationEventConsumer.handle()`'s catch-and-rethrow reaches
  `DefaultErrorHandler` → 3 retries (1s fixed backoff) → publish to `<topic>.DLT`. Any future change
  to swallow that exception would silently drop notifications instead of surfacing them for
  reprocessing.
- **`markRead` deliberately reports "not found" instead of "forbidden"** when a notification exists
  but belongs to another user, to avoid leaking existence of another user's notification via a 403
  vs. 404 distinction.
- **Authentication is fully delegated to the shared gateway-header trust model**
  (`libs/security`'s `GatewayHeaderFilter`/`ServiceSecurityConfig`): this service trusts
  `X-User-Id`/`X-Org-Id`/`X-User-Roles` only when the request also carries a matching
  `X-Platform-Gateway-Secret` header, and refuses to start if that shared secret is left at its
  well-known insecure default unless `platform.gateway.allow-insecure-default=true` is explicitly
  set (as it is for `loc` and in docker-compose). There is no login/JWT handling inside this
  service itself.
- **No producer role in the event graph** — this service is a pure Kafka consumer plus a REST
  read/update surface; it never publishes platform events of its own.
- Notification rows are retained indefinitely — there is no cleanup/archival job or TTL visible in
  the code or migrations.
