# listing-service

## What it does

`listing-service` owns the **consumer-facing published view** of vendor listings on the
lagu-platform. Vendor records (venues, photographers, caterers, decorators, makeup artists)
live and get edited in `record-service`, and their approval workflow is driven by
`workflow-service`. When a record transitions into an approved/active state, listing-service
takes a frozen, denormalized "snapshot" copy of that record's data, computes a search-ranking
boost from the vendor's verification tier, and exposes that snapshot to the public/consumer
side of the platform (search, browsing) — decoupling the consumer read path from the
record's live editable state and its workflow. It also owns vendor **availability**
(day-level AVAILABLE/BLOCKED/BOOKED slots) for a listing. It exists so consumer search/browse
never reads unapproved or in-flux vendor data directly, and so a vendor mid-edit doesn't affect
what buyers currently see.

## Architecture / domain ownership

- **ListingSnapshot** (`listing_snapshot` table) — one row per `record_id` (unique), holding a
  copy of the approved record's `data` (JSONB), its `objectType`, `status`
  (`PUBLISHED`/`UNPUBLISHED`), `verificationTier`, a derived `searchBoost`, and version/timestamps.
  This is the "frozen approved copy" referenced in the migration SQL comments.
- **ListingAvailability** (`listing_availability` table) — day-granularity slots per
  `(record_id, slot_date)` with a `slotType` of `AVAILABLE | BLOCKED | BOOKED` and an optional
  `bookingRef`. `ListingSnapshotService.bookSlot(...)` and the repository's `markBooked(...)`
  query exist to atomically flip a slot to `BOOKED`, but **nothing in this codebase currently
  calls `bookSlot`** — no controller endpoint or Kafka consumer invokes it. It appears to be
  scaffolding intended for a future integration with `booking-service`.
- **searchBoost** is always server-derived from `verificationTier` in
  `ListingSnapshotService.boostForTier` (NONE=1.0, BASIC=1.5, ENHANCED=1.8, PREMIUM=2.0) and is
  never accepted as caller input — the code comments explicitly call this out as a
  security/business-rule decision (a caller must not be able to set its own search ranking).
  The comment notes this ladder mirrors `schema-registry`'s `TierConfiguration` and "longer term
  this should be fetched from schema-registry instead" — i.e. it's a known duplication.
- Publish/unpublish is driven by `workflow-service`'s transition events (see Kafka section)
  and, separately, by an admin-only manual publish/unpublish REST path that bypasses the normal
  workflow-transition flow entirely.
- listing-service calls **record-service** synchronously (via `RecordServiceClient`, a
  load-balanced `RestClient` resolved through Eureka as `http://record-service`) to fetch full
  record data when building a snapshot from a workflow event.
- Search itself (OpenSearch-backed cross-org indexes) lives in **search-service**, which is the
  consumer of this service's Kafka events; the DB-backed `search` endpoint here is described in
  code as a fallback ("Consumer-facing paginated listing search (DB fallback; OpenSearch is the
  primary path)").

## REST API

All routes are under `/api/v1/listings` (`ListingController`). Responses are wrapped in the
shared `ApiResponse<T>` envelope (`{success, data, error}`) from `libs:common`.

Consumer/public:
- `GET /api/v1/listings/search?objectType=&page=&size=` — paginated search of published
  listings by vendor type (page defaults to 0, size to 20).
- `GET /api/v1/listings/{recordId}/snapshot` — fetch a single published snapshot by record id;
  404 if none exists.

Vendor (authenticated, requires a valid `PlatformSecurityContext` — see Security below):
- `GET /api/v1/listings/my` — the caller's own org's listing snapshots
  (`ListingSnapshotService.getByOrg`, scoped by `ctx.getTenantId()`).

Availability:
- `GET /api/v1/listings/{recordId}/availability?from=&to=` — availability slots in a date
  range (ISO `yyyy-MM-dd` dates).
- `PUT /api/v1/listings/{recordId}/availability` — upsert availability slots for a date range
  and `slotType`; requires the caller's org to own the record (otherwise treated as a 404, not
  a 403, to avoid leaking existence — see `ListingSnapshotService.setAvailability`).

Admin only (`requireAdmin()` — requires `CONFIG_ADMIN` or `PLATFORM_ADMIN` role):
- `POST /api/v1/listings/{recordId}/publish` — manually (re)publish a snapshot, bypassing the
  normal workflow-transition path. Body: `{tenantId, objectType, data, verificationTier}` — note
  `searchBoost` is deliberately **not** an accepted field.
- `POST /api/v1/listings/{recordId}/unpublish` — manually unpublish a snapshot.

Springdoc/OpenAPI UI is available (see Configuration) but no `@Operation`/`@Tag` annotations
are present in the controller beyond the endpoint mappings themselves.

