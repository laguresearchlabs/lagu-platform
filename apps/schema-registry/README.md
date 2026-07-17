# schema-registry

## What it does

`schema-registry` is the central metadata service for the lagu-platform. It defines and serves
the schema for every vendor "listing type" (VENUE, PHOTOGRAPHER, CATERER, DECORATOR,
MAKEUP_ARTIST, WEDDING_EVENT, CORPORATE_EVENT, VENDOR, etc.) as data instead of code: individual
fields, reusable groups of fields, and the sections that compose a listing type are all rows in
Postgres, not hardcoded DTOs. Other services (record-service, search-service, document-service,
listing-service) read this metadata at runtime to know what fields a listing type has, how to
validate/search/display them, what documents a vendor must upload, and what commission/SLA/limits
apply per verification tier. The service itself does not store any vendor or listing *data* — only
the schema, versioning, and business-rule configuration that governs that data elsewhere. Per
comments in the migrations, it absorbs and supersedes the old `metadata-service`.

## Domain model / responsibilities

All entities live in the `schema_registry` Postgres schema (`apps/schema-registry/src/main/resources/db/migration`).

- **FieldDefinition** (`field_definition`) — a single field (name, `FieldType` enum, validation
  rules, search/facet/sort/filter flags, enum values, nested `item_schema` for
  `ARRAY_OF_OBJECTS`). Unique on `(name, org_id)`; `org_id = NULL` means platform-level.
- **FieldGroup** (`field_group`) + **FieldGroupEntry** (`field_group_entry`) — a named, reusable
  ordered collection of fields (e.g. `pricing`, `address`, `venue_details`). The join table can
  override a field's `is_required` per group.
- **ListingTypeDefinition** (`listing_type_definition`) + **ListingTypeSection**
  (`listing_type_section`) — the top-level definition of a listing type (e.g. `VENUE`), composed
  of ordered sections, each backed by a `FieldGroup`. Carries `is_publishable`,
  `is_consumer_searchable`, and a `current_version` counter.
- **SchemaVersion** (`schema_version`) — an immutable JSONB snapshot of a listing type's schema
  taken each time it is published, with a `change_classification` (`SAFE` | `SOFT_BREAKING` |
  `HARD_BREAKING`) and free-text `change_summary`.
- **RelationshipDefinition** (`relationship_definition`) — how listing types reference each other
  (e.g. `WEDDING_EVENT` → `VENUE` as `ONE_TO_ONE`, → `PHOTOGRAPHER` as `MANY_TO_MANY`). Ported from
  `metadata-service`; ships org-scoped visibility and permission checks (see Gotchas).
- **SearchDefinition** (`search_definition`) — per-listing-type search configuration (consumer
  and admin facet field lists, default sort, boost field) consumed by `search-service`.
- **TierConfiguration** (`tier_configuration`) — business parameters per verification tier
  (`NONE`/`BASIC`/`ENHANCED`/`PREMIUM`) and optionally per listing type: commission rate, max
  active bookings, search boost factor, response SLA hours, expiry days, a JSONB `features` map.
- **TierEligibilityRule** (`tier_eligibility_rule`) — what a vendor must satisfy to be promoted to
  a tier (`DOCUMENT_VERIFIED`, `FIELD_CONDITION`, `MIN_BOOKINGS` rule types).
- **DocumentRequirement** (`document_requirement`) — document types required for onboarding, with
  per-tier requirement lists, allowed MIME types, and max size; also used for non-listing-type
  "HR" documents (`listing_type IS NULL`, e.g. resume, ID proof) consumed by `document-service`.
- **CategoryDefinition** (`category_definition`) — hierarchical taxonomy per listing type
  (self-referencing parent/child), e.g. Wedding Services → Wedding Photography → Candid
  Photography. No REST controller exists for this entity (see Gaps below).
- **CountryValidationConfig** (`country_validation_config`) — per-country regex validation rules
  (PAN, GSTIN, IFSC, phone, pincode, account number) plus currency/tax-label/dial-code. Only India
  (`IN`) is seeded. No REST controller exists for this entity either.

On startup, `SchemaRegistrySeeder` (an `ApplicationRunner`, gated by `platform.seeder.enabled`)
idempotently seeds ~70 platform-level fields, ~14 field groups, 8 listing types, 4 tier
configurations, vendor + HR document requirements, a handful of tier eligibility rules, the India
validation config, a category tree, and 5 relationship definitions for the wedding vertical. This
is what a fresh database looks like out of the box.

## REST API

All responses are wrapped in `com.lagu.platform.common.dto.ApiResponse<T>` (`{success, data,
error}`), from `libs/common`. Swagger UI is available at `/swagger-ui.html`, OpenAPI JSON at
`/v3/api-docs` (springdoc).

