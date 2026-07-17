# automation-service

## What it does

`automation-service` is the rules-engine for the lagu-platform: it listens to domain events
published by other services (record changes, workflow/approval events) and, when an event
matches an org's configured trigger, runs a chain of actions against it — send a notification,
patch a record field, request a status transition, call an external webhook, create a related
record, or log an activity. It exists so that business logic like "notify the vendor when their
listing is approved" or "auto-escalate an approval that's been pending too long" lives in
data-driven trigger/action definitions rather than being hardcoded into record-service or
workflow-service. It also owns a scheduled job that polls workflow-service for approvals that
have timed out and fires escalation triggers for them.

## Architecture / responsibilities

- **Triggers** (`TriggerDefinition`): org-scoped (or platform-wide when `org_id IS NULL`) rules
  keyed by `eventType` (+ optional `objectType`), with a JSON list of `conditions` (field/operator/value
  rules such as `EQ`, `NEQ`, `CONTAINS`, `STARTS_WITH`, `IN`, `GT`/`LT`/`GTE`/`LTE`, `IS_NULL`,
  `IS_NOT_NULL`) that all must match (`ConditionEvaluator`).
- **Actions** (`ActionDefinition`): an ordered (`executionOrder`) list of steps attached to a
  trigger, each with an `actionType` and a JSON `config` blob, and a `continueOnFailure` flag that
  controls whether the action chain stops on the first failure. Supported action types
  (`ActionExecutor`): `SEND_NOTIFICATION`, `SEND_EMAIL`, `UPDATE_FIELD`, `UPDATE_STATUS`,
  `PUBLISH_RECORD`, `ARCHIVE_RECORD`, `CALL_WEBHOOK`, `CREATE_RECORD`, `LOG_ACTIVITY`,
  `EXPIRE_VERIFICATION`, `REVOKE_VERIFICATION`. Config values may use `{{token}}` templating
  (`TemplateRenderer`) resolved against the event context (`recordId`, `orgId`, `objectType`,
  `currentStatus`, `previousStatus`, `now`, `changedBy`, or `data.<field>`).
- **Runs**: every trigger firing is recorded as an `AutomationRun` (status `RUNNING` → `SUCCESS`/
  `FAILED`), with one `ActionRun` per executed action, for audit/debugging via the REST API.
- **Event flow**: `PlatformEventConsumer` consumes `RECORD_EVENTS` and `WORKFLOW_EVENTS`,
  normalizes them into an `AutomationEventContext` (`AutomationEventParser`), looks up matching
  active triggers (`TriggerDefinitionRepository`), evaluates conditions, and hands matches to
  `AutomationExecutor`, which runs asynchronously (`@Async` on `automationTaskExecutor`, a
  4–10 thread pool) and publishes `TRIGGER_FIRED` / `ACTION_SUCCEEDED` / `ACTION_FAILED` events
  back onto `AUTOMATION_EVENTS`.
- **Loop guard**: `PlatformEventConsumer` counts how many times a trigger has fired for the same
  record in the last 60 seconds (`AutomationRunRepository.countRecentRuns`) and refuses to run it
  again once that count reaches 5 — this guards against an action's own side effect (e.g.
  `UPDATE_STATUS`) re-publishing an event that re-fires the same trigger indefinitely.
- **Escalation**: `EscalationScheduler` runs on a fixed delay (default 60s,
  `platform.automation.approval-timeout-check-interval-ms`), calls
  `workflow-service`'s `GET /api/v1/approvals/pending?olderThanMinutes=` to find approvals older
  than `platform.automation.approval-timeout-minutes` (default 60), and fires any
  `APPROVAL_TIMEOUT` triggers matching each one (published as `ESCALATION_FIRED` instead of
  `TRIGGER_FIRED`).
- **Webhook execution** (`WebhookExecutor`): outbound `CALL_WEBHOOK` calls go through a
  per-hostname Resilience4j circuit breaker (count-based, window 10, min 5 calls, 50% failure
  threshold, 30s open state) wrapped by a retry (3 attempts, exponential backoff from 1s,
  retries `IOException`/`ResourceAccessException`, does not retry when the breaker is open).
