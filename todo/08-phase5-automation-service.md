# Phase 5 — Automation Service

## Responsibility

A rule-based automation engine that reacts to platform events and executes configured actions.
No hardcoded business logic. Admins define: "WHEN this happens, IF these conditions are true,
THEN do these actions."

Think: Salesforce Process Builder / ServiceNow Flow / Zapier — but for this platform.

---

## Core Concepts

```
TriggerDefinition
    │
    ├── event:     "RECORD_STATUS_CHANGED"
    ├── objectType: "PHOTOGRAPHER"
    ├── conditions: [{ field: "status", operator: "EQ", value: "APPROVED" }]
    │
    └── actions:
            ├── SEND_NOTIFICATION  → notify vendor owner
            ├── UPDATE_FIELD       → set data.publishedAt = now()
            └── PUBLISH_RECORD     → set status = "PUBLISHED"
```

---

## Database Schema

### Flyway: `resources/db/migration/V1__automation_schema.sql`

```sql
CREATE TABLE automation.trigger_definition (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID,                       -- NULL = platform-level
    name            VARCHAR(100) NOT NULL,
    label           VARCHAR(200) NOT NULL,
    description     TEXT,
    event_type      VARCHAR(100) NOT NULL,       -- RECORD_CREATED, RECORD_STATUS_CHANGED, etc.
    object_type     VARCHAR(100),               -- NULL = apply to all types
    conditions      JSONB,                      -- list of ConditionRule
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE automation.action_definition (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger_id          UUID        NOT NULL REFERENCES automation.trigger_definition(id) ON DELETE CASCADE,
    action_type         VARCHAR(50) NOT NULL,   -- SEND_NOTIFICATION, UPDATE_FIELD, CALL_WEBHOOK, etc.
    execution_order     INT         NOT NULL DEFAULT 0,
    config              JSONB       NOT NULL,   -- action-specific config
    continue_on_failure BOOLEAN     NOT NULL DEFAULT true,
    is_active           BOOLEAN     NOT NULL DEFAULT true
);

-- Execution history
CREATE TABLE automation.automation_run (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger_id          UUID        NOT NULL REFERENCES automation.trigger_definition(id),
    record_id           UUID,
    org_id              UUID,
    event_type          VARCHAR(100),
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING, RUNNING, SUCCESS, FAILED
    error_message       TEXT,
    started_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);

CREATE TABLE automation.action_run (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    automation_run_id UUID      NOT NULL REFERENCES automation.automation_run(id),
    action_id       UUID        NOT NULL REFERENCES automation.action_definition(id),
    action_type     VARCHAR(50),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_message   TEXT,
    executed_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_trigger_org_event ON automation.trigger_definition (org_id, event_type, object_type);
CREATE INDEX idx_run_trigger       ON automation.automation_run (trigger_id);
CREATE INDEX idx_run_record        ON automation.automation_run (record_id);
```

---

## Trigger Event Types

```java
public enum AutomationEventType {
    RECORD_CREATED,
    RECORD_UPDATED,
    RECORD_STATUS_CHANGED,
    RECORD_DELETED,
    APPROVAL_REQUESTED,
    APPROVAL_APPROVED,
    APPROVAL_REJECTED,
    APPROVAL_TIMEOUT,           // Phase 5 — escalation
    RELATIONSHIP_CREATED,
    RELATIONSHIP_REMOVED,
    SCHEDULED                   // future: cron-based triggers
}
```

---

## Action Types

```java
public enum ActionType {
    SEND_NOTIFICATION,      // push notification / in-app alert to user(s)
    SEND_EMAIL,             // email via notification service
    UPDATE_FIELD,           // set a field value on the triggering record
    UPDATE_STATUS,          // transition record status (fires WorkflowEvent)
    CALL_WEBHOOK,           // HTTP POST to external URL
    CREATE_RECORD,          // create a new related record
    PUBLISH_RECORD,         // shorthand: move to PUBLISHED status
    ARCHIVE_RECORD,         // shorthand: move to ARCHIVED status
    LOG_ACTIVITY            // write to audit/activity feed
}
```