## Kafka

Topic names come from `libs:events`' `PlatformTopics`.

**Consumes:**
- `platform.workflow.events` (`WorkflowEvent`, consumer group `listing-service`,
  `WorkflowEventConsumer`) — only acts on `eventType == "TRANSITIONED"`. If the record's new
  state (`toState`, case-insensitive) is one of `ACTIVE, APPROVED, PUBLISHED`, it fetches the
  full record from record-service and publishes a snapshot. If it's one of
  `SUSPENDED, ARCHIVED, REJECTED`, it unpublishes the snapshot. Any other `toState` is ignored.
  Failure to fetch the record (`RecordServiceClient` throws) propagates out of the listener by
  design — a code comment explains this is intentional so the container's error handler retries
  and eventually parks the event on the DLT rather than silently dropping a publish.

**Produces (via transactional outbox, not a direct `KafkaTemplate.send`):**
- `platform.listing.events` (`ListingEvent`, from `ListingEventPublisher`) — emitted on publish
  (`eventType=PUBLISHED`, carries the full snapshot data, tier, boost, `publishedAt`) and
  unpublish (`eventType=UNPUBLISHED`, minimal fields). Per the `ListingEvent` Javadoc,
  `search-service` consumes these to maintain its consumer-facing OpenSearch indexes.

**Outbox pattern:** listing-service does not publish to Kafka directly from request/consumer
threads. `ListingEventPublisher` stages events into the `listing_outbox` table
(via the shared `TransactionalOutbox` bean from `libs:common`) inside the same transaction as
the snapshot write, so the DB change and the staged event commit or roll back together. A
separate shared component, `OutboxRelay` (`libs:common`, `@Scheduled`), polls
`listing_outbox` every `platform.outbox.poll-interval-ms` (default 1000ms), claims rows with
`FOR UPDATE SKIP LOCKED`, and sends them to Kafka, marking each row published in the same
transaction as the send. `@EnableScheduling` on `ListingServiceApplication` is required for
this poller (and its cleanup job) to run; the class comment there explicitly calls out
`// OutboxRelay polling + cleanup`. Delivery is at-least-once (a crash between Kafka send and
marking published can redeliver) — consumers are expected to tolerate duplicates.

A `DefaultErrorHandler` bean (`KafkaConfig`) retries failed consumer processing 3 times
(1s fixed backoff) then routes the record to a `<topic>.DLT` dead-letter topic.

## Configuration

From `src/main/resources/application.yml` (base) and `application-loc.yml` (local profile):

