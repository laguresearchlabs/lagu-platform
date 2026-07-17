# vendor-service

## What it does

`vendor-service` owns vendor-org onboarding and KYC readiness for the lagu-platform. A vendor
"registers" (self-service, one organisation per business), which creates a canonical `VENDOR`
record in `record-service`, a local `vendor_profile` row, an `OWNER` membership row, and an empty
KYC checklist; it then asks `user-service` (IAM) to associate the new orgId with the registering
user so future JWTs carry that orgId. From there the service tracks the vendor's review-status
lifecycle (`DRAFT → SUBMITTED → UNDER_REVIEW → ACTIVE/REJECTED → SUSPENDED`) and computes/caches a
KYC checklist by asking `record-service` (which proxies `document-service`) whether the vendor has
verified GST, PAN, bank, and identity documents on file. It is a small orchestration/aggregation
service, not a document store or a listing catalog — those responsibilities live in
`document-service` / `record-service` and (per the module list) `listing-service`.

## Architecture / domain ownership

The service owns three tables, one per JPA entity, all keyed by `org_id` (the platform `orgId`
**is** the vendor's id — one vendor = one org, per the comment in `V1__vendor_schema.sql`):

- `vendor_profile` — business name, country, review `status`, link to the `record_id` in
  record-service, the `owner_user_id`, and an optimistic-lock `version` column (added in
  `V2__optimistic_locking.sql` specifically so concurrent profile edits / admin status changes
  fail with a conflict instead of silently clobbering each other).
- `vendor_member` — org membership rows (`OWNER`/`ADMIN`/`MEMBER`), with a repository
  (`VendorMemberRepository`) supporting lookups by org/user. **Only the `OWNER` row is ever
  written** (during registration in `VendorService.register`); there is no controller endpoint to
  invite, list, promote, or remove members. The membership data model exists but member
  management is not yet exposed over REST.
- `vendor_kyc_checklist` — a cached snapshot (`has_gst_doc`, `has_pan_doc`, `has_bank_doc`,
  `has_identity_doc`, `business_name_filled`, `address_filled`, `phone_filled`, `kyc_ready`,
  `last_computed_at`) recomputed on demand by `VendorService.computeKyc`. Note
  `address_filled`/`phone_filled` are columns on the entity/DTO but nothing in the service code
  ever sets them true — they stay `false` unless set some other way.

Two outbound HTTP clients (via a Eureka-discovered, load-balanced `RestClient`, see
`LoadBalancerConfig`) integrate with the rest of the platform:

- `RecordServiceClient` → `record-service` (`http://record-service`): creates the canonical
  `VENDOR` record on registration, can fetch a record by id, and fetches document verification
  status (`GET /api/v1/documents/submission-status`) used to compute the KYC checklist. Calls
  carry `X-Internal-Service: vendor-service` and `X-Platform-Gateway-Secret`.
- `IamServiceClient` → `user-service` (`http://user-service`, Eureka app name `user-service`,
  described in-code as "iam-service"): `PUT /api/v1/users/{userId}/platform-org/{orgId}` to
  associate the new org with the registering user, forwarding the caller's bearer token.

Both client calls are best-effort: failures are logged and swallowed (`RecordServiceClient`
returns `null`/empty map; `IamServiceClient` just logs). `VendorService.register` does **not**
roll back the record-service record or local rows if the IAM association call fails, so a vendor
can end up registered locally and in record-service without the IAM org association actually
having landed — there's no saga/outbox/retry compensating for that.

## REST API (`VendorController`, base path `/api/v1/vendors`)

All endpoints require an authenticated `PlatformSecurityContext` (populated by
`GatewayHeaderFilter` from trusted `X-User-Id`/`X-Org-Id`/`X-User-Roles` headers, see below) —
anonymous calls get a `ValidationException` ("Authentication required").

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/vendors/register` | Self-registration: creates org, `VENDOR` record, owner membership, empty KYC row; associates org with the caller in IAM. 409-equivalent `IllegalStateException` if the user already has a profile. |
| GET | `/api/v1/vendors/me` | Authenticated vendor's own profile (with KYC checklist if computed). |
| POST | `/api/v1/vendors/me/submit` | Moves the caller's org from `DRAFT` to `SUBMITTED` for admin review. |
| GET | `/api/v1/vendors/me/kyc` | Recomputes and returns the KYC checklist for the caller's org. |
| GET | `/api/v1/vendors/{orgId}` | Admin: fetch any vendor profile by org id. |
| GET | `/api/v1/vendors?status=SUBMITTED` | Admin: list vendor profiles by status (defaults to `SUBMITTED`). |
| PATCH | `/api/v1/vendors/{orgId}/status` | Admin: change a vendor's status (approve/suspend/reject/etc.), subject to the transition table below. |

Admin endpoints require `ctx.isConfigAdmin()` (role `CONFIG_ADMIN` or `PLATFORM_ADMIN`); otherwise
a 403 `ResponseStatusException` is thrown.

Allowed status transitions (`VendorService.validateStatusTransition`):

```
DRAFT        -> SUBMITTED
SUBMITTED    -> UNDER_REVIEW, DRAFT
UNDER_REVIEW -> ACTIVE, REJECTED
ACTIVE       -> SUSPENDED
SUSPENDED    -> ACTIVE, REJECTED
REJECTED     -> DRAFT
```

Any other transition throws `IllegalStateException`.

Responses are wrapped in the shared `com.lagu.platform.common.dto.ApiResponse` envelope; error
handling for exceptions (`ValidationException`, `NoSuchElementException`, etc.) is inherited from
`libs/common`'s `GlobalExceptionHandler`.

There is no dedicated `OpenApiConfig`/Swagger customization class in this module, but
`springdoc-openapi` is on the classpath (from the shared version catalog), so the default
springdoc UI/JSON endpoints are available with no additional wiring.

## Kafka

**None currently.** The module depends on `spring-boot-starter-kafka` and `application.yml`
configures a producer/consumer (`bootstrap-servers`, JSON (de)serializers, consumer
`group-id: vendor-service`), but there is no `@KafkaListener`, no `KafkaTemplate` usage, and no
topic constant for vendor events anywhere in the 15 source files. `libs/events`' `PlatformTopics`
defines topics for schema, record, workflow, automation, document, verification, listing, and
booking events, but no `VENDOR_EVENTS`/similar topic — vendor-service neither produces nor
consumes any Kafka topic today. The Kafka config appears to be present for future use (or copied
from a shared template) rather than active integration.

Similarly, `@EnableScheduling` is set on `VendorServiceApplication`, but there is no `@Scheduled`
method anywhere in the module — also apparently unused/reserved for future work.

## Configuration

Base config: `src/main/resources/application.yml`.

| Property | Default | Env var |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/lagu_vendor` | `DB_URL` |
| `spring.datasource.username` | `lagu` | `DB_USERNAME` |
| `spring.datasource.password` | `lagu` | `DB_PASSWORD` |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | `KAFKA_BOOTSTRAP` |
| `server.port` | `8107` | `SERVER_PORT` |
| `eureka.client.service-url.defaultZone` | `http://localhost:8761/eureka` | `EUREKA_SERVER_URL` |

Other fixed settings: `spring.jpa.hibernate.ddl-auto=validate` (schema is managed exclusively by
Flyway, `classpath:db/migration`), `open-in-view=false`, Eureka `prefer-ip-address: true` with a
10s/30s lease renewal/expiration, and Actuator exposing `health`, `info`, `prometheus` with full
health details.

`platform.gateway.shared-secret` (read by `RecordServiceClient` and, platform-wide, by
`GatewayHeaderFilter`/`ServiceSecurityConfig` in `libs/security`) defaults to
`CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD` if unset — must be overridden with a real
shared secret in any non-local environment, or the gateway-trust filter refuses to trust identity
headers at all (fails closed, per `GatewayHeaderFilter`'s logged warning).

### Profiles

Only one profile file exists in this module: `application-loc.yml` (`on-profile: loc`), intended
for local, non-Docker/IDE development. It hardcodes:
- Postgres at `localhost:5435`, database `platformdb`, schema `vendor` (both Hikari and Flyway
  pinned to that schema)
- Kafka at `localhost:9092`
- Redis at `localhost:6380` (configured here but **not referenced anywhere** in this module's Java
  code — likely inherited from a shared template/boilerplate, currently unused by vendor-service)
- `platform.gateway.allow-insecure-default: true`
- DEBUG logging for `com.lagu.platform`

There is no `application-dev.yml` or `application-prod.yml` in this module — non-local
environments are expected to be driven entirely by the env vars in the table above plus whatever
profile is passed via `SPRING_PROFILES_ACTIVE`. The root `build.gradle.kts` explicitly does *not*
default `spring.profiles.active` to `loc` (to avoid a packaged Docker image accidentally picking
up `application-loc.yml`'s hardcoded localhost config) — it's the root build's `bootRun` task that
sets `-Dspring.profiles.active=loc` for local Gradle runs.

**Gotcha:** the `Dockerfile` has `EXPOSE 8080`, but the app's configured port is `8107`
(`server.port`, default in `application.yml`). `EXPOSE` is only documentation to Docker — the JVM
will still bind to 8107 unless `SERVER_PORT` is overridden — but the mismatch looks like a
copy-paste leftover from a template and could mislead anyone wiring up container networking.

## Running locally

Requires PostgreSQL reachable at `localhost:5435` with database `platformdb` (user/password
`postgres`/`postgres`) and a `vendor` schema — matching the `loc` profile — plus Kafka reachable
at `localhost:9092` (even though this service does not currently consume/produce anything, the
Kafka autoconfiguration will still try to connect). Eureka is expected at
`http://localhost:8761/eureka` for the load-balanced `record-service`/`user-service` client calls
to resolve; without a running Eureka + those services, registration (`POST .../register`) and KYC
computation (`GET .../me/kyc`) will fail (client calls log and continue, but downstream data won't
be created).

```
SPRING_PROFILES_ACTIVE=loc ./gradlew :apps:vendor-service:bootRun
```

or, since the root build already defaults `bootRun` to the `loc` profile:

```
./gradlew :apps:vendor-service:bootRun
```

Default port: `8107` (overridable via `SERVER_PORT`).

Flyway migrations run automatically on startup (`spring.flyway.enabled: true`,
`classpath:db/migration`): `V1__vendor_schema.sql` creates `vendor_profile`, `vendor_member`,
`vendor_kyc_checklist`; `V2__optimistic_locking.sql` adds the `version` column to
`vendor_profile`.

## Running tests

```
./gradlew :apps:vendor-service:test
```

**There is currently no `src/test` directory in this module** — no unit or integration tests
exist, despite `build.gradle.kts` declaring `spring-boot-starter-test`,
`testcontainers-junit-jupiter`, `testcontainers-postgresql`, and `spring-kafka-test` as test
dependencies. Running the command above will succeed trivially (no tests to run) rather than
exercise any behavior.

## Migration status

This is a partial re-implementation of the legacy `vendor-service` at
`/mnt/c/git/lagu/vendor-management/apps/vendor-service`. Comparing package layouts, the legacy
service is substantially larger and covers areas not yet present here:

- Vendor detail data: business identity, tax info, bank info, addresses, phone/email records,
  social links, notification preferences, account settings.
- Document management: vendor document upload/verification (`VendorDocument`,
  `VerifyDocumentRequest`) — here, this service only *reads* document verification status from
  record-service/document-service, it doesn't manage documents itself.
- Vendor listings (`VendorListing`, `VendorListingService`, `VendorListingController`) — not
  present in lagu-platform's vendor-service; per `settings.gradle.kts` this now looks like it's
  meant to live in the separate `apps:listing-service` module.
- A change-request/admin-review workflow (`ChangeRequestService`, `AdminReviewService`, bulk
  review, per-section change requests) — not present here; the current service only supports a
  flat status-transition model with no change-request/audit trail of *what* changed.
- Service-type catalog (`ServiceTypeController`/`ServiceType`) — not present here.
- Team/user management with roles (`VendorUserController`, `VendorUserService`,
  `VendorUserRole`) — the closest analogue here (`vendor_member` table) has no controller at all.

In short: registration, a single-owner membership row, status lifecycle, and KYC-readiness
checking are implemented; most of the richer vendor-profile editing, document verification
workflow, listings, change-request review, and team management from the legacy service have not
been ported yet.