---

## Action Configs (JSONB)

### SEND_NOTIFICATION
```json
{
  "recipients": ["RECORD_OWNER", "GROUP:Photography Team", "USER:uuid"],
  "title": "Your profile has been approved!",
  "body": "Congratulations {{data.name}}, your profile is now live.",
  "channel": ["IN_APP", "EMAIL"]
}
```

### UPDATE_FIELD
```json
{
  "field": "data.publishedAt",
  "value": "{{now}}",
  "valueType": "TIMESTAMP"
}
```

### CALL_WEBHOOK
```json
{
  "url": "https://external.system/webhook",
  "method": "POST",
  "headers": { "Authorization": "Bearer {{secret.WEBHOOK_TOKEN}}" },
  "body": {
    "recordId": "{{recordId}}",
    "status": "{{currentStatus}}"
  },
  "timeoutSeconds": 10,
  "retryCount": 3
}
```

### CREATE_RECORD
```json
{
  "objectType": "NOTIFICATION",
  "data": {
    "type": "APPROVAL_COMPLETE",
    "targetUserId": "{{createdBy}}",
    "message": "Your {{objectType}} record has been approved."
  }
}
```

---

## Kafka Consumer (core of automation-service)

```java
@KafkaListener(
    topics = {
        PlatformTopics.RECORD_EVENTS,
        PlatformTopics.WORKFLOW_EVENTS
    },
    groupId = "automation-service"
)
public void handlePlatformEvent(String payload, @Header("eventType") String eventType) {
    AutomationEventContext ctx = eventParser.parse(eventType, payload);

    List<TriggerDefinition> triggers = triggerRepository
        .findActiveByEventTypeAndObjectType(ctx.getEventType(), ctx.getObjectType());

    for (TriggerDefinition trigger : triggers) {
        if (conditionEvaluator.matches(trigger.getConditions(), ctx)) {
            automationExecutor.execute(trigger, ctx);
        }
    }
}
```

---

## Condition Evaluator

```java
@Component
public class ConditionEvaluator {

    public boolean matches(List<ConditionRule> conditions, AutomationEventContext ctx) {
        if (conditions == null || conditions.isEmpty()) return true;

        return conditions.stream().allMatch(rule -> evaluate(rule, ctx));
    }

    private boolean evaluate(ConditionRule rule, AutomationEventContext ctx) {
        Object fieldValue = resolveField(rule.getField(), ctx);
        return switch (rule.getOperator()) {
            case "EQ"         -> Objects.equals(fieldValue, rule.getValue());
            case "NEQ"        -> !Objects.equals(fieldValue, rule.getValue());
            case "CONTAINS"   -> fieldValue instanceof String s && s.contains(rule.getValue());
            case "IN"         -> rule.getValues().contains(String.valueOf(fieldValue));
            case "GT"         -> compareNumbers(fieldValue, rule.getValue()) > 0;
            case "LT"         -> compareNumbers(fieldValue, rule.getValue()) < 0;
            case "IS_NULL"    -> fieldValue == null;
            case "IS_NOT_NULL" -> fieldValue != null;
            default -> false;
        };
    }

    private Object resolveField(String fieldPath, AutomationEventContext ctx) {
        // fieldPath can be: "status", "data.capacity", "objectType", "orgId"
        if (fieldPath.startsWith("data.")) {
            return ctx.getData().get(fieldPath.substring(5));
        }
        return switch (fieldPath) {
            case "status"     -> ctx.getCurrentStatus();
            case "objectType" -> ctx.getObjectType();
            default -> null;
        };
    }
}
```

---

## Template Engine (for notification bodies)

Simple Mustache-style variable substitution:

```java
@Component
public class TemplateRenderer {
    public String render(String template, AutomationEventContext ctx) {
        return template
            .replace("{{recordId}}",     ctx.getRecordId().toString())
            .replace("{{objectType}}",   ctx.getObjectType())
            .replace("{{currentStatus}}", ctx.getCurrentStatus() != null ? ctx.getCurrentStatus() : "")
            .replace("{{now}}",          Instant.now().toString())
            .replace("{{orgId}}",        ctx.getOrgId().toString());
    }
    // For data.* fields: iterate ctx.getData() and replace {{data.fieldName}}
}
```

For Phase 5 this is sufficient. Phase N+1 can swap in Freemarker or Pebble.

---

## REST API (automation-service)

```
GET    /triggers                       List trigger definitions for org
POST   /triggers                       Create trigger
GET    /triggers/{id}                  Get trigger with actions
PUT    /triggers/{id}                  Update trigger
DELETE /triggers/{id}                  Disable trigger

POST   /triggers/{id}/actions          Add action
PUT    /triggers/{id}/actions/{actionId}  Update action
DELETE /triggers/{id}/actions/{actionId} Remove action

GET    /runs                           List recent automation runs (paginated)
GET    /runs/{id}                      Run detail with action results
POST   /triggers/{id}/test             Dry-run against a sample record (no side effects)
```

---

## Escalation Engine (Timeout handling)

Handles `approval_instance` timeouts. Runs as a scheduled job:

```java
@Scheduled(fixedDelay = 60_000)   // every 60s
public void checkApprovalTimeouts() {
    List<ApprovalInstance> timedOut = approvalInstanceClient
        .findTimedOut();  // calls workflow-service

    for (ApprovalInstance instance : timedOut) {
        AutomationEventContext ctx = buildEscalationContext(instance);
        List<TriggerDefinition> triggers = triggerRepository
            .findActiveByEventType("APPROVAL_TIMEOUT");
        triggers.stream()
            .filter(t -> conditionEvaluator.matches(t.getConditions(), ctx))
            .forEach(t -> automationExecutor.execute(t, ctx));
    }
}
```

---

## Application Config

```yaml
spring:
  application:
    name: automation-service
  datasource:
    url: jdbc:postgresql://localhost:5435/platformdb
    hikari:
      schema: automation
  jpa:
    properties:
      hibernate:
        default_schema: automation
  flyway:
    schemas: automation
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: automation-service
      auto-offset-reset: earliest

platform:
  workflow-service:
    url: http://workflow-service
  notification:
    email-enabled: true
    smtp-host: ${SMTP_HOST:localhost}
```

---

## Seed Triggers (Phase 5 bootstrap)

| Trigger Name               | Event                  | Condition            | Action                      |
|----------------------------|------------------------|----------------------|-----------------------------|
| vendor_approved_notify     | RECORD_STATUS_CHANGED  | status = APPROVED    | SEND_NOTIFICATION to owner  |
| vendor_rejected_notify     | RECORD_STATUS_CHANGED  | status = REJECTED    | SEND_NOTIFICATION to owner  |
| approval_requested_notify  | APPROVAL_REQUESTED     | —                    | SEND_NOTIFICATION to approver|
| approval_timeout_escalate  | APPROVAL_TIMEOUT       | —                    | SEND_NOTIFICATION to manager|

---

## Implementation Checklist

- [ ] Create `apps/automation-service` module
- [ ] Implement all automation schema tables + Flyway migrations
- [ ] Implement `TriggerDefinitionService` (CRUD)
- [ ] Implement `ActionDefinitionService` (CRUD)
- [ ] Implement `ConditionEvaluator` with all operators
- [ ] Implement `ActionExecutor` with all action types
- [ ] Implement `TemplateRenderer`
- [ ] Implement Kafka multi-topic consumer
- [ ] Implement `AutomationRunLogger` (record run history)
- [ ] Implement escalation scheduler for approval timeouts
- [ ] Implement dry-run / test endpoint
- [ ] Implement webhook action with retry (use Spring Retry)
- [ ] Write integration tests
- [ ] Add circuit breaker on webhook calls (Resilience4j)
