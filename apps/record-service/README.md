# record-service

## What it does

record-service is the generic data-record store for lagu-platform's schema-driven ("no-code")
object model. Instead of each domain (venues, vendors, etc.) getting its own hand-built table and
API, business objects are stored here as a `Record` row with an `object_type` tag and an
arbitrary JSONB `data` payload, validated at write time against a schema fetched from
schema-registry. It owns record CRUD, an audit trail of every change, named relationships between
records (e.g. linking a vendor record to a venue record), a verification/trust-tier subsystem, and
file/image field uploads (presigned direct-to-bucket via `libs/storage`). It does not own workflow/approval logic
itself — status changes are requested here but actually applied by workflow-service, which round-trips
the decision back over Kafka.

## Architecture / responsibilities

Package `com.lagu.platform.record`, four domain entities (schema `records` in the shared Postgres
database, Flyway-managed):

- **`Record`** (`record` table) — the core entity: `tenant_id`, `object_type`, `status` (default
  `DRAFT`), `data` (JSONB), optimistic-lock `version`, audit columns (`created_by`/`updated_by`/
  `created_at`/`updated_at`). Soft-deleted (`status = 'DELETED'`), never physically removed.
- **`RecordAudit`** (`record_audit` table) — append-only history of `CREATED` / `UPDATED` /
  `DELETED` / `STATUS_CHANGED` actions per record, storing old/new JSONB snapshots and old/new
  status, exposed via `GET /{id}/history`.
- **`RecordRelationship`** (`record_relationship` table) — named, directed links between two
  records (`relationship_name`, `source_record_id`, `target_record_id`), unique per
  (name, source, target). Validated against relationship definitions fetched from schema-registry
  when one exists (source/target object-type match, `ONE_TO_ONE` cardinality enforcement).
- **`RecordVerification`** (`record_verification` table) — one-per-record verification state:
  `tier` (`NONE`/`BASIC`/`ENHANCED`/`PREMIUM`), `status` (`UNVERIFIED`/`VERIFIED`/`REVOKED`/
  `EXPIRED`), optional `expires_at`. `RecordVerificationService.expireOverdue()` is invoked by
  automation-service's `EXPIRE_VERIFICATION` action to bulk-expire overdue verifications.

Two Kafka-facing components move data across service boundaries:

- **`RecordEventPublisher`** stages outbound events into the transactional outbox
  (`record_outbox` table, via `libs:common`'s `TransactionalOutbox`) inside the same transaction
  as the record change, rather than writing to Kafka directly. The actual delivery to Kafka is
  done by `OutboxRelay`, a shared component that lives in `libs:common`, not in this service's
  source tree.
- **`WorkflowEventConsumer`** and **`MetadataChangedConsumer`** react to events from
  workflow-service and schema-registry respectively (see Kafka section below).

Two REST clients reach other services (both via Eureka-backed load-balanced `RestClient`s):

- **`MetadataClient`** — fetches object-type schemas and relationship definitions from
  schema-registry (`http://schema-registry`), adapting its section-nested schema shape into this
  service's flat `FieldSchemaDto`/`ObjectTypeSchemaDto`. A comment in the code notes
  schema-registry absorbed what used to be metadata-service's responsibilities (per
  `todo/13-no-code-vendor-platform-adr.md`), which is why the class is still named
  `MetadataClient` while pointing at schema-registry. Schema lookups are cached in Redis
  (`metadata-schema` cache, 10-minute TTL) and invalidated on `SCHEMA_PUBLISHED` events.
- **`libs/storage`** — mints presigned PUT/GET URLs for `FILE`/`IMAGE` fields under the `record/`
  key prefix. File bytes never enter this JVM; the record's JSONB field holds the object **key**,
  and download URLs are signed per request by `GET /{id}/files/{fieldName}`. This replaced a
  proxy to image-service that stored a 10-minute signed URL into the record, so the field went
  stale minutes after upload.

