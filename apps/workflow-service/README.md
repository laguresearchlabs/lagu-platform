# workflow-service

## What it does

`workflow-service` owns generic status/state-machine transitions for records across the
lagu-platform, plus two things layered on top of that state machine: multi-step approval
gating on individual transitions, and a separate "change set" mechanism for staging
field-level edits that need admin review before they apply. It does not own any business
records itself (no venues, bookings, events, etc.) — `record-service` and other services ask
it "can this record move from state A to state B", and it answers, tracks the current state,
and enforces who is allowed to make the move and under what conditions. It exists so that
workflow definitions (states, transitions, allowed roles, guard conditions, approval chains)
are configured data rather than being hard-coded per-object-type logic scattered across every
service that owns a lifecycle.

This is inferred from the code (`StateMachineEngine`, `ApprovalEngine`, `ChangeSetService`,
the Flyway migrations, and the seeded workflows in `WorkflowSeeder`) — there is no design doc
in the repo describing intent beyond code comments.

## Architecture / responsibilities

Three loosely related concerns live in this service, backed by the `workflow` Postgres schema:

1. **Generic state machine** (`WorkflowDefinition` / `WorkflowState` / `WorkflowTransition` /
   `RecordWorkflowState` / `TransitionHistory`, driven by `StateMachineEngine`):
   - A `WorkflowDefinition` is keyed by `objectType` (e.g. `VENUE`, `WEDDING_EVENT`) and
     optionally scoped to an `tenantId`; an org-scoped definition takes priority over a
     platform-level one (`tenantId IS NULL`) for the same object type
     (`WorkflowDefinitionRepository.findForObjectType`).
   - `RecordWorkflowState` is the one row of runtime state per record (`record_id` is unique),
     tracking `currentState`. It's created lazily on the first transition request for that
     record (`StateMachineEngine.initState`), seeded to the workflow's `initialStatus`.
   - `WorkflowTransition` rows define `fromState -> toState` moves keyed by a `triggerName`
     (matched case-insensitively), each with an optional `allowedRoles` list (empty/null =
     anyone), an optional JSONB `conditions` guard, and an optional link to an
     `ApprovalDefinition` when the transition requires approval.
   - `TransitionGuard` evaluates the `conditions` JSONB against a `context` map passed in the
     triggering event. Supported operators: `eq`, `neq`, `in`, `not_in`, `exists`,
     `not_exists`, `gt`, `lt`, `gte`, `lte`. All conditions must pass (AND semantics). Design
     decision: it **fails closed** — an unknown operator, a missing `op`, or a non-numeric
     value in a numeric comparison blocks the transition rather than allowing it (see
     `TransitionGuardTest`).
   - Every transition is recorded to `TransitionHistory` regardless of outcome path.

2. **Approval workflow** (`ApprovalDefinition` / `ApprovalStep` / `ApprovalInstance` /
   `ApprovalStepDecision`, driven by `ApprovalEngine`):
   - A `WorkflowTransition` with `requiresApproval=true` and a linked `ApprovalDefinition`
     starts an `ApprovalInstance` instead of transitioning immediately.
   - `ApprovalDefinition.approvalType` is one of `SEQUENTIAL` (steps decided in order, each by
     its own required role), `PARALLEL` (every step must be approved by a holder of that
     step's specific role, in any order — one approver holding multiple roles still can't
     complete more than one step per decision), or `ANY_ONE` (any single step approval by an
     eligible role completes the whole instance).
   - Role eligibility for a decision is re-checked against the actor's *current* roles at
     decision time, not roles captured when the instance was created.
   - The transition's requester can never decide their own approval instance
     (`SELF_APPROVAL_FORBIDDEN`), and no approver may decide the same instance twice.
   - On approval, `ApprovalEngine` calls back into `StateMachineEngine.executeTransition` to
     actually apply the state change; on rejection it just publishes an event — the record
     stays in its prior state.

3. **Change sets** (`ChangeSet`, driven by `ChangeSetService`): a separate mechanism from the
   state machine, for staging a proposed field-level edit (`proposedData` vs. `originalData`
   JSONB blobs) when a `WorkflowState.requiresChangeApproval` flag is set. A vendor submits a
   change set instead of the owning service applying the edit directly; an admin reviews
   (approve/reject, optionally with `correctedData`), or the submitter withdraws it while
   still `PENDING`. This is a request/response-only workflow — no Kafka events are published
   for change sets, and it does not touch `RecordWorkflowState`.

### Seeded data

`WorkflowSeeder` (an `ApplicationRunner`, gated by `platform.seeder.enabled`, default `true`)
inserts a fixed set of workflows on startup if none exist yet for that `objectType`:
- Vendor review workflow, identical shape, seeded per vendor type: `VENUE`, `PHOTOGRAPHER`,
  `CATERER`, `DECORATOR`, `MAKEUP_ARTIST` (`DRAFT -> SUBMITTED -> UNDER_REVIEW -> APPROVED ->
  PUBLISHED`, with `REJECTED`, `SUSPENDED`, `ARCHIVED` side states).