### Listing Types — `/api/v1/listing-types`
| Method | Path | Purpose |
|---|---|---|
| GET | `/` | List active, platform-level listing types |
| GET | `/{name}` | Get one listing type by name |
| GET | `/{name}/schema` | Get the current composed schema (sections + fields), cached |
| GET | `/{name}/schema/version/{version}` | Get a specific published schema version snapshot |
| POST | `/` | Create a listing type (with optional sections) |
| PUT | `/{id}` | Update a listing type's metadata |
| POST | `/{name}/sections` | Append a section (backed by an existing field group) |
| POST | `/{name}/publish` | Publish current schema as a new immutable `SchemaVersion`; emits `SCHEMA_PUBLISHED` |
| DELETE | `/{id}` | Soft-deactivate a listing type |

### Fields — `/api/v1/fields`
GET `/`, GET `/{id}`, POST `/`, PUT `/{id}`, DELETE `/{id}` (soft-deactivate) — platform-level
`FieldDefinition` CRUD.

### Field Groups — `/api/v1/field-groups`
GET `/`, GET `/{id}`, POST `/`, PUT `/{id}`, DELETE `/{id}` (soft-deactivate) — platform-level
`FieldGroup` CRUD.

### Relationship Definitions — `/api/v1/relationship-definitions`
| Method | Path | Purpose |
|---|---|---|
| GET | `/` | List (org-scoped if caller has an org, else platform-level) |
| GET | `/{id}` | Get by id (org-visibility enforced) |
| GET | `/by-name/{name}` | Get by name, org-scoped with platform-level fallback |
| POST | `/` | Create |
| PUT | `/{id}` | Update |
| DELETE | `/{id}` | Soft-deactivate |

This is the only controller gated by `@RequirePermission` (resource `RELATIONSHIP`, actions
READ/CREATE/UPDATE/DELETE), enforced by `RequirePermissionAspect` in `libs/security` against the
`X-*` headers set by the gateway (`GatewayHeaderFilter` / `PlatformSecurityContext`). It is also
the only entity/service with explicit org-scoping logic (`findAllForOrg` vs
`findAllPlatformLevel`, `canReadOrgScoped`/`canWriteOrgScoped`).

### Search Definitions — `/api/v1/search-definitions`
GET `/`, GET `/{listingType}`, POST `/` (upsert by listing type), DELETE `/{listingType}`.

### Tier Configs — `/api/v1/tier-configs`
GET `/`, GET `/{tierName}?listingType=` (falls back to the listing-type-agnostic config if no
listing-type-specific override exists), POST `/`, PUT `/{id}`.

### Tier Rules — `/api/v1/tier-rules`
| Method | Path | Purpose |
|---|---|---|
| GET | `/?listingType=&tier=` | List eligibility rules |
| GET | `/{id}` | Get one rule |
| POST | `/` | Create a rule |
| PATCH | `/{id}/active` | Toggle a rule active/inactive |
| DELETE | `/{id}` | Delete a rule |
| GET | `/check?recordId=&targetTier=&listingType=` | Evaluate tier eligibility for a record |

### Document Requirements — `/api/v1/document-requirements`
| Method | Path | Purpose |
|---|---|---|
| GET | `/?listingType=` | List requirements (platform-wide if omitted) |
| GET | `/catalog` | Full active platform-level catalog regardless of listing type — used by `document-service` |
| GET | `/{id}` | Get one |
| POST | `/` | Create |
| PUT | `/{id}` | Update |
| PATCH | `/{id}/active` | Toggle active |
| DELETE | `/{id}` | Delete |

No controllers exist for `CategoryDefinition` or `CountryValidationConfig` even though both have
entities, repositories, and seed data — they are populated but not yet exposed over REST.

## Kafka

- **Produces** `platform.schema.events` (`PlatformTopics.SCHEMA_EVENTS`, from `libs/events`) — a
  `SchemaPublishedEvent` (`eventType=SCHEMA_PUBLISHED`, `listingType`, `version`,
  `changeClassification`, `publishedBy`, `orgId`, `occurredAt`) each time
  `POST /api/v1/listing-types/{name}/publish` succeeds. Keyed by `listingType`.
- **Consumes** nothing — no `@KafkaListener` exists in this module.

Events are not sent directly to Kafka. `SchemaEventPublisher` stages the event into the
`schema_outbox` table (migration `V3__schema_outbox.sql`) inside the same transaction as the
`SchemaVersion` insert, via the shared `TransactionalOutbox`/`OutboxStore` in `libs/common`. A
shared `OutboxRelay` component (also in `libs/common`, activated here via
`platform.outbox.enabled=true` and `@EnableScheduling` on the application class) polls
unpublished rows (`FOR UPDATE SKIP LOCKED`, default every 1s), sends them to Kafka in order, and
purges published rows older than 7 days on a daily cron. Delivery is at-least-once — consumers
must tolerate duplicate `SCHEMA_PUBLISHED` events.

