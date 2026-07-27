# document-service

## What it does

`document-service` manages the lifecycle of HR/verification documents that users upload as
part of onboarding to an organization on the lagu-platform — resumes, government identity
proof, photographs, academic certificates, address proof, and other supporting files. It
tracks each document through a review workflow (`UPLOADED` -> `UNDER_REVIEW` -> `VERIFIED` /
`REJECTED`, plus a scheduled `EXPIRED` transition for time-limited identity documents) so that
org managers/owners can review submissions and the platform can answer "has this user
completed their document checklist?" It does not store file bytes itself — actual file
storage is delegated to `image-service` (see below) — this service is the system of record
for document *metadata*, ownership, and review status.

## Architecture / responsibilities

- Owns a `document` table (schema `documents`) keyed by `tenant_id`/`user_id`, storing document
  type, a URL/reference to the stored file (`file_url`), MIME type, size, review status,
  rejection reason, reviewer, and an optional `expiry_date` plus a free-form `jsonb` metadata
  column.
- **File storage**: `DocumentStorageService` does not write to local disk or S3 directly. It
  proxies the multipart upload to another microservice, **image-service**, via a
  load-balanced (Eureka-resolved) `RestClient` hitting `POST /api/v1/images/upload` with
  `group-type=HR_DOCUMENT`, `group-id=<userId>`, `sub-group-type=<documentType>`. It reads
  `signedUrl` (falling back to `imageURL`, then a bare `id`) out of image-service's response
  and persists that as `file_url`. So the actual blob storage backend used by image-service
  (S3, disk, etc.) is opaque to this codebase — it was not found anywhere in
  document-service's source.
- **Document type catalog**: `DocumentTypeRegistry` loads the list of valid document types
  (code, label, required flag, expiry-tracked flag) from `schema-registry`
  (`GET http://schema-registry/api/v1/document-requirements/catalog`) at startup and refreshes
  hourly (`platform.doc-types.refresh-ms`, default 3,600,000 ms). If schema-registry is
  unreachable it falls back to a hardcoded static list. **Gotcha**: the hardcoded fallback list
  uses the code `HR_IDENTITY_PROOF`, while the controller's Javadoc, `DocumentService`'s
  validation logic, and the domain comments all reference `IDENTITY_PROOF`. If schema-registry
  is down and the service is running on the fallback list, uploads of type `IDENTITY_PROOF`
  from a client following the documented contract would be rejected as an invalid document
  type — this inconsistency is present in the code as read, not an assumption.
- **Authorization**: uses the shared `libs/security` gateway-trust model — `GatewayHeaderFilter`
  only trusts `X-User-Id`/`X-Tenant-Id`/`X-User-Roles` headers when a matching
  `X-Platform-Gateway-Secret` is also present (set by `gateway-service`); otherwise requests are
  treated as unauthenticated. `@RequirePermission(resource = "DOCUMENT", action = ...)` on each
  endpoint is checked by `DefaultPermissionEvaluator`: any authenticated user can `CREATE`/`READ`
  their own documents, but the `REVIEW` action (claim/verify/reject, pending-review listing) is
  restricted to callers with role `ORG_MANAGER` or `ORG_OWNER`.
- **Upload validation**: only `image/jpeg`, `image/png`, `image/webp`, `application/pdf`
  content types (and matching `jpg/jpeg/png/webp/pdf` extensions) are accepted, capped at 20 MB
  (`DocumentService.MAX_FILE_SIZE_BYTES`); the comment in code explains this is deliberate —
  no executables, HTML/SVG (stored-XSS risk if ever rendered inline), or office/archive formats.
  Uploaded filenames are sanitized (path separators and non `[A-Za-z0-9._-]` characters
  stripped, truncated to 255 chars) before being stored as `file_name`.
- A `@Scheduled` job (`DocumentService.expireDocuments`, cron `0 0 1 * * *`, i.e. daily at
  01:00) flips any document whose `expiry_date` has passed to status `EXPIRED` (unless already
  `EXPIRED` or `REJECTED`).

## REST API

All endpoints are under `/api/v1/documents`, defined in `DocumentController`:

| Method | Path | Permission | Purpose |
|---|---|---|---|
| POST | `/api/v1/documents` (multipart/form-data) | DOCUMENT:CREATE | Upload a document. Params: `file`, `documentType` (`RESUME`, `IDENTITY_PROOF`, `PHOTOGRAPH`, `ACADEMIC_CERTIFICATE`, `ADDRESS_PROOF`, `OTHER`), optional `identitySubType` (required when `documentType=IDENTITY_PROOF`; one of `AADHAAR`, `PASSPORT`, `DRIVING_LICENSE`, `VOTER_ID`, `PAN_CARD`), optional `expiryDate` (ISO date). Returns 201 with the created document. |
| GET | `/api/v1/documents` | DOCUMENT:READ | List all documents uploaded by the authenticated user, newest first. |
| GET | `/api/v1/documents/{id}` | DOCUMENT:READ | Fetch a single document by ID (scoped to the caller's org unless the caller is a platform admin). |
| GET | `/api/v1/documents/submission-status` | DOCUMENT:READ | Returns a checklist across all known document types with each type's current status (`MISSING`, `UPLOADED`, `UNDER_REVIEW`, `VERIFIED`, `REJECTED`, `EXPIRED`) plus `allRequiredSubmitted`/`allRequiredVerified` booleans. |
| GET | `/api/v1/documents/pending-review?page=&size=` | DOCUMENT:REVIEW (ORG_MANAGER/ORG_OWNER) | Paginated list of documents with status `UPLOADED` for the caller's org, oldest first. |
| POST | `/api/v1/documents/{id}/review` | DOCUMENT:REVIEW | Claims a document for review — sets status to `UNDER_REVIEW` and records the reviewer. |
| POST | `/api/v1/documents/{id}/verify` | DOCUMENT:REVIEW | Marks a document `VERIFIED`, clears any prior rejection reason, stamps reviewer/time. |
| POST | `/api/v1/documents/{id}/reject` | DOCUMENT:REVIEW | Marks a document `REJECTED`, with an optional JSON body `{ "rejectionReason": "..." }`. |

All responses are wrapped in the shared `ApiResponse<T>` envelope from `libs/common`; paginated
responses use the shared `PageResult<T>`. OpenAPI/Swagger UI is exposed via springdoc at
`/swagger-ui.html` and `/v3/api-docs` (see Configuration).

## Kafka

- **Produces** to `platform.document.events` (`PlatformTopics.DOCUMENT_EVENTS`, defined in
  `libs/events`), a `DocumentEvent` payload with `eventType` one of `DOCUMENT_UPLOADED`,
  `DOCUMENT_VERIFIED`, `DOCUMENT_REJECTED` (note: `DOCUMENT_EXPIRED` is documented in the
  `DocumentEvent` Javadoc as a possible event type, but the `expireDocuments` scheduled job in
  `DocumentService` does **not** currently call `publisher.publish(...)` — no event is emitted
  when a document is auto-expired; this looks like a gap between the documented contract and
  actual behavior). Events are keyed by `"<tenantId>:<userId>"`.
- **Consumes**: no Kafka `@KafkaListener` consumers were found in this module's source. It is
  producer-only.
- `KafkaConfig` wires a `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer` (routes
  failed consumer records to `<topic>.DLT`) and a fixed backoff of 3 retries at 1s — this is
  general-purpose Kafka listener error-handling infrastructure; since no listeners currently
  exist, it is presently unused but ready if one is added.
- Producer config (`application.yml`): `acks=all`, `retries=3`, `enable.idempotence=true`,
  JSON value serialization.

## Configuration

Module: `apps/document-service` (Gradle project `:apps:document-service`), included via the
root `settings.gradle.kts`. Depends on `:libs:common`, `:libs:events`, `:libs:security`
(module dependencies), plus Spring Boot web/data-jpa/validation/actuator/kafka, Eureka client
+ Spring Cloud LoadBalancer, springdoc-openapi, Micrometer Prometheus, Logstash Logback
encoder, Flyway (core + postgresql), PostgreSQL driver, and Lombok. Java toolchain: 25
(inherited from root `build.gradle.kts`). Uses Flyway (`V1__document_schema.sql`) to create
the `documents` schema/table; Hibernate `ddl-auto` is `validate` (schema changes must go
through migrations, not auto-DDL).

### Profiles

Only two profile-related files exist in this module:

- `application.yml` — the base config. Everything datasource/Kafka-related is read from
  required env vars (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
  `SPRING_DATASOURCE_PASSWORD`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`) with no defaults — this file
  is meant to run as-is in Docker/production once those env vars are supplied.
- `application-loc.yml` (profile `loc`) — local, non-Docker development only: hardcodes
  `jdbc:postgresql://localhost:5435/platformdb` (user/pass `postgres`/`postgres`),
  `localhost:9092` for Kafka, server port `8081`, verbose SQL/Hibernate logging, and
  `platform.gateway.allow-insecure-default: true` (permits the default gateway shared secret
  locally — see Security below). A comment in `application.yml` explicitly warns not to set
  `spring.profiles.active: loc` as a blanket default so a packaged Docker image never picks
  this up; instead the root `build.gradle.kts` forces `spring.profiles.active=loc` only for the
  `bootRun` Gradle task.
- **No `application-docker.yml` / `application-prod.yml` exists.** In `docker-compose.yml`,
  `document-service` is started with `SPRING_PROFILES_ACTIVE=docker`, but since no matching
  profile file is present, this has no additional effect beyond the base `application.yml` —
  all docker-compose configuration is supplied purely via environment variables layered onto
  the base config.

### Key environment variables (from `docker-compose.yml` and `application.yml`)

| Variable | Purpose |
|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | PostgreSQL connection (schema `documents`) |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka broker(s) |
| `SERVER_PORT` | HTTP port (default `8080`; docker-compose maps host `8106` -> container `8080`) |
| `EUREKA_SERVER_URL` | Eureka registry URL (default `http://localhost:8761/eureka`) |
| `PLATFORM_GATEWAY_SHARED_SECRET` | Shared secret validating gateway-forwarded identity headers; service refuses to start if this is the well-known insecure default unless `PLATFORM_GATEWAY_ALLOW_INSECURE_DEFAULT=true` |
| `PLATFORM_GATEWAY_ALLOW_INSECURE_DEFAULT` | Bypasses the above check (used in docker-compose/`loc`) |
| `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_EXPORTER_OTLP_PROTOCOL` | OpenTelemetry export (set in docker-compose; not referenced directly in this module's own config files) |
| `platform.doc-types.refresh-ms` | How often `DocumentTypeRegistry` re-polls schema-registry (default 3,600,000 ms / 1 hour) |

Management endpoints exposed: `health`, `info`, `prometheus` (`management.endpoints.web.exposure.include`).
Multipart limits: `max-file-size: 20MB`, `max-request-size: 25MB` (Spring's servlet-level cap,
separate from and consistent with the 20MB application-level check in `DocumentService`).

## Running locally

Requires PostgreSQL (default expected at `localhost:5435`, db `platformdb`) and Kafka (default
`localhost:9092`) reachable, matching `application-loc.yml`. From the repo root:

```
SPRING_PROFILES_ACTIVE=loc ./gradlew :apps:document-service:bootRun
```

(the root build already forces the `loc` profile for `bootRun`, so `SPRING_PROFILES_ACTIVE` can
usually be omitted for this specific task, per the comment in the root `build.gradle.kts` — but
setting it explicitly is harmless and matches the comment in `application.yml`).

Default local port: `8081` (per `application-loc.yml`; differs from the `8080` used in the
packaged Docker image / `Dockerfile` `EXPOSE 8080`, and from the `8106` host port
docker-compose maps to it).

This service also expects `image-service` (for actual file storage) and `schema-registry` (for
the document type catalog) to be reachable via Eureka/load-balanced calls at `http://image-service`
and `http://schema-registry` respectively; without them, uploads will fail (image-service
unreachable) or the document type list will silently fall back to the static list described
above (schema-registry unreachable).

Via Docker Compose (from repo root), with the `apps` or `full` compose profile:

```
docker compose --profile apps up document-service
```

This starts `document-service` alongside `postgres` and `kafka` (declared as `depends_on` with
health checks) and maps host port `8106` to container port `8080`.

## Running tests

```
./gradlew :apps:document-service:test
```

**Notable**: the only test file in this module,
`src/test/java/com/lagu/platform/document/DocumentServiceIntegrationTest.java`, is **entirely
commented out** (the whole class body, including all `@Test` methods, is wrapped in `//` line
comments). As it stands, `./gradlew :apps:document-service:test` will compile and pass
trivially with zero tests actually executed — there is currently no active automated test
coverage for this service. The commented-out test does show what behavior was intended to be
covered, though, using Testcontainers (`postgres:16-alpine`) + `@EmbeddedKafka` + a mocked
`DocumentStorageService`:
upload success/validation-failure paths (resume, identity-proof with/without required sub-type,
invalid type), get-by-id, list-my-documents, submission-status (including `MISSING`/`UPLOADED`/
`VERIFIED` transitions), the HR review flow (`review` -> `verify`, and `review` -> `reject` with
a reason), the `pending-review` listing, and a role-based-access check that a plain staff user
gets `403 FORBIDDEN` on `pending-review`. Test dependencies present in `build.gradle.kts`
(`spring-boot-test`, `testcontainers-junit`/`testcontainers-postgresql`, `spring-kafka-test`)
support re-enabling this test as-is.

## Notable design decisions and gotchas

- **No local/S3 file storage in this service** — it is a thin proxy/metadata layer in front of
  `image-service`. Anyone looking for actual blob storage code (bucket names, disk paths, etc.)
  won't find it here.
- **`RestClient.Builder` bean collision with Eureka**: `DocumentServiceConfig` documents (via
  code comment) a specific problem — Eureka's own auto-configured HTTP client for
  registration/heartbeats autowires *any* unqualified `RestClient.Builder` bean, including a
  `@LoadBalanced` one, which breaks heartbeats by trying to load-balance requests to the literal
  Eureka server host. The fix here is a `@Primary`, non-load-balanced `RestClient.Builder` plus
  a separately `@Qualifier`-pinned load-balanced builder used explicitly by `imageRestClient`.
- **Two different HTTP client types in use**: `imageRestClient` (a load-balanced `RestClient`)
  is used for uploads to image-service; a separately configured load-balanced `RestTemplate` is
  used by `DocumentTypeRegistry` for polling schema-registry. This split is intentional per the
  code comments, not an inconsistency to "fix."
- `@EnableScheduling` is enabled at the application class level, backing both the daily
  document-expiry job and the hourly document-type-catalog refresh.
- Multi-tenancy: nearly every query and mutation in `DocumentService` scopes by both `userId`
  and `tenantId` from `PlatformSecurityContext` (derived from gateway-forwarded headers); a
  `PLATFORM_ADMIN` caller can bypass org scoping on `getById`/review actions via `findForContext`.
- The `DocumentReviewRequest` body on `/reject` is optional (`required = false`); if omitted,
  `rejectionReason` is stored as `null`.
