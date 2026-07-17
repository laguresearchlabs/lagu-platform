# booking-service

## Status: scaffold only — not implemented, not wired into the build

This module is a placeholder. It has a `build.gradle.kts` and Spring configuration
(`application.yml`, `application-loc.yml`), and an empty package skeleton under
`src/main/java/com/lagu/platform/booking/` (`api`, `client`, `domain`, `dto`, `event`,
`service` — all currently empty, no `.java` files), but:

- There are **no source files** anywhere in the module.
- There are **no Flyway migrations** in `src/main/resources/db/migration` (the directory
  exists but is empty), even though the config enables Flyway and expects a `booking` schema.
- The module is **not included** in the root `settings.gradle.kts` — running
  `./gradlew build` from the platform root will not build, test, or start this service.

Do not assume any booking functionality exists on this branch of lagu-platform. If you
were expecting a working booking service, check whether it's meant to be migrated from
elsewhere in the workspace, or whether this scaffold is mid-implementation.

## Intent inferable from configuration

Based on `application.yml`/`application-loc.yml`, the intended shape of this service is:

- A Spring Boot service named `booking-service`, backed by PostgreSQL, using its own
  Flyway-managed schema (`booking`) inside the shared `platformdb` database
  (`jdbc:postgresql://localhost:5435/platformdb`, schema `booking`).
- Default HTTP port `8109` (`SERVER_PORT` env var to override).
- Kafka producer/consumer wiring (bootstrap servers via `KAFKA_BOOTSTRAP`, consumer
  group `booking-service`, JSON (de)serialization trusting `com.lagu.platform.*`).
- Outbound HTTP calls to two other platform services, configured via:
  - `LISTING_SERVICE_URL` (default `http://listing-service:8108`)
  - `METADATA_SERVICE_URL` (default `http://metadata-service:8080`) — note there is no
    `metadata-service` module elsewhere in this monorepo; this may reference a service
    that lives outside `lagu-platform` or one not yet created.
- Actuator endpoints exposed: `health`, `info`, `prometheus`.

None of this is backed by actual code yet — it only reflects what the YAML config
declares as intended integration points.

## Dependencies declared in build.gradle.kts

- `libs:common`, `libs:events`, `libs:security` (platform shared libraries)
- Spring Boot: web, data-jpa, validation, actuator, kafka
- springdoc-openapi, micrometer-prometheus, logstash-logback-encoder
- Flyway (core + postgresql), PostgreSQL JDBC driver, Lombok
- Test: spring-boot-test, Testcontainers (JUnit + PostgreSQL), spring-kafka-test

These are the same categories of dependency used by the platform's other Spring Boot
services, suggesting this module was scaffolded from the same template/archetype as its
siblings, then never filled in.

## How to make this buildable

To include it in the build, add `"apps:booking-service"` to the `include(...)` list in
`/mnt/c/git/lagu/lagu-platform/settings.gradle.kts`. It will still fail to produce a
usable JAR/service until at least one `@SpringBootApplication` main class and REST/Kafka
code are added under `src/main/java/com/lagu/platform/booking/`.