- **Downstream calls**: `RecordServiceClient` and `WorkflowServiceClient` call `record-service`
  and `workflow-service` respectively over a load-balanced (Eureka-discovered) `RestClient`,
  authenticating as an internal service via `X-Internal-Service: automation-service` +
  `X-Platform-Gateway-Secret`.

## REST API

All endpoints require the `TRIGGER` resource permission (via `@RequirePermission`, enforced by
`libs:security`'s aspect) and are scoped to the caller's org (`X-Org-Id`, taken from
`GatewayHeaderFilter.current()`); platform-level triggers (`org_id IS NULL`) are visible/matched
alongside org-specific ones but org-scoping on lookups is enforced by the repository queries.

**Triggers** — `TriggerController`, base path `/api/v1/triggers`:

| Method | Path | Purpose | Permission |
|---|---|---|---|
| GET | `/api/v1/triggers` | List trigger definitions for the org (paginated) | TRIGGER:READ |
| GET | `/api/v1/triggers/{id}` | Get one trigger definition | TRIGGER:READ |
| POST | `/api/v1/triggers` | Create a trigger definition (201) | TRIGGER:CREATE |
| PUT | `/api/v1/triggers/{id}` | Update a trigger definition | TRIGGER:UPDATE |
| DELETE | `/api/v1/triggers/{id}` | Disable a trigger (sets `isActive=false`; not a hard delete) (204) | TRIGGER:DELETE |
| POST | `/api/v1/triggers/{id}/actions` | Add an action to a trigger (201) | TRIGGER:UPDATE |
| PUT | `/api/v1/triggers/{id}/actions/{actionId}` | Update an action | TRIGGER:UPDATE |
| DELETE | `/api/v1/triggers/{id}/actions/{actionId}` | Remove an action (204) | TRIGGER:UPDATE |
| POST | `/api/v1/triggers/{id}/test` | Dry-run a trigger against sample data — executes the action chain with `dryRun=true`, so `ActionExecutor` only logs what it would do and has no side effects | TRIGGER:UPDATE |

**Automation runs** — `AutomationRunController`, base path `/api/v1/runs`:

| Method | Path | Purpose | Permission |
|---|---|---|---|
| GET | `/api/v1/runs` | List automation run history for the org (paginated) | TRIGGER:READ |
| GET | `/api/v1/runs/{id}` | Get one run (404 if it belongs to a different org) | TRIGGER:READ |

Request/response bodies for trigger and action CRUD are untyped `Map<String, Object>` (no
dedicated DTO/record classes) — field names accepted are whatever `TriggerDefinitionService.applyFields`
/ `applyActionFields` read: `name`, `label`, `description`, `eventType`, `objectType`, `conditions`,
`isActive` for triggers; `actionType`, `executionOrder`, `config`, `continueOnFailure`, `isActive`
for actions.

Swagger/OpenAPI UI is exposed at `/swagger-ui.html` (springdoc), spec at `/v3/api-docs`.

## Kafka topics

Topic names come from `libs:events`' `PlatformTopics`.

**Consumes:**
- `platform.record.events` (`PlatformTopics.RECORD_EVENTS`) — deserialized as `RecordEvent`;
  `CREATED`/`UPDATED`/`STATUS_CHANGED`/`DELETED` map to automation event types
  `RECORD_CREATED`/`RECORD_UPDATED`/`RECORD_STATUS_CHANGED`/`RECORD_DELETED`. Consumer group
  `automation-service`.
- `platform.workflow.events` (`PlatformTopics.WORKFLOW_EVENTS`) — deserialized as `WorkflowEvent`;
  only `APPROVAL_REQUESTED`, `APPROVAL_STEP_COMPLETED` (both mapped to automation event
  `APPROVAL_REQUESTED`), and `APPROVAL_REJECTED` are turned into automation events — other
  workflow event types (`TRANSITIONED`, `APPROVAL_TIMEOUT`, etc.) are ignored by the parser (note:
  `APPROVAL_TIMEOUT` triggers are instead driven by `EscalationScheduler`'s polling, not this
  topic). Consumer group `automation-service-workflow`.

Both listeners use `String` payloads with manual JSON parsing via Jackson `ObjectMapper`
(`ack-mode: MANUAL`, manual `Acknowledgment.acknowledge()` after dispatch) even though the
consumer's configured deserializer is `StringDeserializer` — actual object mapping happens in
`AutomationEventParser`, not via a Kafka `JsonDeserializer`.

**Produces:**
- `platform.automation.events` (`PlatformTopics.AUTOMATION_EVENTS`) — `AutomationEvent` objects
  with `eventType` one of `TRIGGER_FIRED`, `ESCALATION_FIRED`, `ACTION_SUCCEEDED`,
  `ACTION_FAILED`. Producer key is `"{orgId}:{recordId}"`, or just `orgId`, or `"platform"` if
  `orgId` is null. Producer uses `JsonSerializer`, `acks=all`, `retries=3`,
  `enable.idempotence=true`.
- `<topic>.DLT` — dead-letter topic per consumed topic, published by
  `KafkaConfig`'s `DefaultErrorHandler` (`DeadLetterPublishingRecoverer`, 3 retries with a 1s
  fixed backoff) when listener processing throws.

