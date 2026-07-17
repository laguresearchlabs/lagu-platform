# search-service

## What it does

`search-service` is the query/read path for the lagu-platform: it maintains OpenSearch indexes
built from domain events produced by other services and exposes REST endpoints for full-text
search, faceted filtering, typeahead suggestions, and marketplace (consumer) search over
published listings. It does not own any relational data of its own (its `DataSourceAutoConfiguration`,
`HibernateJpaAutoConfiguration`, and `FlywayAutoConfiguration` are explicitly excluded in
`application.yml`) — it is a pure projection: record-service and listing-service are the systems
of record, and search-service asynchronously builds a denormalized, per-org-and-per-object-type
search index from their Kafka events so that queries never have to fan out to those services
directly.

## Architecture / responsibilities

- **Search backend**: OpenSearch (`org.opensearch.client:opensearch-java` /
  `opensearch-rest-client`, v2.19.0), accessed through `OpenSearchClient`
  (`config/OpenSearchConfig.java`), a plain low-level REST client pointed at
  `opensearch.host:opensearch.port` — no TLS/auth configuration is present.
- **Index layout**: one index per org+objectType for tenant data
  (`platform-<orgId>-<objectType>`, built in `IndexMappingBuilder.indexName`), plus one
  cross-org index per objectType for published marketplace listings
  (`platform-consumer-<objectType>`, `IndexMappingBuilder.consumerIndexName`). The
  `opensearch.index-prefix` property controls the `platform` prefix (default `platform`).
- **Index name validation**: org/objectType segments are validated against
  `^[a-zA-Z0-9_-]+$` before being concatenated into an index name — OpenSearch treats `,` as a
  multi-index separator and `*` as a wildcard, so an unvalidated segment could let a caller query
  across other orgs' indices (`IndexMappingBuilder.SAFE_INDEX_SEGMENT`).
- **Mapping is schema-derived**: `IndexMappingBuilder` fetches the listing-type's field schema
  from `schema-registry` via `MetadataClient` (result cached under `search:schema`, Redis-backed)
  and maps each field's `AttributeType` to an OpenSearch property: `TEXT` → text+keyword subfield,
  `LONG_TEXT` → text, `NUMBER` → integer, `DECIMAL`/`CURRENCY` → double, `BOOLEAN` → boolean,
  `DATE`/`DATETIME` → date, `GEOLOCATION` → geo_point, everything else → keyword. Indexes are
  created lazily (`createIfAbsent`) the first time a record of that org/objectType is indexed, with
  1 shard / 0 replicas.
- **Defense in depth on org scoping**: every tenant-scoped query filters by `orgId` at the
  document level in addition to relying on per-org index isolation
  (`SearchService.buildQuery`).
- **Consumer/marketplace search is deliberately public** (see `platform.security.public-paths:
  /api/v1/search/consumer` in `application.yml`): the consumer index only ever contains
  workflow-approved `PUBLISHED` snapshots, so no auth or org filter is needed at query time;
  relevance is instead boosted by a `function_score` query that multiplies BM25 score by the
  vendor's tier-derived `searchBoost` field (default 1.0 if missing).