- `WEDDING_EVENT` lifecycle (`PLANNING -> CONFIRMED -> IN_PROGRESS -> COMPLETED`, plus
  `CANCELLED`/`ARCHIVED`).
- `CORPORATE_EVENT` lifecycle (adds a `SUBMITTED` state between `PLANNING` and `CONFIRMED`,
  requiring admin roles to confirm).

None of the seeded transitions require approval (`requiresApproval=false` for all of them) —
approval chains must be configured separately via the workflow-definition API.

## REST API

All endpoints are under `/api/v1`. Identity/roles come from the shared
`GatewayHeaderFilter` (see Configuration below) via `PlatformSecurityContext`, not from any
auth logic in this service. Endpoints without `@RequirePermission` still require an
authenticated context; some (see below) fetch it manually and 401 if absent, others read it
and silently proceed with a null org/user if the header is missing (`ChangeSetController`).

**Workflow definitions** — `WorkflowDefinitionController`, all gated with
`@RequirePermission(resource = "WORKFLOW", action = ...)`:
| Method | Path | Action | Purpose |
|---|---|---|---|
| GET | `/api/v1/workflow-definitions` | READ | List active workflow definitions (summary only, no states/transitions). |
| GET | `/api/v1/workflow-definitions/{id}` | READ | Get one workflow definition with full states + transitions. |
| POST | `/api/v1/workflow-definitions` | CREATE | Create a workflow definition (org-scoped unless caller is platform admin). |
| POST | `/api/v1/workflow-definitions/{id}/states` | UPDATE | Add a state to an existing workflow. |
| POST | `/api/v1/workflow-definitions/{id}/transitions` | UPDATE | Add a transition (validates both `fromState`/`toState` exist on the workflow first). |

**Record workflow status** — `RecordWorkflowController`:
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/records/{recordId}/workflow` | Current state, whether it's terminal, and the list of transitions allowed for the caller's roles. |
| GET | `/api/v1/records/{recordId}/workflow/history` | Paged transition history for a record (`page`, `size` query params, default 0/20). |

**Approvals** — `ApprovalController`, requires an authenticated + org-member context
(manually checked, 401 otherwise):
| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/approvals/pending` | Pending approval instances for the caller's org/roles; optional `olderThanMinutes` filter. |
| GET | `/api/v1/approvals/{id}` | Get one approval instance (404 if it belongs to a different org — cross-org lookups are not distinguished from "not found"). |
| POST | `/api/v1/approvals/{id}/decide` | Record an approve/reject decision for the caller's eligible step. |