## Configuration

Base config: `src/main/resources/application.yml`. Profile overlay for local dev:
`application-loc.yml` (`spring.config.activate.on-profile: loc`). No `dev`/`prod`
profile-specific YAML files exist in this module — `application.yml` sets
`spring.profiles.active: loc` as a fallback default, but the file itself notes this must never
leak into a Docker image (Docker only runs `java -jar app.jar` with no profile forced by that
default) and the root `build.gradle.kts` forces `spring.profiles.active=loc` specifically for
`bootRun` so local Gradle runs still pick it up.

Key properties / env vars (base `application.yml`):

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | none (required outside `loc`) | Postgres JDBC URL |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | none | DB user |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | none | DB password |
| `spring.kafka.bootstrap-servers` | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | none | Kafka brokers |
| `server.port` | `SERVER_PORT` | `8080` | HTTP port (Docker); `loc` profile overrides to `8103` |
| `eureka.client.service-url.defaultZone` | `EUREKA_SERVER_URL` | `http://localhost:8761/eureka` | Service discovery |
| `platform.automation.webhook-timeout-seconds` | `WEBHOOK_TIMEOUT_SECONDS` | `10` | Default webhook HTTP timeout used by `WebhookExecutor` |
| `platform.automation.webhook-retry-attempts` | `WEBHOOK_RETRY_ATTEMPTS` | `3` | Set but not read anywhere in `WebhookExecutor` — the executor's retry attempt count is hardcoded to 3 in code, so this property currently has no effect |
| `platform.automation.approval-timeout-check-interval-ms` | `APPROVAL_TIMEOUT_CHECK_MS` | `60000` | `EscalationScheduler` poll interval |
| `platform.automation.approval-timeout-minutes` | — (no env var wired, only a raw property default) | `60` | How old a pending approval must be before it's considered timed out |
| `platform.gateway.shared-secret` | — | `CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD` (insecure placeholder) | Shared secret between gateway-service and this service; must be set in real deployments or startup fails (`ServiceSecurityConfig`) unless `platform.gateway.allow-insecure-default=true` |

Hikari/JPA: schema fixed to `automation` (`hikari.schema`, `hibernate.default_schema`), dialect
`PostgreSQLDialect`, JDBC time zone `UTC`, `ddl-auto: validate` (schema is owned by Flyway, not
Hibernate). Flyway runs migrations from `classpath:db/migration` against the `automation` schema.

`loc` profile overrides: `jdbc:postgresql://localhost:5435/platformdb` (user/pass `postgres`),
Kafka at `localhost:9092`, `server.port: 8103`, SQL/Hibernate DEBUG-level logging, and
`platform.gateway.allow-insecure-default: true` (lets the service start locally without a real
gateway secret).

Actuator exposes `health`, `info`, `prometheus`. Swagger UI at `/swagger-ui.html`,
OpenAPI JSON at `/v3/api-docs`.

## Running locally