`RecordValidator` validates a record's JSONB `data` against the schema for its `object_type`:
required fields, and per-type checks for `NUMBER`/`DECIMAL` (min/max), `TEXT`/`LONG_TEXT`
(maxLength/pattern), `EMAIL`, `PHONE`, `URL`, `ENUM`, `MULTI_SELECT`, `BOOLEAN`. Fields present in
`data` but not defined in the schema are stored anyway, only logged as a warning — the schema is
not strictly enforced as a closed set.

## REST API

All endpoints require gateway-authenticated identity (see Security below) and are gated by
`@RequirePermission(resource, action)`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/records?objectType=&status=&page=&size=` | Paged list of records for the caller's org, filtered by object type and optional status (excludes `DELETED` when `status` is omitted) |
| GET | `/api/v1/records/{id}` | Fetch a single record by id |
| POST | `/api/v1/records` | Create a record (`objectType`, `data`); starts in `DRAFT` unless caller is `PLATFORM_ADMIN` |
| PUT | `/api/v1/records/{id}` | Replace a record's `data` (validated against its schema) |
| PATCH | `/api/v1/records/{id}` | Merge partial `data` into the existing record (validated after merge) |
| DELETE | `/api/v1/records/{id}` | Soft-delete (sets `status = DELETED`) |
| POST | `/api/v1/records/{id}/status` | Request a status transition (`trigger`, `comment`); does **not** change status synchronously — publishes `STATUS_TRANSITION_REQUESTED` and waits for workflow-service to respond |
| GET | `/api/v1/records/{id}/history` | Paged audit history for a record |
| POST | `/api/v1/records/{id}/files/{fieldName}/upload-url` | Step 1 — presigned PUT URL for a `FILE`/`IMAGE`-typed field. Body: `fileName`, `contentType`, `sizeBytes`. Returns `uploadUrl`, `key`, `expiresAt` |
| — | *(client `PUT`s the file to `uploadUrl`)* | Step 2 — bytes go straight to the bucket; `Content-Type` must match, it is bound into the signature |
| POST | `/api/v1/records/{id}/files/{fieldName}/confirm` | Step 3 — body `key`. Verifies the object exists and is non-empty, then stores the **key** in that JSONB field |
| GET | `/api/v1/records/{id}/files/{fieldName}` | Freshly signed, short-lived download URL for a file field |
| GET | `/api/v1/records/{recordId}/verification` | Fetch a record's verification state |
| PUT | `/api/v1/records/{recordId}/verification` | Set verification tier (`NONE`/`BASIC`/`ENHANCED`/`PREMIUM`), notes, expiry |
| POST | `/api/v1/records/{recordId}/verification/revoke` | Revoke verification (`reason` in body) |
| POST | `/api/v1/records/{recordId}/verification/expire-overdue` | Bulk-expire verifications past `expires_at`; called by automation-service |
| GET | `/api/v1/records/{sourceId}/relationships?relationshipName=` | List relationships from a source record, optionally filtered by name |
| POST | `/api/v1/records/{sourceId}/relationships` | Create a relationship to a target record (`relationshipName`, `targetRecordId`) |
| DELETE | `/api/v1/records/{sourceId}/relationships/{relationshipName}/{targetId}` | Delete a specific relationship |

Responses are wrapped in `ApiResponse<T>` (from `libs:common`); list endpoints use `PageResult<T>`.
Swagger UI is exposed at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs` (springdoc).

## Kafka

Topic names come from `libs:events`' `PlatformTopics`.

