# Kafka Event Contracts

All event schemas live in `libs/events`. No service publishes or consumes events
without having the canonical class from this library.

---

## Topics

| Topic                      | Producer(s)              | Consumer(s)                                    |
|----------------------------|--------------------------|------------------------------------------------|
| `platform.metadata.changed`| metadata-service         | search-service                                 |
| `platform.record.events`   | record-service           | search-service, workflow-service, automation-service |
| `platform.workflow.events` | workflow-service         | record-service, automation-service             |
| `platform.team.events`     | metadata-service (teams) | automation-service                             |
| `platform.automation.events` | automation-service     | (notification delivery, future integrations)   |

---

## Conventions

- **Topic naming**: `platform.<domain>.<noun>` — all lowercase, dots as separators
- **Key**: `orgId:recordId` (or just `orgId` for metadata events) — enables Kafka partitioning by org
- **Value**: JSON-serialized event POJO
- **Header `eventType`**: String — allows consumers to route without deserializing the body first

```java
// All events implement PlatformEvent
public interface PlatformEvent {
    String getEventType();
    UUID   getOrgId();
    Instant getOccurredAt();
}
```

---

## Event: MetadataChangedEvent

```java
@Data @Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetadataChangedEvent implements PlatformEvent {

    /** ATTRIBUTE_CREATED | ATTRIBUTE_UPDATED | ATTRIBUTE_DELETED
     *  ENTITY_CREATED    | ENTITY_UPDATED    | ENTITY_DELETED
     *  OBJECT_TYPE_CREATED | OBJECT_TYPE_UPDATED | OBJECT_TYPE_DELETED */
    private String  eventType;

    private UUID    resourceId;
    private String  resourceName;       // e.g. "VENUE"
    private String  resourceKind;       // ATTRIBUTE | ENTITY | OBJECT_TYPE
    private UUID    orgId;
    private UUID    changedBy;
    private Instant occurredAt;
}
```

---

## Event: RecordEvent

```java
@Data @Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecordEvent implements PlatformEvent {

    /** CREATED | UPDATED | STATUS_CHANGED | DELETED
     *  STATUS_TRANSITION_REQUESTED  (consumed by workflow-service) */
    private String  eventType;

    private UUID    recordId;
    private UUID    orgId;
    private String  objectType;

    private String  previousStatus;
    private String  currentStatus;

    /** Populated on CREATED and UPDATED; null on STATUS_CHANGED/DELETED (avoid payload bloat) */
    private Map<String, Object> data;

    /** For STATUS_TRANSITION_REQUESTED only */
    private String  triggerName;
    private String  comment;

    private UUID    changedBy;
    private Instant occurredAt;
}
```

---

## Event: WorkflowEvent

```java
@Data @Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowEvent implements PlatformEvent {

    /** TRANSITIONED | APPROVAL_REQUESTED | APPROVAL_APPROVED
     *  APPROVAL_REJECTED | APPROVAL_TIMEOUT | TRANSITION_REJECTED */
    private String  eventType;

    private UUID    recordId;
    private UUID    orgId;
    private String  objectType;

    private UUID    workflowId;
    private String  fromState;
    private String  toState;
    private String  triggerName;
    private String  comment;

    private UUID    approvalInstanceId;   // only for APPROVAL_* events
    private String  approvalStep;

    private UUID    actorUserId;
    private Instant occurredAt;
}
```

---

## Event: TeamEvent

```java
@Data @Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class TeamEvent implements PlatformEvent {

    /** MEMBER_ADDED | MEMBER_REMOVED | ROLE_ASSIGNED | ROLE_REVOKED
     *  GROUP_CREATED | GROUP_DELETED */
    private String  eventType;

    private UUID    orgId;
    private UUID    groupId;
    private String  groupName;
    private UUID    userId;
    private String  roleName;

    private UUID    actorUserId;
    private Instant occurredAt;
}
```

---

## Event: AutomationEvent