Requires Postgres (schema `automation` on a `platformdb` database, `localhost:5435` per the
`loc` profile) and Kafka (`localhost:9092`) to be running, plus Eureka if you want service
discovery to succeed (registration failures don't crash the app, just log). Flyway will create
the `automation` schema and seed platform-level demo triggers on first boot (see
`V1__automation_schema.sql`: `vendor_approved_notify`, `vendor_rejected_notify`,
`approval_requested_notify`, `approval_timeout_escalate`).

```
SPRING_PROFILES_ACTIVE=loc ./gradlew :apps:automation-service:bootRun
```

(`bootRun` alone also works — the root build forces `spring.profiles.active=loc` for that task —
but setting it explicitly matches what `application.yml`'s comment instructs.)

Default port in `loc`: **8103**. Default port in the packaged Docker image (`Dockerfile`,
`server.port` default): **8080**.

Build the jar / Docker image:
```
./gradlew :apps:automation-service:build
docker build -t automation-service apps/automation-service
```

## Running tests

```
./gradlew :apps:automation-service:test
```

**Note:** the only test file in this module, `AutomationServiceIntegrationTest.java`, is
entirely commented out (the whole file body is one large `//`-prefixed block). There is
currently no active/executing test in `src/test` for this service — running `./gradlew test`
will report no tests. The commented-out test (a `@SpringBootTest` with Testcontainers Postgres +
Redis and `@EmbeddedKafka`) covers trigger CRUD, adding/updating actions, a Kafka event
matching a trigger and producing an `AutomationRun`, a non-matching event producing no run, and
the dry-run endpoint — presumably disabled pending a fix (it also spins up a Redis container
that nothing else in this module appears to depend on directly).

## Notable design decisions / gotchas

- **Untyped request bodies**: Trigger/action create and update endpoints accept raw
  `Map<String, Object>` instead of validated DTOs — `spring-boot-starter-validation` is a
  dependency but no `@Valid`/Bean Validation annotations are used on these endpoints in the code
  read. Malformed input (e.g. bad `executionOrder` type) will surface as a `ClassCastException`
  at runtime, not a 400 with a field-level message.
- **Runaway-loop guard is a hard cutoff, not a backoff**: once a trigger fires 5+ times for the
  same record within a 60s window, further firings are dropped silently (logged at ERROR) rather
  than queued/delayed — a legitimately high-frequency automation on one record would be truncated.
- **Best-effort downstream calls**: `RecordServiceClient` methods catch all exceptions and
  return `null` / log-and-continue rather than propagating failures — a failed `UPDATE_FIELD` or
  `UPDATE_STATUS` call still gets marked `SUCCESS` at the `ActionExecutor` level unless the HTTP
  client itself throws (it doesn't, by design), so run history may not reflect true action
  outcomes for these action types.
- **Two unqualified `RestClient.Builder` beans** exist in `LoadBalancerConfig` on purpose: one
  `@LoadBalanced` (used explicitly via `@Qualifier` by `RecordServiceClient`/`WorkflowServiceClient`)
  and one `@Primary` plain builder, to keep Eureka's own auto-configured heartbeat client from
  picking up the load-balanced one and trying to load-balance calls to the literal Eureka host
  (documented as an open spring-cloud-netflix issue in the code comment).
  `WebhookExecutor.doHttpRequest`, by contrast, builds a brand-new plain `RestClient` per call
  (not load-balanced — webhook URLs are arbitrary external endpoints, not Eureka service IDs).
- **`webhook-retry-attempts` config is dead**: `platform.automation.webhook-retry-attempts` is
  defined in `application.yml` but `WebhookExecutor`'s `RetryConfig.maxAttempts(3)` is hardcoded
  in the constructor and never reads that property.
- **Platform-level vs org-level triggers**: a `TriggerDefinition` with `org_id IS NULL` applies
  across all orgs (seeded examples ship this way); org-specific triggers layer on top. Both are
  matched together in `PlatformEventConsumer.dispatch` and `TriggerDefinitionRepository`'s
  queries (`t.orgId = :orgId OR t.orgId IS NULL`).
- **`objectType` matching is optional per event**: `PlatformEventConsumer.dispatch` only filters
  by `objectType` when the parsed context has one; `findActiveByEvent` (no type) is used
  otherwise, e.g. for approval/escalation events which don't carry an `objectType` filter the same way.
- **Escalation and Kafka-driven triggers share `AutomationExecutor`**: `APPROVAL_TIMEOUT` firing
  from `EscalationScheduler` and normal Kafka-driven firings both go through the same
  `AutomationExecutor.execute`, which distinguishes them only to choose the outbound event type
  (`ESCALATION_FIRED` vs `TRIGGER_FIRED`), based on `ctx.getEventType().equals("APPROVAL_TIMEOUT")`.