## Configuration

`src/main/resources/application.yml` (base) + `application-loc.yml` (local profile).

Base config (env-var driven, no defaults for datasource/Kafka on purpose — see Gotchas):

| Property | Value / source |
|---|---|
| `spring.application.name` | `schema-registry` |
| `spring.datasource.url` / `username` / `password` | `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` |
| `spring.jpa.hibernate.ddl-auto` | `validate` (schema is Flyway-managed) |
| `spring.jpa.properties.hibernate.default_schema` | `schema_registry` |
| `spring.flyway.schemas` | `schema_registry` |
| `spring.flyway.baseline-on-migrate` | `true` |
| `spring.data.redis.host` / `port` | `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` |
| `spring.kafka.bootstrap-servers` | `SPRING_KAFKA_BOOTSTRAP_SERVERS` |
| `spring.kafka.producer.acks` | `all`, 3 retries, idempotence enabled |
| `server.port` | `SERVER_PORT` (default `8080`) |
| `eureka.client.service-url.defaultZone` | `EUREKA_SERVER_URL` (default `http://localhost:8761/eureka`), registers with Eureka |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` |
| `springdoc.swagger-ui.path` / `api-docs.path` | `/swagger-ui.html` / `/v3/api-docs` |
| `platform.seeder.enabled` | `true` — controls `SchemaRegistrySeeder` |
| `platform.cache.schema-ttl-minutes` | `5` — **not currently read by any code** (see Gotchas) |
| `platform.cache.tier-config-ttl-minutes` | `10` — **not currently read by any code** (see Gotchas) |
| `platform.outbox.enabled` | `true` — activates the shared `OutboxRelay` |
| `platform.outbox.table` | `schema_outbox` (documentation only; relay reads via `OutboxStore`, table name isn't parameterized from this property in what was inspected) |

Profiles: only one is defined in this module — `loc` (`application-loc.yml`), for local
non-Docker development. It hardcodes:
- Postgres: `jdbc:postgresql://localhost:5435/platformdb`, user/pass `postgres`/`postgres`
- Redis: `localhost:6380`
- Kafka: `localhost:9092`
- `server.port: 8090`
- DEBUG logging for `com.lagu.platform`, Hibernate SQL, and Spring Data Redis
- `platform.gateway.allow-insecure-default: true`

There is no `application-dev.yml` or `application-prod.yml` in this module — only `loc` and the
profile-less base file, which is what a packaged Docker image runs (`java -jar app.jar` with no
profile set, so it uses the base `application.yml` and requires all the `SPRING_*`/`SERVER_PORT`
env vars above to be supplied externally, e.g. via `docker-compose.yml`).

`SPRING_PROFILES_ACTIVE=loc` is applied automatically for `bootRun` at the root `build.gradle.kts`
level (see `tasks.withType<BootRun>`) so `./gradlew :apps:schema-registry:bootRun` "just works"
locally without exporting it yourself; it has no effect on the built JAR/Docker image.

## Running locally

Dependencies: Postgres, Redis, and Kafka. The repo's root `docker-compose.yml` provides all three
with the ports the `loc` profile expects (Postgres on host port 5435 → `platformdb`, Redis on
6380, Kafka on 9092):

```bash
docker compose up -d postgres redis kafka
```

Then, from the repo root:

```bash
./gradlew :apps:schema-registry:bootRun
```

This runs with the `loc` profile (see above) on port **8090**. Flyway runs the migrations
automatically (`baseline-on-migrate: true`), and `SchemaRegistrySeeder` populates reference data on
first boot (set `platform.seeder.enabled=false` to skip). The module also registers with Eureka at
`http://localhost:8761/eureka` by default — a discovery server must be reachable for that
registration to succeed, though nothing in this module hard-fails if it isn't.

Swagger UI: `http://localhost:8090/swagger-ui.html`.