**Change sets** — `ChangeSetController` (no `@RequirePermission` — authorization here is
whatever the caller does with `GatewayHeaderFilter`'s context; falls back to request-body
fields like `submittedBy`/`reviewedBy` if there's no gateway context):
| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/change-sets` | Submit a proposed change (vendor). |
| POST | `/api/v1/change-sets/{id}/review` | Approve/reject a pending change set (admin). |
| POST | `/api/v1/change-sets/{id}/withdraw` | Withdraw a pending change set (only the original submitter). |
| GET | `/api/v1/change-sets/record/{recordId}` | List change sets for a record. |
| GET | `/api/v1/change-sets/pending` | All pending change sets, cross-org (admin view). |
| GET | `/api/v1/change-sets?status=PENDING` | List change sets for the caller's org filtered by status (defaults to `PENDING`; 400 if no org in context). |

Swagger UI is available at `/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`
(springdoc-openapi is a declared dependency; no custom `OpenAPIDefinition` bean was found in
the source, so this is default auto-generated documentation).

## Kafka

Topic names come from `libs/events/.../PlatformTopics.java`.

**Consumes:**
- `platform.record.events` (`PlatformTopics.RECORD_EVENTS`) — `TransitionEventConsumer`,
  consumer group `workflow-service`. Only events with `eventType ==
  "STATUS_TRANSITION_REQUESTED"` are acted on (a `RecordEvent`); everything else is
  immediately acknowledged and ignored. On a processing exception the message is retried 3
  times (1s fixed backoff, `KafkaConfig.kafkaErrorHandler`) and then published to
  `platform.record.events.DLT` via `DeadLetterPublishingRecoverer`. Acking is manual
  (`ack-mode: MANUAL`); the listener acks explicitly after (attempted) processing.

**Produces:**
- `platform.workflow.events` (`PlatformTopics.WORKFLOW_EVENTS`) — `WorkflowEvent` payloads
  for `TRANSITIONED`, `TRANSITION_REJECTED`, `APPROVAL_REQUESTED`,
  `APPROVAL_STEP_COMPLETED`, `APPROVAL_REJECTED`. (The `WorkflowEvent.eventType` javadoc also
  lists `APPROVAL_APPROVED`/`APPROVAL_TIMEOUT`, but no code path in this service publishes
  those two — worth checking if a timeout/escalation feature was planned but not implemented;
  `ApprovalStep.timeoutHours`/`escalateToRole` columns exist in the schema but nothing reads
  them.) Partition key is `"{tenantId}:{recordId}"` (or just `tenantId` if `recordId` is null).

Publishing does **not** go straight to `KafkaTemplate`. `WorkflowEventPublisher` stages
events into the `workflow_outbox` table (via `libs/common`'s `TransactionalOutbox`) inside the
same transaction as the state change, and the shared `OutboxRelay`
(`libs/common/.../outbox/OutboxRelay.java`, active because `platform.outbox.enabled=true` and
`@EnableScheduling` is set on the application class) polls that table on a fixed delay
(`platform.outbox.poll-interval-ms`, default 1000ms) and delivers committed rows to Kafka,
retrying on failure and stopping a batch on the first failure so per-key ordering isn't
violated. This replaces an earlier "AFTER_COMMIT listener" design (per the migration comment
in `V4__workflow_outbox.sql`) that could lose events on a post-commit crash. Published rows
are purged after 7 days by a separate scheduled cleanup job (`platform.outbox.cleanup-cron`,
default daily at 03:00). Delivery is at-least-once — consumers must tolerate duplicate
`WorkflowEvent`s.

## Configuration

From `src/main/resources/application.yml` and `application-loc.yml`:

| Property | Default / source | Notes |
|---|---|---|
| `spring.application.name` | `workflow-service` | |
| `spring.profiles.active` | `loc` (in `application.yml`) | Overridden to nothing in Docker images — see comment in the file; only takes effect via `bootRun` (root `build.gradle.kts` forces `spring.profiles.active=loc` as a system property for `bootRun` tasks). |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | required env vars (non-loc) | Postgres connection. `loc` profile hardcodes `jdbc:postgresql://localhost:5435/platformdb`, user/pass `postgres`/`postgres`. |
| `spring.datasource.hikari.maximum-pool-size` / `minimum-idle` | 10 / 2 | |
| Hibernate | `ddl-auto: validate`, schema `workflow`, `open-in-view: false` | Schema is managed entirely by Flyway; Hibernate only validates it matches. |
| `spring.flyway.schemas` | `workflow` | Migrations in `src/main/resources/db/migration` (`V1`–`V5`), `baseline-on-migrate: true`. |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | required env var (non-loc) | `loc` hardcodes `localhost:9092`. |
| Kafka consumer | group `workflow-service`, `auto-offset-reset: earliest`, `JsonDeserializer` trusting only `com.lagu.platform.events`, manual ack | |
| Kafka producer | `acks: all`, `retries: 3`, `enable.idempotence: true` | |
| `SERVER_PORT` | `8080` (default), `8085` in `loc` profile | |
| `EUREKA_SERVER_URL` | `http://localhost:8761/eureka` (default) | Eureka client registration, `prefer-ip-address: true`. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | |
| `platform.outbox.enabled` | `true` | Enables `OutboxRelay` from `libs/common`. |
| `platform.outbox.poll-interval-ms` | `1000` (relay's own default) | Not overridden in this service's yml. |
| `platform.outbox.cleanup-cron` | `0 0 3 * * *` (relay's own default) | Not overridden here. |
| `platform.seeder.enabled` | `true` | Controls `WorkflowSeeder`. |
| `PLATFORM_GATEWAY_SHARED_SECRET` / `platform.gateway.shared-secret` | insecure well-known default unless set | From `libs/security`. Service refuses to start trusting identity headers unless this is set, **or** `platform.gateway.allow-insecure-default=true` (set in the `loc` profile here). |
| `platform.gateway.allow-insecure-default` | `true` in `loc` profile only | Must not be set in Docker/prod configs. |

Only one profile file exists in this service: `application-loc.yml` ("local (non-Docker)
IDE/bootRun development only", per its own comment). There is no `application-dev.yml` or
`application-prod.yml` in this module — dev/prod configuration is presumably supplied
entirely through environment variables against the base `application.yml`, unlike some other
services in this monorepo that may ship dedicated profile files (not verified here).

## Running locally

Requires Postgres and Kafka reachable at the addresses in `application-loc.yml`
(`localhost:5435/platformdb`, `localhost:9092`) — check `docker-compose` at the repo root (not
inspected as part of this task) for how those are normally started for local dev, and Eureka
at `localhost:8761` if service discovery matters for your test.

```bash
# from the lagu-platform repo root
SPRING_PROFILES_ACTIVE=loc ./gradlew :apps:workflow-service:bootRun
```

Because `build.gradle.kts` (root) forces `spring.profiles.active=loc` for all `bootRun`
tasks, plain `./gradlew :apps:workflow-service:bootRun` also works without setting the env
var explicitly — the explicit form above matches how `application.yml`'s own comment
describes running it.

Default port in `loc`: **8085**. Swagger UI: `http://localhost:8085/swagger-ui.html`.

Build the jar / image:
```bash
./gradlew :apps:workflow-service:build
docker build -t workflow-service apps/workflow-service   # uses Dockerfile, eclipse-temurin:25-jre-alpine
```

## Running tests

```bash
./gradlew :apps:workflow-service:test
```

- `ApprovalEngineTest` — pure Mockito unit tests, no containers needed. Covers SEQUENTIAL,
  PARALLEL, and ANY_ONE approval-type semantics, self-approval rejection, double-decision
  rejection, and cross-org lookup behavior.
- `TransitionGuardTest` — pure unit tests for the JSONB condition evaluator, including the
  fail-closed cases (unknown op, missing op, non-numeric comparison, missing context field).
- `StateMachineIntegrationTest` — **entirely commented out** in the source tree (the whole
  file body is one large comment block). It would otherwise be a Testcontainers +
  `@EmbeddedKafka` end-to-end test posting a `STATUS_TRANSITION_REQUESTED` event and asserting
  the resulting state and REST behavior. As committed, it contributes zero test coverage and
  requires no Docker/Testcontainers to run — worth reinstating or removing.

## Design notes / gotchas

- **Fail-closed guard evaluation.** `TransitionGuard` treats any condition it can't confidently
  evaluate (unknown operator, missing `op`, non-numeric comparison) as a failure, not a pass.
  This was apparently a deliberate fix — see the comment in `TransitionGuardTest` noting a
  prior bug where a parse failure on `gte`/`lte` compared as equal and let transitions through.
- **Optimistic locking added after the fact.** `V5__optimistic_locking.sql` adds a `@Version`
  column to `record_workflow_state` and `approval_instance` specifically so two concurrent
  transitions/decisions against the same stale state conflict instead of silently both
  applying.
- **Self-approval and re-decision guards depend on `requested_by`, which is nullable.**
  `V3__approval_requested_by.sql` added `requested_by` to `approval_instance` after the table
  already existed; pre-existing rows have no requester recorded and are implicitly exempt from
  the self-approval check (`actorId != null && actorId.equals(instance.getRequestedBy())` is
  false when `requestedBy` is null).
- **Role check bypass for `PLATFORM_ADMIN` and `SVC_*` principals.** In
  `StateMachineEngine.isRoleAllowed`, both platform admins and any caller with a role starting
  `SVC_` (i.e., any other platform service acting as itself via `X-Internal-Service`, per
  `GatewayHeaderFilter`) can trigger any transition regardless of the transition's configured
  `allowedRoles`.
- **Transactional outbox, not direct Kafka send.** All `WorkflowEvent` publishing goes through
  `workflow_outbox` + the shared `OutboxRelay`, not a direct `KafkaTemplate.send` in the request
  path — see the Kafka section above. This makes event delivery at-least-once and tied to the
  state-change transaction rather than best-effort post-commit.
- **Approval escalation/timeout fields exist in schema but are unused in code.**
  `approval_definition.timeout_hours` and `approval_step.timeout_hours` /
  `escalate_to_role` are defined in `V1__workflow_schema.sql`, and the `WorkflowEvent` type
  comment mentions `APPROVAL_TIMEOUT`, but no scheduled job or service code in this module
  reads those columns or publishes that event type — appears to be an unimplemented feature,
  not a bug, but flagging it since the schema implies more capability than the code delivers.
- **Change sets are independent of the state machine.** `ChangeSetService` does not consult or
  update `RecordWorkflowState`/`TransitionHistory` at all, and does not publish any Kafka
  events. `ChangeSetService.requiresApproval(workflowId, stateName)` exists for other services
  (documented as "callers... via HTTP") to check before applying a direct PATCH, but no
  controller in this module exposes that check over REST — it's a plain `@Service` method, so
  either it's called in-process only (unlikely across services) or the intended HTTP entry
  point wasn't found in this codebase / doesn't exist yet.
- **Workflow definition resolution picks the first match, not a validated "no ambiguity"
  path.** `StateMachineEngine.resolveWorkflow` takes `matches.getFirst()` after ordering
  org-scoped before platform-level; the unique constraint in `V1` (`uq_workflow_object_org`)
  prevents two rows with the *same* org/object-type pair, so in practice at most one org-level
  and one platform-level definition can match, but this relies on that constraint rather than
  an explicit check in the service code.
