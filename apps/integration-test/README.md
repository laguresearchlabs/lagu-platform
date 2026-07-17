# integration-test

## What this is

This module is **not a running service** — it is a single cross-service end-to-end test
(`PlatformEndToEndIT`) that boots real, already-built lagu-platform services as separate
containers (from their actual `bootJar` output, not `@SpringBootTest` mocks) alongside
real infrastructure, and drives the whole pipeline the same way it behaves in production:
over HTTP and Kafka, with no shortcuts.

Services exercised: `schema-registry`, `record-service`, `search-service`,
`workflow-service`, `listing-service`. Infrastructure: PostgreSQL 16, Redis 7, Kafka
(`apache/kafka:3.8.0`), OpenSearch 2.19.

### Why this isn't a `@SpringBootTest`

Putting two or more services' `main` sourceSets on one test classpath makes
`classpath:application.yml` and `classpath:db/migration` resolve non-deterministically
across services — Spring/Flyway scan the classpath and don't reliably pick "the right"
jar's resources, which could silently apply one service's migrations against another
service's schema. Running each service as its own JVM process in its own container (as
it actually runs in production) avoids that class of bug entirely, at the cost of a much
slower and heavier test.

## What the test actually verifies (`createsPublishesIndexesAndSearchesRecordEndToEnd`)

A single test method drives the full lifecycle of a listing:

1. **schema-registry**: creates a field, a field group, and a listing type
   (`IT_TEST_VENUE`), then publishes the listing type. Asserts the publish reaches Kafka
   (topic `PlatformTopics.SCHEMA_EVENTS`) as a `SchemaPublishedEvent`, not just that the
   HTTP call returned 200.
2. **record-service**: creates a `DRAFT` record of that object type via
   `POST /api/v1/records`.
3. **search-service**: polls (up to 30s) until the new record is indexed into OpenSearch
   and searchable via `POST /api/v1/search`, proving the async record→Kafka→index path
   works.
4. **workflow-service**: defines a `DRAFT → SUBMITTED → PUBLISHED` workflow for the
   object type via the workflow-definition API, then drives the record through it via
   `POST /api/v1/records/{id}/status` (trigger `submit`, then `PuBlIsH` — deliberately
   mixed-case to prove trigger matching is case-insensitive). Also asserts role-based
   visibility of allowed transitions (`ORG_MANAGER` sees `publish`; `ORG_MEMBER` sees
   nothing actionable).
5. **listing-service**: reacts to the `PUBLISHED` transition by creating a snapshot,
   which flows to search-service's separate cross-org "consumer" index. A second,
   rival-org listing is published the same way to give the consumer search a
   multi-vendor corpus.
6. **Public consumer search**: asserts `POST /api/v1/search/consumer` returns the
   published listing with **no** identity headers and **no** gateway secret — i.e. it's
   genuinely public, unlike every other endpoint the test calls.

## Infrastructure & wiring details worth knowing

- **Service discovery without Eureka**: services normally resolve each other via a
  `@LoadBalanced RestClient` backed by Eureka. This test disables Eureka
  (`EUREKA_CLIENT_ENABLED=false`) and instead passes a static
  `spring.cloud.discovery.client.simple.instances.<service>[0].uri` entry as a **program
  argument** (not an env var — Spring's relaxed env-var binding doesn't reliably handle
  the hyphenated map key syntax).
- **Auth**: every service requires a non-default gateway shared secret
  (`PLATFORM_GATEWAY_SHARED_SECRET`); the test provisions its own
  (`it-e2e-gateway-secret`) since services now refuse to start on the well-known default.
  Authenticated calls carry `X-User-Id`, `X-Org-Id`, `X-User-Roles`, and
  `X-Platform-Gateway-Secret` headers.
- **Kafka networking**: `KafkaContainer`'s default listener only the test JVM (on the
  host) can reach; a second listener (`kafka:19092`) is added via `withListener()` so the
  app containers on the shared Docker network can reach the broker too.
- **Seeders disabled**: `PLATFORM_SEEDER_ENABLED=false` on schema-registry/workflow-service
  so the test builds its own schema/workflow from a clean slate via the API rather than
  relying on production seed data.

## How to run it

```
./gradlew :apps:integration-test:test
```

Do **not** invoke the test task directly without going through Gradle — it depends on
`bootJar` for all five services (`schema-registry`, `record-service`, `search-service`,
`workflow-service`, `listing-service`) and reads their built JARs via system properties
(`it.schemaRegistryJarDir`, `it.recordServiceJarDir`, etc.) set up in
`build.gradle.kts`. Those `bootJar` tasks are declared as `dependsOn` for the test task,
so a plain `./gradlew :apps:integration-test:test` builds everything it needs
automatically.

Requirements:
- Docker available locally (Testcontainers spins up 4 infra containers + 5 app
  containers, all on one custom network).
- Enough memory/CPU headroom for 9 concurrent containers, including an OpenSearch
  container capped at `-Xms512m -Xmx512m`.

The test has a **15-minute timeout** — cold JVM starts for 5 app containers plus 4
infrastructure containers comfortably exceed JUnit's usual defaults, so don't be
surprised if a full run takes several minutes even when everything is healthy.

## Gotchas

- If you rebuild only one service's code and rerun the test without going through
  Gradle, you may pick up a stale JAR — `resolveBootJar()` fails loudly if a jar
  directory doesn't contain exactly one non-`-plain.jar` file, specifically to catch
  this.
- The Gradle task declares each service's `libs/` output directory as a task `input`
  specifically so that changing service code re-triggers this (otherwise slow, rarely-run)
  E2E test rather than being silently skipped as up-to-date.
- `automation-service`, `notification-service`, `document-service`, and `vendor-service`
  are **not** part of this end-to-end flow — only the five services listed above are
  started and exercised.