To run the packaged jar / Docker image directly (`Dockerfile` builds from `build/libs/*-SNAPSHOT.jar`,
exposes 8080), the `SPRING_*` env vars (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`, `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`,
`SPRING_KAFKA_BOOTSTRAP_SERVERS`) must be supplied — there is no `loc` fallback baked into the
image.

## Tests

```bash
./gradlew :apps:schema-registry:test
```

**There are currently no active tests.** The only test file,
`src/test/java/com/lagu/platform/schema/SchemaRegistryApplicationTest.java`, is entirely commented
out. It would otherwise be a Testcontainers-backed Spring context-load test (spins up a real
`postgres:16` container, points `spring.datasource.*` at it, disables the seeder, and asserts the
context loads with Flyway migrations applied) — but as committed, `./gradlew test` for this module
runs zero tests. The module does declare `testImplementation` on
`spring-boot-starter-test` and `testcontainers` (junit + postgresql), so the scaffolding for that
test is present but not enabled.

## Design decisions and gotchas

- **Data-driven schema, not code-driven.** Listing type shape (which fields, which sections, what
  validation) is entirely rows in Postgres, versioned via `SchemaVersion` snapshots. This is the
  core reason the service exists: other services can add/change vendor listing fields without a
  code deploy.
- **Change classification is not actually computed.** `SchemaVersion.changeClassification` and the
  `SCHEMA_PUBLISHED` event both carry a `SAFE | SOFT_BREAKING | HARD_BREAKING` field, and the
  column/comments clearly anticipate real compatibility analysis (diffing the old vs. new schema
  snapshot). In the current code, `SchemaVersionService.publish()` hardcodes `"SAFE"` for every
  publish — there is no diffing logic that ever produces `SOFT_BREAKING` or `HARD_BREAKING`.
  Anything downstream that keys behavior off this field is not exercised.
- **Tier eligibility checking is a stub.** `TierCheckService.check()` evaluates
  `TierEligibilityRule`s but every branch (`DOCUMENT_VERIFIED`, `FIELD_CONDITION`, `MIN_BOOKINGS`)
  returns `satisfied = false` with a "pending integration" message — it does not call
  document-service, evaluate actual record field values, or query booking counts. `GET
  /api/v1/tier-rules/check` therefore never reports a vendor as eligible for anything today.
- **Cache TTL config properties are dead.** `application.yml` defines
  `platform.cache.schema-ttl-minutes: 5` and `platform.cache.tier-config-ttl-minutes: 10`, but
  `SchemaRegistryCacheConfig` hardcodes `Duration.ofMinutes(5)` / `Duration.ofMinutes(10)` directly
  in Java rather than reading those properties (the values happen to match today, but changing the
  YAML has no effect).
- **Redis-backed response caching.** `ListingTypeService.getSchema()` (cache
  `schema-registry:schema`) and `TierConfigService.getByTierName()` (cache
  `schema-registry:tier-config`) are `@Cacheable` via a custom `JacksonRedisSerializer`
  (`libs/common`). Publishing a schema evicts the schema cache for that listing type;
  `ListingTypeService.update()` does the same.
- **Transactional outbox instead of direct Kafka sends.** Publishing a schema version and emitting
  `SCHEMA_PUBLISHED` happen in one DB transaction; a shared background relay
  (`libs/common`'s `OutboxRelay`) delivers to Kafka afterward with retries, so a schema publish can
  never "succeed" without eventually notifying consumers (or vice versa).
- **Inconsistent authorization coverage.** Only `RelationshipDefinitionController` is gated by
  `@RequirePermission` / org-scoping. Every other controller (listing types, fields, field groups,
  search definitions, tier configs/rules, document requirements) has no method-level permission
  annotations and no org-scoping logic in its service — they operate purely at the platform level
  (`org_id IS NULL`) regardless of caller identity. This is consistent with most entities being
  platform-wide reference data, but it means any org-scoped multi-tenant story for those entities
  doesn't exist yet.
- **Two entities are seeded but not exposed.** `CategoryDefinition` and `CountryValidationConfig`
  have entities, repositories, and seed data (a category taxonomy and an India validation config)
  but no REST controller — there is currently no way to read or modify them over HTTP.
- **Soft-delete is inconsistent across resources.** Fields, field groups, listing types, document
  requirements, and relationship definitions are deactivated (`is_active = false`) rather than hard
  deleted on their `DELETE` endpoints — except `DocumentRequirementService.delete()`, which calls
  `repository.delete(doc)` (a real row delete), and `SearchDefinitionService.delete()`, which also
  hard-deletes.
- **`FieldType.ARRAY_OF_OBJECTS`** is the one type with a genuinely different modeling: its schema
  for each array item is stored inline as a JSONB array of field-definition-like objects
  (`item_schema`), not as references to other `FieldDefinition` rows.
- **Seeder is idempotent and vertical-specific.** `SchemaRegistrySeeder` seeds ~70 fields, 14 field
  groups, and 8 listing types aimed squarely at an Indian wedding/events marketplace (GSTIN/PAN
  validation, INR currency, wedding sub-events like Mehendi/Haldi/Sangeet, vendor types like
  photographer/caterer/decorator/makeup artist). It checks for existing rows before inserting, so
  re-running it (e.g. every boot) is safe.