| Property | Default | Purpose |
|---|---|---|
| `server.port` | `${SERVER_PORT:8108}` | HTTP port |
| `spring.datasource.url` | `${DB_URL:jdbc:postgresql://localhost:5432/lagu_listing}` | Postgres connection |
| `spring.datasource.username` / `password` | `${DB_USERNAME:lagu}` / `${DB_PASSWORD:lagu}` | Postgres credentials |
| `spring.datasource.hikari.schema` | `listing` | DB schema this service uses (shared database, own schema, per comment "keeps this service's tables and flyway history collision-free when sharing a database") |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Schema is managed by Flyway, not Hibernate |
| `spring.flyway.schemas` | `listing` | Flyway-managed schema |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP:localhost:9092}` | Kafka brokers |
| `eureka.client.service-url.defaultZone` | `${EUREKA_SERVER_URL:http://localhost:8761/eureka}` | Service registry, used to resolve `record-service` |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | Actuator |
| `springdoc.swagger-ui.path` | `/swagger-ui.html` | OpenAPI UI |
| `platform.outbox.enabled` | `true` | Enables the shared `TransactionalOutbox`/`OutboxRelay`/`OutboxStore` beans (they are `@ConditionalOnProperty`, inert otherwise) |
| `platform.outbox.table` | `listing_outbox` | Table name used by the shared outbox beans; validated as a bare SQL identifier at startup |
| `platform.outbox.poll-interval-ms` | `1000` (default in `OutboxRelay`, not set here) | Outbox relay poll frequency |
| `platform.gateway.shared-secret` | none (falls back to a well-known insecure default, `CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD`) | Shared secret gateway-service stamps on `X-Platform-Gateway-Secret`; also sent by `RecordServiceClient` when calling record-service |
| `platform.gateway.allow-insecure-default` | `false` (base), `true` in `application-loc.yml` | Must be explicitly enabled to run with the default secret; otherwise `ServiceSecurityConfig` refuses to start |

**Profiles:** only `loc` exists as a resource file (`application-loc.yml`) — local (non-Docker)
IDE/`bootRun` development, pointing at `localhost:5435/platformdb` (schema `listing`) and
`localhost:9092`. A comment in `application.yml` states a Docker image must never default to
this profile ("must never pick up application-loc.yml's hardcoded localhost DB/Kafka/Redis
config"); the root `build.gradle.kts` instead sets `spring.profiles.active=loc` as a system
property specifically on the `bootRun` Gradle task, so `./gradlew :apps:listing-service:bootRun`
activates it without the packaged jar/Docker image ever doing so. No `dev` or `prod` profile
resource files exist for this service; presumably prod-equivalent config is supplied entirely
via env vars against the base `application.yml`. Note: at the time of writing, the working tree
has an uncommitted local edit to `application.yml` that uncomments a default
`spring.profiles.active: loc` — this contradicts the adjacent comment and looks unintentional;
check `git diff` before relying on it.

**Notable env vars:** `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP`, `SERVER_PORT`,
`EUREKA_SERVER_URL`, `PLATFORM_GATEWAY_SHARED_SECRET` (maps to `platform.gateway.shared-secret`).

**Dockerfile gotcha:** the Dockerfile (`EXPOSE 8080`) exposes port 8080, but the application's
actual configured port (`server.port`, default `8108`) is 8108 — the `EXPOSE` line looks stale/
copy-pasted and does not reflect the real listening port.

## Security

Identity comes from trusted headers set by an API gateway (`GatewayHeaderFilter`,
`libs:security`), never from a JWT parsed locally: `X-User-Id`, `X-Tenant-Id`, `X-User-Roles`, or
`X-Internal-Service` for service-to-service calls — trusted only when accompanied by a matching
`X-Platform-Gateway-Secret`. `ListingController` reads the resulting `PlatformSecurityContext`
via `GatewayHeaderFilter.current()`. Endpoints under "Vendor" and "Availability" require a
resolved user context (`requireContext()`); admin publish/unpublish additionally requires
`CONFIG_ADMIN` or `PLATFORM_ADMIN` (`requireAdmin()`). `ServiceSecurityConfig`
(component-scanned via `scanBasePackages = {..., "com.lagu.platform.security"}` in
`ListingServiceApplication`) refuses to start if the gateway secret is left at its insecure
default unless `platform.gateway.allow-insecure-default=true` (set in `application-loc.yml`).

## Running locally

Requires PostgreSQL and Kafka reachable at the configured addresses, and (for full
functionality) record-service + Eureka running since `WorkflowEventConsumer` calls out to
record-service and registers itself via Eureka.

```
SPRING_PROFILES_ACTIVE=loc ./gradlew :apps:listing-service:bootRun
```

or simply

```
./gradlew :apps:listing-service:bootRun
```

since the root `build.gradle.kts` defaults the `bootRun` task's active profile to `loc`.
With the `loc` profile, it expects Postgres at `localhost:5435/platformdb` (schema `listing`,
user/password `postgres`/`postgres`) and Kafka at `localhost:9092`. Flyway
(`baseline-on-migrate: true`) applies `V1__listing_snapshot_schema.sql` and
`V2__listing_outbox.sql` on startup. Default port: **8108** (`server.port`, overridable via
`SERVER_PORT`).

To run the packaged jar directly (as the Dockerfile does): `java -jar app.jar`, configured
entirely by env vars against the base `application.yml` profile (no `loc` profile picked up).

## Testing

**There is no `src/test` directory in this module** — no unit or integration tests exist for
listing-service in lagu-platform, despite the module declaring test dependencies in
`build.gradle.kts` (`spring-boot-starter-test`, Testcontainers JUnit + Postgres,
`spring-kafka-test`). By contrast, the legacy service at
`/mnt/c/git/lagu/vendor-management/apps/listing-service` does have a `src/test` tree, which
suggests tests were not carried over as part of the migration to lagu-platform. Given this,
`./gradlew :apps:listing-service:test` will currently run zero tests.

## Migration status

This module appears to be a **partial migration** from the legacy `listing-service` in the
`vendor-management` repo:
- The 12 production source files here (application entry point, one controller, one Kafka
  consumer, one event publisher, one REST client, two JPA entities + repositories, two
  `@Configuration` classes, one service) implement snapshot publish/unpublish and availability,
  but no test suite was brought over.
- `bookSlot`/`markBooked` exist in the domain/repository/service layers with no caller anywhere
  in this codebase — looks like an unfinished or forward-looking integration point for a
  booking flow.
- The `searchBoost`-from-tier mapping is hardcoded here with an explicit code comment flagging
  it as a temporary duplication of `schema-registry`'s tier configuration.
- No `@Operation`/OpenAPI documentation annotations are present on the controller despite
  springdoc being wired in.
- Only a `loc` profile configuration exists; no `dev`/`prod` resource files are present in this
  module. (This matches the pattern seen in sibling services like `record-service`, which also
  only ships `application.yml` + `application-loc.yml` — so this may be a platform-wide
  convention rather than something specific to this migration.)