- **Redis** is used for two things: the schema cache (`CacheConfig`, 10-minute TTL, Jackson
  serialization via `libs:common`'s `JacksonRedisSerializer`) and nothing else observed in the code
  — there is no document-level caching of search results themselves.
- **Service discovery**: registers with Eureka (`eureka-client`) and calls `record-service` and
  `schema-registry` by service name through a `@LoadBalanced` `RestClient.Builder`
  (`config/LoadBalancerConfig.java`). A non-load-balanced `@Primary` `RestClient.Builder` is also
  defined solely to work around a Spring Cloud Netflix bug where Eureka's own heartbeat client
  otherwise picks up the load-balanced builder and tries to load-balance requests to the literal
  Eureka host (see the comment in `LoadBalancerConfig.java` referencing
  spring-cloud-netflix#4382).
- **Auth**: inherits `libs:security`'s `GatewayHeaderFilter`/`ServiceSecurityConfig`. Identity
  (`X-User-Id`/`X-Org-Id`/`X-User-Roles` or `X-Internal-Service`) is only trusted when the request
  carries a valid `X-Platform-Gateway-Secret`; `@RequirePermission` annotations on controller
  methods gate access by resource/action. Calls search-service itself makes to record-service and
  schema-registry set `X-Internal-Service: search-service` and the shared gateway secret.

## REST API

All endpoints are under the service's own port; there is no visible API gateway routing config in
this module.

| Method | Path | Purpose | Auth |
|---|---|---|---|
| `POST` | `/api/v1/search` | Full-text query + filters + sort + facets over the caller's org's index for a given `objectType` (`SearchRequest.objectType` is required). Requires `RECORD:READ` permission. Org is taken from the gateway-supplied `X-Org-Id`. | `@RequirePermission(resource="RECORD", action="READ")` |
| `POST` | `/api/v1/search/consumer` | Public marketplace search across all vendors' `PUBLISHED` listings for an `objectType`, tier-boosted by `searchBoost`. | Public (listed in `platform.security.public-paths`) |
| `GET` | `/api/v1/search/suggest?objectType=&field=&prefix=` | Typeahead: prefix-filter + terms aggregation on `field`, returns up to 10 distinct values, for the caller's org. Requires `RECORD:READ`. | `@RequirePermission(resource="RECORD", action="READ")` |
| `POST` | `/admin/reindex/{objectType}` | Kicks off an async full reindex of every record of `objectType` for the caller's org by paginating `record-service`. Returns `202 Accepted` immediately with `{status, objectType, orgId}`. | `@RequirePermission(resource="*", action="UPDATE")` |

`SearchRequest` body shape: `objectType` (required), `query` (string, null = match-all), `filters`
(map of field → exact value, or `{gte,lte,gt,lt}` for ranges), `sort` (list of `{field, order}`,
order defaults to `asc`), `facets` (list of field names to aggregate, max 20 buckets each), `page`
(default 0), `size` (default 20).

`SearchResponse` shape: `total`, `page`, `size`, `results` (`recordId`, `objectType`, `status`,
`data`, `score`), `facets` (map of field → list of `{value, count}`).

I/O errors talking to OpenSearch are translated to `503 Service Unavailable`
(`SearchExceptionHandler`).

OpenAPI docs are served via springdoc at `/swagger-ui.html` and `/v3/api-docs`.

## Kafka topics

Consumes (all via `spring-kafka`, manual ack mode, `JsonDeserializer` trusting only
`com.lagu.platform.events`; `KafkaConfig` wires a `DefaultErrorHandler` that retries 3 times with a
1s fixed backoff before publishing to a `<topic>.DLT` dead-letter topic):

| Topic (constant) | Consumer group | Handler | Behavior |
|---|---|---|---|
| `platform.record.events` (`PlatformTopics.RECORD_EVENTS`) | `search-service` | `SearchDocumentIndexer` | `CREATED`/`UPDATED` → full document reindex; `STATUS_CHANGED` → partial update of `status`+`updatedAt` only; `DELETED` → delete from the per-org/objectType index; other event types ignored. |
| `platform.listing.events` (`PlatformTopics.LISTING_EVENTS`) | `search-service` | `ListingEventConsumer` | `PUBLISHED` → upserts the approved snapshot into the cross-org consumer index (`platform-consumer-<objectType>`) with `verificationTier`/`searchBoost`/`publishedAt`; `UNPUBLISHED` → deletes it from that index; other event types ignored. |
| `platform.schema.events` (`PlatformTopics.SCHEMA_EVENTS`) | `search-service-metadata` | `MetadataChangedConsumer` | On `SCHEMA_PUBLISHED`, evicts the `search:schema` Redis cache entry for the affected `listingType` so `MetadataClient` re-fetches the new schema on next use. |

Does not produce any application events (the only Kafka producer usage is the
`DeadLetterPublishingRecoverer`'s implicit publish to `<topic>.DLT` on exhausted retries).

Note: there is no consumer for `platform.workflow.events`, `platform.verification.events`, or
`platform.booking.events` in this module, even though `PlatformTopics` defines them — this service
only reacts to record, listing, and schema events.

## Configuration

Base config: `src/main/resources/application.yml`. Local-only profile: `application-loc.yml`
(activate with `SPRING_PROFILES_ACTIVE=loc`; the root Gradle build also sets this profile
automatically for `bootRun` — see the comment in the root `build.gradle.kts` — but it has no effect
on the packaged jar/Docker image).

Key properties / env vars (base `application.yml`):

| Property | Env var | Default | Notes |
|---|---|---|---|
| `spring.data.redis.host` / `.port` | `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` | none (required) | Schema cache backend. |
| `spring.kafka.bootstrap-servers` | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | none (required) | |
| `opensearch.host` / `opensearch.port` | `OPENSEARCH_HOST` / `OPENSEARCH_PORT` | none (required) | |
| `opensearch.index-prefix` | `OPENSEARCH_INDEX_PREFIX` | `platform` | |
| `server.port` | `SERVER_PORT` | `8080` | `8082` in the `loc` profile. |
| `eureka.client.service-url.defaultZone` | `EUREKA_SERVER_URL` | `http://localhost:8761/eureka` | |
| `platform.security.public-paths` | — | `/api/v1/search/consumer` | Path prefixes exempt from auth (see `ServiceSecurityConfig` in `libs:security`). |
| `platform.gateway.shared-secret` | `PLATFORM_GATEWAY_SHARED_SECRET` | insecure well-known default (from `libs:security`) | Must be set to a real secret in any non-local environment; `ServiceSecurityConfig` refuses to start otherwise unless `platform.gateway.allow-insecure-default=true`. |

`loc`-profile overrides (`application-loc.yml`): Redis at `localhost:6380`, Kafka at
`localhost:9092`, OpenSearch at `localhost:9200`, server port `8082`, DEBUG logging for
`com.lagu.platform`, and `platform.gateway.allow-insecure-default: true` (so local dev doesn't
need a real gateway secret).

No `dev` or `prod` profile file exists in this module — those environments are presumably
configured entirely through env vars against the base `application.yml`.

## Running locally

Prerequisites: Kafka, Redis, and an OpenSearch node reachable at the configured host/port
(`localhost:9092`, `localhost:6380`, `localhost:9200` respectively under the `loc` profile — check
this repo's `docker-compose` setup, if any, for how these are normally started; none was found
inside this module). A Eureka server is also expected at `http://localhost:8761/eureka` unless
overridden (registration failures are otherwise likely to just log/retry, not necessarily crash
the app).

From the repo root:

```bash
SPRING_PROFILES_ACTIVE=loc ./gradlew :apps:search-service:bootRun
```

The service listens on port `8082` under `loc` (port `8080` otherwise, or whatever `SERVER_PORT`
is set to). Swagger UI: `http://localhost:8082/swagger-ui.html`.

To build the jar / Docker image:

```bash
./gradlew :apps:search-service:bootJar
docker build -t search-service apps/search-service
```

The Docker image (`Dockerfile`) runs `java -jar app.jar` directly with no profile set, exposing
port `8080` — all required config must come from env vars in that context.

## Running tests

```bash
./gradlew :apps:search-service:test
```

No `src/test` directory exists in this module at present — there are currently no automated tests
for search-service, despite `testImplementation` dependencies on `spring-boot-starter-test`,
Testcontainers JUnit support, and `spring-kafka-test` being declared in `build.gradle.kts`. Running
the command above will succeed trivially (no tests to run) rather than exercise any behavior.

## Notable design decisions and gotchas

- **Eventually-consistent, event-driven index**: search-service has no synchronous write path of
  its own; all tenant-index content arrives via Kafka from record-service (and cross-org consumer
  content from listing-service). A consumer lagging or a message lost between commit and consumption
  translates directly into stale/missing search results.
- **`STATUS_CHANGED` is a partial update, not a full reindex** (`SearchDocumentIndexer.partialUpdate`)
  — only `status` and `updatedAt` are patched. If the record's underlying `data` changed via some
  path other than a `CREATED`/`UPDATED` event, the index would not reflect it.
  Note also that `indexFull` always writes `createdAt` as `Instant.now()`, even on `UPDATED` events,
  so the index's `createdAt` is not the record's true creation timestamp, it's "as of last full
  index write."
  `partialUpdate` does not call `mappingBuilder.ensureIndex(...)` first, unlike `indexFull` — if a
  `STATUS_CHANGED` event arrives before any `CREATED`/`UPDATED` has created the index, the
  `osClient.update` call will fail against a non-existent index.
- **Admin reindex is best-effort and fire-and-forget**: `ReindexService.reindex` runs `@Async`,
  logs and continues past individual per-record indexing failures (`catch (IOException e)`), and
  reports no completion status back to the caller beyond the initial `202 Accepted` — there is no
  endpoint to poll reindex progress/completion.
  It also does not delete stale documents already in the index for records that no longer exist in
  record-service — it only upserts what `record-service` currently returns.
  Pagination continuation relies on record-service's response shape (`data.content`,
  `data.last`) matching what `RecordClient.listRecords` expects; there's no explicit contract
  test/interface shared between the two services for this.
- **No TLS/auth to OpenSearch or Redis**: `OpenSearchConfig` builds a plain HTTP `RestClient`
  with no credentials, and Redis connection settings show no password. This is presumably
  acceptable within a private network but is not enforced/configurable here.
- **Consumer index empty state, not error state**: `SearchService.searchConsumer` explicitly
  catches an OpenSearch `index_not_found_exception` and returns an empty result page rather than
  propagating a 500 — this happens whenever no listing of the requested `objectType` has ever been
  published.
- **Schema fetch failures degrade silently**: `MetadataClient.getSchema` catches all exceptions
  and returns an empty field list on failure, which means a transient schema-registry outage
  results in an index created with only the fixed base fields (`recordId`, `orgId`, `objectType`,
  `status`, timestamps, and an empty `data` object) rather than a request failure — subsequent
  documents would still index (as an empty/dynamic `data` object) but without the intended typed
  mapping.
- **No API gateway module in this repo path**: routing/rate-limiting for these REST endpoints is
  not visible in this module; it's presumably handled by a separate gateway service.
