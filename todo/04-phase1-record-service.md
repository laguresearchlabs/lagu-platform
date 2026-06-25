# Phase 1 — Record Service

## Responsibility

Stores, validates, and retrieves business records (Venue, Photographer, Event, etc.) as
typed JSONB documents. The record-service is metadata-aware but metadata-agnostic — it
does not know what a "Venue" is. It only knows how to validate and persist a document
whose shape is described by the object type's schema projection.

---

## Core Concept

```
POST /records
{
  "objectType": "VENUE",
  "data": {
    "name": "Grand Palace Hall",
    "capacity": 500,
    "price": 75000,
    "currency": "INR"
  }
}
```

The record-service:
1. Fetches schema from metadata-service: `GET /object-types/VENUE/schema`
2. Validates each field in `data` against the schema rules
3. Persists to PostgreSQL JSONB
4. Publishes `RecordCreatedEvent` to Kafka

---

## Database Schema

### Flyway: `resources/db/migration/V1__record_schema.sql`

```sql
CREATE TABLE records.record (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID            NOT NULL,
    object_type     VARCHAR(100)    NOT NULL,
    status          VARCHAR(50)     NOT NULL DEFAULT 'DRAFT',
    data            JSONB           NOT NULL DEFAULT '{}',
    created_by      UUID,
    updated_by      UUID,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- GIN index for JSONB field queries
CREATE INDEX idx_record_data_gin ON records.record USING gin(data);

-- Common query patterns
CREATE INDEX idx_record_org_type    ON records.record (org_id, object_type);
CREATE INDEX idx_record_org_status  ON records.record (org_id, object_type, status);
CREATE INDEX idx_record_created_at  ON records.record (org_id, object_type, created_at DESC);

-- Audit log
CREATE TABLE records.record_audit (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id   UUID        NOT NULL REFERENCES records.record(id),
    action      VARCHAR(20) NOT NULL,   -- CREATED, UPDATED, STATUS_CHANGED, DELETED
    old_data    JSONB,
    new_data    JSONB,
    old_status  VARCHAR(50),
    new_status  VARCHAR(50),
    changed_by  UUID,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_record ON records.record_audit (record_id);
```

---

## Domain Entity

```java
@Entity
@Table(schema = "records", name = "record")
@Data
@NoArgsConstructor
public class Record {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "object_type", nullable = false, length = 100)
    private String objectType;

    @Column(nullable = false, length = 50)
    private String status = "DRAFT";

    @Convert(converter = JsonbConverter.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> data = new HashMap<>();

    @Column(name = "created_by")  private UUID createdBy;
    @Column(name = "updated_by")  private UUID updatedBy;
    @Column(name = "created_at")  private OffsetDateTime createdAt;
    @Column(name = "updated_at")  private OffsetDateTime updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = OffsetDateTime.now(); }
    @PreUpdate  void onUpdate() { updatedAt = OffsetDateTime.now(); }
}
```

---

## Validation Engine

This is the heart of the record-service. It enforces the metadata schema at write time.

```java
@Component
public class RecordValidator {

    private final MetadataClient metadataClient;

    public void validate(String objectType, Map<String, Object> data) {
        ObjectTypeSchema schema = metadataClient.getSchema(objectType);  // cached

        List<String> errors = new ArrayList<>();

        for (FieldSchema field : schema.getFields()) {
            Object value = data.get(field.getName());

            if (field.isRequired() && (value == null || isBlank(value))) {
                errors.add(field.getName() + " is required");
                continue;
            }
            if (value == null) continue;

            switch (field.getType()) {
                case NUMBER   -> validateNumber(field, value, errors);
                case DECIMAL  -> validateDecimal(field, value, errors);
                case TEXT     -> validateText(field, value, errors);
                case EMAIL    -> validateEmail(field, value, errors);
                case PHONE    -> validatePhone(field, value, errors);
                case ENUM     -> validateEnum(field, value, errors);
                case MULTI_SELECT -> validateMultiSelect(field, value, errors);
                case DATE     -> validateDate(field, value, errors);
                case BOOLEAN  -> validateBoolean(field, value, errors);
                // FILE, IMAGE — validate presence, skip content (handled by image-service)
            }
        }

        // Check for unknown fields (warn, don't reject — future-proofing)
        Set<String> knownFields = schema.getFields().stream()
            .map(FieldSchema::getName)
            .collect(Collectors.toSet());
        data.keySet().stream()
            .filter(k -> !knownFields.contains(k))
            .forEach(k -> log.warn("Unknown field '{}' in {} record — ignored", k, objectType));

        if (!errors.isEmpty()) {
            throw new RecordValidationException(objectType, errors);
        }
    }
}
```