```java
@Data @Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class AutomationEvent implements PlatformEvent {

    /** TRIGGER_FIRED | ACTION_SUCCEEDED | ACTION_FAILED | ESCALATION_FIRED */
    private String  eventType;

    private UUID    orgId;
    private UUID    triggerId;
    private String  triggerName;
    private UUID    recordId;
    private String  actionType;
    private boolean success;
    private String  errorMessage;

    private Instant occurredAt;
}
```

---

## Producer Template

```java
// In each service's event package
@Component
@RequiredArgsConstructor
public class RecordEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishCreated(Record record) {
        RecordEvent event = RecordEvent.builder()
            .eventType("CREATED")
            .recordId(record.getId())
            .orgId(record.getOrgId())
            .objectType(record.getObjectType())
            .currentStatus(record.getStatus())
            .data(record.getData())
            .changedBy(record.getCreatedBy())
            .occurredAt(Instant.now())
            .build();

        String key = record.getOrgId() + ":" + record.getId();

        kafkaTemplate.send(PlatformTopics.RECORD_EVENTS, key, event)
            .whenComplete((result, ex) -> {
                if (ex != null) log.error("Failed to publish RecordEvent", ex);
            });
    }
}
```

---

## Consumer Template

```java
@Component
@RequiredArgsConstructor
public class RecordEventConsumer {

    @KafkaListener(
        topics     = PlatformTopics.RECORD_EVENTS,
        groupId    = "search-service",
        properties = {
            "spring.json.value.default.type=com.lagu.platform.events.RecordEvent"
        }
    )
    public void handle(RecordEvent event, Acknowledgment ack) {
        try {
            searchIndexer.handle(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process RecordEvent {}", event.getRecordId(), e);
            // Do not ack — message will be retried
            // After max retries, Kafka sends to dead letter topic
        }
    }
}
```

---

## Kafka Config (shared pattern for each service)

### Producer (application.yml)

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
```

### Consumer (application.yml)

```yaml
spring:
  kafka:
    consumer:
      group-id: ${spring.application.name}
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      enable-auto-commit: false
      properties:
        spring.json.trusted.packages: "com.lagu.platform.events"
    listener:
      ack-mode: MANUAL_IMMEDIATE
```

---

## Dead Letter Topics

For each consumed topic, configure a dead letter topic:

```yaml
spring:
  kafka:
    consumer:
      properties:
        spring.kafka.listener.ack-mode: MANUAL_IMMEDIATE

# In KafkaConfig @Bean:
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
        (rec, ex) -> new TopicPartition(rec.topic() + ".DLT", rec.partition()));

    BackOff backOff = new FixedBackOff(1000L, 3L);  // 3 retries, 1s apart

    return new DefaultErrorHandler(recoverer, backOff);
}
```

DLT topics: `platform.record.events.DLT`, `platform.workflow.events.DLT`, etc.

---

## Topic Creation (infra/kafka/topics.sh)

Create topics with appropriate partitions on startup:

```bash
#!/bin/bash
kafka-topics.sh --bootstrap-server localhost:9092 --create \
  --topic platform.metadata.changed --partitions 3 --replication-factor 1

kafka-topics.sh --bootstrap-server localhost:9092 --create \
  --topic platform.record.events --partitions 12 --replication-factor 1

kafka-topics.sh --bootstrap-server localhost:9092 --create \
  --topic platform.workflow.events --partitions 6 --replication-factor 1

kafka-topics.sh --bootstrap-server localhost:9092 --create \
  --topic platform.team.events --partitions 3 --replication-factor 1

kafka-topics.sh --bootstrap-server localhost:9092 --create \
  --topic platform.automation.events --partitions 3 --replication-factor 1

# Dead letter topics
for topic in platform.record.events platform.workflow.events platform.metadata.changed; do
  kafka-topics.sh --bootstrap-server localhost:9092 --create \
    --topic ${topic}.DLT --partitions 1 --replication-factor 1
done
```

For local dev: set `auto.create.topics.enable=true` in docker-compose Kafka config (already set).

---

## Partition Strategy

`platform.record.events` gets 12 partitions — highest throughput topic.
Key: `orgId:recordId` → all events for one record go to the same partition (ordering guaranteed per record).

`platform.metadata.changed` gets 3 partitions — low volume.
Key: `orgId` → all metadata changes for one org are ordered.
