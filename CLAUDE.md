# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & test

Gradle multi-module build, Java 25 toolchain, Spring Boot 4.1 / Spring Cloud 2025.1.x.
Version catalog: `gradle/libs.versions.toml` — add dependencies there, never inline coordinates.
On PowerShell use `.\gradlew.bat`; the examples below use the Git Bash form.

```bash
./gradlew build                                   # everything (slow — Testcontainers per service)
./gradlew :apps:record-service:build              # one service
./gradlew :apps:record-service:test               # its tests
./gradlew :apps:record-service:test --tests '*RecordValidatorTest*'   # single test class
./gradlew :apps:record-service:test --tests '*RecordValidatorTest.rejectsMissingRequiredField'
./gradlew :apps:record-service:bootRun            # run locally (auto-activates the `loc` profile)
./gradlew :apps:integration-test:test             # cross-service E2E (see below)
```

There is no lint/format/static-analysis task — `build` is compile + test only.

`:apps:integration-test:test` boots real service `bootJar`s as containers; it declares
`bootJar` dependencies and jar-dir inputs, so it re-runs when service code changes. Budget
~30 min and expect it to want ~9 containers. It is a separate CI job, not part of the
per-service matrix, and is deliberately not gating deploys.

### Local infrastructure

`docker-compose.yml` is profile-gated — nothing starts without one:

```bash
docker compose --profile infra up -d          # postgres:5435, redis:6380, kafka:9092, kafdrop
docker compose --profile search up -d         # + OpenSearch
docker compose --profile observability up -d  # + otel-collector, prometheus, grafana
docker compose --profile full up -d           # + the services themselves
```

Then run services from the IDE/Gradle: the root build sets `spring.profiles.active=loc` for
every `bootRun`, and `application-loc.yml` points at those host ports.

## The `loc` profile trap

`application.yml` deliberately does **not** default `spring.profiles.active` — that would make
Docker images pick up `application-loc.yml`'s hardcoded `localhost` DB/Kafka/Redis. Local runs
get `loc` from the root `build.gradle.kts` `BootRun` config, or explicitly via
`SPRING_PROFILES_ACTIVE=loc`. Never reintroduce a default profile in `application.yml`.

Also in the root build: `systemProperty("user.timezone", "UTC")` for all tests. Removing it
breaks every Testcontainers Postgres test on hosts whose TZ resolves to `Asia/Calcutta`.

## Architecture

`docs/architecture.md` is the authority — a source-traced topology diagram, per-service
produce/consume table, and file-storage design. Read it before touching cross-service flows.
`workflow.md` walks the end-to-end request lifecycle with concrete payloads. `todo/` holds the
original design docs and ADRs; several services have since diverged from them, so treat `todo/`
as intent, not as a description of current behavior.

The core idea is a schema-driven ("no-code") object model: business objects are not hand-built
tables but `Record` rows with an `object_type` and a JSONB `data` payload, validated at write
time against schemas that live in **schema-registry**. Status changes are *requested* on
record-service but *decided* by workflow-service, round-tripped over Kafka.

`gateway-service` (Spring Cloud Gateway) and `registry-service` (Eureka) live in **separate
repos**, not here. Services register with Eureka and reach each other via a `@LoadBalanced
RestClient` on `http://<service-name>`.

### Shared libraries (`libs/`)

- **`common`** — transactional outbox (`TransactionalOutbox.stage` inside the business
  transaction; `OutboxRelay` delivers to Kafka), the `ApiResponse`/`ApiError`/`PageResult`
  envelope, `GlobalExceptionHandler`, JSONB converters, conditional field visibility.
- **`events`** — `PlatformTopics` constants (`platform.<domain>.events`) and the event DTOs.
  Every producer and consumer references these, never a topic string literal.
- **`security`** — `GatewayHeaderFilter`, `@RequirePermission`, `PermissionEvaluator`.
- **`membership`** — org/event membership policy shared by vendor-service and event-service.
- **`storage`** — presigned GCS/S3 uploads, content sniffing, image processing, ClamAV scanning.

### Security model

Services never see JWTs. The gateway validates the token and injects `X-User-Id`,
`X-Tenant-Id`, `X-User-Roles`, `X-User-Email`, all trusted **only** when accompanied by the
shared `X-Platform-Gateway-Secret`. Without it the request is treated as unauthenticated, so
hitting a published container port directly cannot forge identity. Service-to-service calls
send `X-Internal-Service: <name>` instead and get a single `SVC_<NAME>` authority — never
user/admin roles. The gateway strips that header from external traffic.

Endpoints are gated with `@RequirePermission(resource = "RECORD", action = "UPDATE")`, enforced
by `RequirePermissionAspect`. Cross-org access returns 404, not 403, to avoid leaking existence.

### File uploads

File bytes never pass through a JVM. Three steps: `POST …/upload-url` → client `PUT`s straight
to the bucket → `POST …/confirm` (size re-read from the bucket, magic bytes re-sniffed) before
anything persists. Services store the object **key**, never a signed URL, and sign a short-lived
download URL per request. Each service owns a key prefix (`platform.storage.domain`) so its
bucket IAM binding can be scoped to it.

### Persistence

One Postgres database (`platformdb`), one schema per service, created by
`infra/postgres/init.sql`; tables are Flyway-managed per service under
`src/main/resources/db/migration`. `ddl-auto: validate` everywhere. Services carrying an outbox
own an `*_outbox` table in their own schema.

| Service | Port (loc) | Schema | Outbox |
|---|---|---|---|
| schema-registry | 8090 | `schema_registry` | `schema_outbox` |
| record-service | 8101 | `records` | `record_outbox` |
| automation-service | 8103 | `automation` | — |
| document-service | 8081 | `documents` | — |
| search-service | 8082 | none (OpenSearch + Redis) | — |
| notification-service | 8084 | `notification` | — |
| workflow-service | 8085 | `workflow` | `workflow_outbox` |
| vendor-service | 8107 | `vendor` | — |
| listing-service | 8108 | `listing` | `listing_outbox` |
| booking-service | 8109 | `booking` | `booking_outbox` |
| event-service | 8110 | default | — |

## Service module conventions

Each `apps/<service>` has a `README.md` documenting its endpoints, entities and events — read it
before changing a service, and update it alongside behavior changes. Package layout is
`com.lagu.platform.<domain>` with `api/` (controllers), `service/`, `domain/` (entities +
repositories), `dto/`, `client/` (outbound REST), `event/` (Kafka producers/consumers),
`config/`, `mapper/` (MapStruct). Lombok and MapStruct are annotation processors.

Tests: plain unit tests plus one `*IntegrationTest` per service using `@SpringBootTest` +
`@Testcontainers` (Postgres, Redis) + `@EmbeddedKafka` with topics from `PlatformTopics`.
Cross-service behavior belongs in `apps/integration-test`, not in a service's own tests — two
services' `main` sourceSets on one classpath make `application.yml` and `db/migration` resolve
non-deterministically, which can apply one service's migrations to another's schema.

## CI/CD

`.github/workflows/ci-cd.yml` builds only services whose paths changed (`dorny/paths-filter`);
any change under `libs/`, `build.gradle.kts`, `settings.gradle.kts` or the version catalog
rebuilds everything. Images push to GCP Artifact Registry (`asia-south1`) on `main` only.
**A new service must be added to the `filters` block and the matrix-building script**, or it
silently never builds. Commits follow Conventional Commits (`feat(event-service): …`).