---

## MetadataClient (Feign or RestClient)

```java
@Component
public class MetadataClient {

    private final RestClient restClient;
    private final CacheManager cacheManager;

    public ObjectTypeSchema getSchema(String objectTypeName) {
        String cacheKey = "schema:" + objectTypeName;
        Cache cache = cacheManager.getCache("object-type-schema");
        ObjectTypeSchema cached = cache.get(cacheKey, ObjectTypeSchema.class);
        if (cached != null) return cached;

        ObjectTypeSchema schema = restClient.get()
            .uri("/object-types/{name}/schema", objectTypeName)
            .retrieve()
            .body(ObjectTypeSchema.class);

        cache.put(cacheKey, schema);
        return schema;
    }
}
```

Configure `RestClient` to call metadata-service via Eureka load balancer:

```yaml
metadata-service:
  url: http://metadata-service   # resolved by Eureka
```

---

## REST API

Base path: `/api/v1/records` — routed via gateway at `/platform/records/**`

| Method | Path                                  | Description                              |
|--------|---------------------------------------|------------------------------------------|
| POST   | `/records`                            | Create record                            |
| GET    | `/records/{id}`                       | Get record by ID                         |
| PUT    | `/records/{id}`                       | Full update (all fields)                 |
| PATCH  | `/records/{id}`                       | Partial update (only provided fields)    |
| DELETE | `/records/{id}`                       | Soft delete (status = DELETED)           |
| GET    | `/records`                            | List records (see Query API below)       |
| GET    | `/records/{id}/history`               | Audit trail                              |
| POST   | `/records/{id}/status`                | Trigger status transition (→ workflow)   |

### Query API (Phase 1 — PostgreSQL JSONB queries)

```
GET /records?objectType=VENUE&status=ACTIVE&filter=capacity__gte:500&sort=data.name:asc&page=0&size=20
```

Filter operators:
- `field:value` — exact match
- `field__contains:value` — JSONB contains / text contains
- `field__gte:value` — ≥
- `field__lte:value` — ≤
- `field__in:v1,v2,v3` — IN list

Translate to native PostgreSQL JSONB queries:
```sql
WHERE org_id = :orgId
  AND object_type = 'VENUE'
  AND status = 'ACTIVE'
  AND (data->>'capacity')::int >= 500
ORDER BY data->>'name' ASC
```

**Phase 4 replaces this with OpenSearch.** Phase 1 JSONB queries are sufficient for
initial scale.

---

## Request / Response DTOs

```java
// CreateRecordRequest.java
@Data
public class CreateRecordRequest {
    @NotBlank
    private String objectType;

    @NotNull
    private Map<String, Object> data;

    private String status = "DRAFT";  // optional — default DRAFT
}

// RecordResponse.java
@Data
@Builder
public class RecordResponse {
    private UUID   id;
    private UUID   orgId;
    private String objectType;
    private String status;
    private Map<String, Object> data;
    private UUID   createdBy;
    private UUID   updatedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
```

---

## Kafka Events Published

Topic: `platform.record.events`