**Produces** (all via the transactional outbox, not a direct `KafkaTemplate` call):
- `platform.record.events` (`RecordEvent`) — `CREATED`, `UPDATED`, `DELETED`,
  `STATUS_TRANSITION_REQUESTED` (consumed by workflow-service), `STATUS_CHANGED` (emitted after
  workflow-service's transition is applied).
- `platform.verification.events` (`VerificationEvent`) — `TIER_CHANGED`, `EXPIRED`, `REVOKED`.

**Consumes:**
- `platform.workflow.events` (`WorkflowEvent`, consumer group `record-service`) — only
  `TRANSITIONED` events are acted on: the record's `status` is updated, an audit row is written,
  and a `STATUS_CHANGED` `RecordEvent` is published. All other event types are acknowledged and
  ignored.
- `platform.schema.events` (`SchemaPublishedEvent`, consumer group `record-service-metadata`) —
  only `SCHEMA_PUBLISHED` events evict the corresponding entry from the `metadata-schema` Redis
  cache, so the next validation refetches the schema from schema-registry.

Consumer listener ack-mode is `MANUAL` (explicit `Acknowledgment.acknowledge()` in each listener).
A `DefaultErrorHandler` with `DeadLetterPublishingRecoverer` retries a failing message 3 times
(1s fixed backoff) before routing it to `<topic>.DLT`.

## Configuration

Base config: `src/main/resources/application.yml`. Local-dev overrides: `application-loc.yml`
(profile `loc`). No `application-docker.yml` exists — docker-compose sets
`SPRING_PROFILES_ACTIVE=docker` but that profile has no matching file in this service, so it just
runs on the base `application.yml` with docker-compose-supplied env vars.

Key properties / env vars (base `application.yml`):

| Property | Env var | Notes |
|---|---|---|
| `spring.datasource.url/username/password` | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | required, no default |
| `spring.jpa.hibernate.ddl-auto` | — | fixed to `validate`; schema changes must go through Flyway |
| `spring.datasource.hikari.schema` / `spring.jpa.properties.hibernate.default_schema` | — | fixed to `records` |
| `spring.flyway.schemas` | — | fixed to `records`; migrations in `db/migration` (V1–V5) |
| `spring.data.redis.host/port` | `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` | required, no default |
| `spring.kafka.bootstrap-servers` | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | required, no default |
| `spring.kafka.consumer.group-id` | — | fixed to `record-service` (base group; `MetadataChangedConsumer` overrides its own group id) |
| `server.port` | `SERVER_PORT` | default `8080` |
| `eureka.client.service-url.defaultZone` | `EUREKA_SERVER_URL` | default `http://localhost:8761/eureka` |
| `platform.gateway.shared-secret` | (referenced in `RecordServiceConfig`, set via env) | default is an insecure placeholder string; must be overridden in real environments |
| `platform.outbox.enabled` | — | default `true`; gates whether `TransactionalOutbox` bean is active |
| `management.endpoints.web.exposure.include` | — | `health,info,prometheus` |

`loc` profile (`application-loc.yml`, for IDE/`bootRun` use only — never picked up by the Docker
image): Postgres `jdbc:postgresql://localhost:5435/platformdb` (postgres/postgres), Redis
`localhost:6380`, Kafka `localhost:9092`, `server.port: 8101`, DEBUG logging for
`com.lagu.platform`/Hibernate SQL, and `platform.gateway.allow-insecure-default: true`.

The root `build.gradle.kts` forces `spring.profiles.active=loc` as a system property specifically
on the Gradle `bootRun` task (not baked into the jar), so `./gradlew :apps:record-service:bootRun`
works locally out of the box; the packaged Docker image always runs `java -jar app.jar` with no
default profile.

## Running locally

Requires Postgres, Redis, and Kafka. The repo's root `docker-compose.yml` provides all three
(and schema-registry, which this service depends on for schema validation):

```
docker compose --profile infra up -d postgres redis kafka schema-registry
./gradlew :apps:record-service:bootRun
```

This uses the `loc` profile automatically (see above): Postgres on `localhost:5435`, Redis on
`localhost:6380`, Kafka on `localhost:9092`, service listening on port `8101`.

Alternatively, run the whole service via docker-compose itself:

```
docker compose --profile apps up record-service
```

(published on host port `8101`, mapped to container port `8080`; see the `record-service` entry
in `docker-compose.yml` for its full docker env — `SPRING_PROFILES_ACTIVE=docker`, OTLP endpoint,
etc.)

## Running tests

```
./gradlew :apps:record-service:test
```

**Note:** the only test file, `src/test/java/com/lagu/platform/record/RecordServiceIntegrationTest.java`,
has its entire class body commented out (all ~250 lines, including the Testcontainers/embedded-Kafka
setup and every `@Test` method). As it stands, this command compiles and runs **zero** tests for
this module — there is currently no automated test coverage.

## Notable design decisions and gotchas

- **Status transitions are asynchronous.** `POST /{id}/status` never changes `status` directly —
  it publishes `STATUS_TRANSITION_REQUESTED` and returns the record with its *unchanged* status.
  The actual status change happens later when `WorkflowEventConsumer` receives a `TRANSITIONED`
  event back from workflow-service. Callers that expect the response body to reflect the new
  status will be surprised.
- **Create is locked to `DRAFT`.** Non-`PLATFORM_ADMIN` callers cannot set an initial status other
  than `DRAFT` on create — this is intentional so vendors can't skip the approval workflow by
  posting directly into `ACTIVE`/`PUBLISHED`.
- **Soft delete only.** `DELETE` sets `status = DELETED`; rows are never removed, and
  `findByIdAndTenantId` filters out `DELETED` records so they effectively disappear from normal reads.
- **Optimistic locking** (`@Version` on `Record` and `RecordVerification`, added in migration V5)
  turns concurrent writes into an HTTP 409 rather than silently discarding one writer's changes.
- **Transactional outbox pattern** (migration V4 + `libs:common`'s `TransactionalOutbox`) avoids
  the dual-write problem between the database and Kafka: events are staged in `record_outbox` in
  the same transaction as the data change, and a separate `OutboxRelay` (in `libs:common`, not in
  this service's own source) delivers them to Kafka afterward.
- **Multi-tenancy is enforced in the service layer**, not the database: every read/write path
  checks `PlatformSecurityContext.tenantId` against the record's `tenant_id` (except for
  `PLATFORM_ADMIN`, which sees all orgs). A caller with no org context gets a 403
  (`ORG_CONTEXT_REQUIRED`), never an unscoped lookup.
- **Identity comes entirely from headers**, trusted only when accompanied by a matching
  `X-Platform-Gateway-Secret` (`libs:security`'s `GatewayHeaderFilter`) — this service assumes it
  sits behind gateway-service (or is called by another internal service presenting
  `X-Internal-Service`); if `platform.gateway.shared-secret` is unset or blank, all requests are
  treated as unauthenticated (fails closed). The default secret value is a placeholder
  (`CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD`) and must be overridden outside local dev.
- **Unknown JSONB fields are tolerated, not rejected** — `RecordValidator` only logs a warning for
  fields in `data` that aren't declared in the object type's schema; they're still persisted.
- **File uploads mutate `data` directly**: `RecordFileController` checks the target field is typed
  `FILE`/`IMAGE` in the schema, uploads via `ImageServiceClient`, then merges the returned URL into
  the record's JSONB `data` under that field name and saves — bypassing `RecordService`'s own
  validate/audit/publish path for updates (no audit row or `UPDATED` event is emitted for file
  uploads).
- **Eureka/RestClient bean wiring workaround**: `RecordServiceConfig` documents that Eureka's own
  auto-configuration will greedily grab any unqualified `RestClient.Builder` bean for its
  heartbeat/registration calls — including a `@LoadBalanced` one, which then breaks trying to
  "load balance" to the literal Eureka host. A plain `@Primary` builder absorbs that unqualified
  injection, while `schemaRegistryRestClient`/`imageRestClient` explicitly `@Qualifier` the
  load-balanced builder to stay pinned to service-discovery behavior.
- **`mapstruct` and `mapstruct-processor` are declared dependencies** in `build.gradle.kts`, but no
  `@Mapper`-annotated class exists anywhere in this service's source — all DTO conversion is done
  by hand (e.g. `RecordService.toResponse`). Likely present for future use or copy-pasted from a
  shared module template.