```java
// RecordEvent.java (in libs/events)
@Data @Builder
public class RecordEvent {
    private String    eventType;       // CREATED, UPDATED, STATUS_CHANGED, DELETED
    private UUID      recordId;
    private UUID      orgId;
    private String    objectType;
    private String    previousStatus;
    private String    currentStatus;
    private Map<String, Object> data;  // included on CREATED; omit on STATUS_CHANGED to save space
    private UUID      changedBy;
    private Instant   occurredAt;
}
```

Kafka topic constants (in `libs/events`):
```java
public final class PlatformTopics {
    public static final String RECORD_EVENTS    = "platform.record.events";
    public static final String METADATA_CHANGED = "platform.metadata.changed";
    public static final String WORKFLOW_EVENTS  = "platform.workflow.events";
    public static final String AUTOMATION_EVENTS = "platform.automation.events";
}
```

---

## Security Context Integration

Inject `PlatformSecurityContext` (from `libs/security`) to:
- Set `orgId` from `X-Org-Id` header on every record
- Set `createdBy` / `updatedBy` from `X-User-Id` header
- Filter all queries by `orgId` (never expose cross-tenant data)

```java
@Service
public class RecordService {

    public RecordResponse create(CreateRecordRequest req, PlatformSecurityContext ctx) {
        recordValidator.validate(req.getObjectType(), req.getData());

        Record record = new Record();
        record.setOrgId(ctx.getOrgId());       // from gateway header
        record.setCreatedBy(ctx.getUserId());  // from gateway header
        record.setObjectType(req.getObjectType().toUpperCase());
        record.setData(req.getData());
        record.setStatus(req.getStatus());

        Record saved = recordRepository.save(record);
        auditService.log(saved.getId(), "CREATED", null, saved.getData(), null, null, ctx.getUserId());
        eventPublisher.publishCreated(saved);

        return mapper.toResponse(saved);
    }
}
```

---

## Application Config

```yaml
spring:
  application:
    name: record-service
  datasource:
    url: jdbc:postgresql://localhost:5435/platformdb
    hikari:
      schema: records
  jpa:
    properties:
      hibernate:
        default_schema: records
  flyway:
    schemas: records
  data:
    redis:
      host: localhost
      port: 6380
  kafka:
    bootstrap-servers: localhost:9092

platform:
  metadata-service:
    url: http://metadata-service   # Eureka service ID
    schema-cache-ttl: 300          # seconds
```

---

## JsonbConverter (shared in libs/common)

```java
@Converter(autoApply = false)
public class JsonbConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null) return null;
        try { return MAPPER.writeValueAsString(attribute); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try { return MAPPER.readValue(dbData, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }
}
```

Also create `JsonbListConverter` for `List<String>` (used in enum_values).

---

## Error Handling

```java
// RecordValidationException.java
public class RecordValidationException extends RuntimeException {
    private final String objectType;
    private final List<String> fieldErrors;
}

// GlobalExceptionHandler.java — @RestControllerAdvice
@ExceptionHandler(RecordValidationException.class)
public ResponseEntity<ApiError> handleValidation(RecordValidationException ex) {
    return ResponseEntity.badRequest().body(
        ApiError.builder()
            .code("RECORD_VALIDATION_FAILED")
            .message("Record validation failed for " + ex.getObjectType())
            .details(ex.getFieldErrors())
            .build()
    );
}
```

---

## Implementation Checklist

- [ ] Create `apps/record-service` module
- [ ] Implement `Record` entity + `RecordAudit` entity
- [ ] Implement `JsonbConverter` + `JsonbListConverter` in `libs/common`
- [ ] Implement `MetadataClient` with Redis caching
- [ ] Implement `RecordValidator` covering all `AttributeType` cases
- [ ] Implement `RecordService` (create, update, patch, delete, list, get)
- [ ] Implement JSONB query builder for list/filter API
- [ ] Implement audit logging
- [ ] Implement `RecordEventPublisher` (Kafka)
- [ ] Implement `PlatformSecurityContext` injection via filter
- [ ] Write integration tests with Testcontainers
- [ ] Verify cross-tenant isolation (org_id filter on all queries)
- [ ] Add GIN index and verify JSONB query performance
