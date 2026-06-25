# Phase 1 — Metadata Service

## Responsibility

Single source of truth for all platform definitions:
- **AttributeDefinition** — the field types (superset of all possible fields)
- **EntityDefinition** — named groups of attributes (reusable sections)
- **ObjectTypeDefinition** — the top-level business object (Venue, Photographer, Event…)
- **RelationshipDefinition** — declared in Phase 2 but schema created here

Everything else (record-service, workflow-service, search-service) reads from this service.
Metadata changes rarely; aggressive caching is safe and required.

---

## Database Schema

### Flyway location: `resources/db/migration/V1__metadata_schema.sql`

```sql
-- ── Attribute Definitions ──────────────────────────────────────────────────

CREATE TABLE metadata.attribute_definition (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID,           -- NULL = platform-level (available to all orgs)
    name            VARCHAR(100)    NOT NULL,
    label           VARCHAR(200)    NOT NULL,
    description     TEXT,
    attribute_type  VARCHAR(50)     NOT NULL,   -- see AttributeType enum
    is_required     BOOLEAN         NOT NULL DEFAULT false,
    is_searchable   BOOLEAN         NOT NULL DEFAULT false,
    is_filterable   BOOLEAN         NOT NULL DEFAULT false,
    is_sortable     BOOLEAN         NOT NULL DEFAULT false,
    is_facetable    BOOLEAN         NOT NULL DEFAULT false,
    is_unique       BOOLEAN         NOT NULL DEFAULT false,
    default_value   TEXT,
    validation_rules JSONB,          -- {"min": 0, "max": 1000, "pattern": "..."}
    enum_values     JSONB,          -- ["OPTION_A", "OPTION_B"] for ENUM / MULTI_SELECT
    config          JSONB,          -- type-specific config (e.g. entity ref target type)
    is_active       BOOLEAN         NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_attr_name_org UNIQUE (name, org_id)
);

CREATE INDEX idx_attr_org ON metadata.attribute_definition (org_id);
CREATE INDEX idx_attr_type ON metadata.attribute_definition (attribute_type);

-- ── Entity Definitions ─────────────────────────────────────────────────────

CREATE TABLE metadata.entity_definition (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID,
    name        VARCHAR(100)    NOT NULL,
    label       VARCHAR(200)    NOT NULL,
    description TEXT,
    is_active   BOOLEAN         NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_entity_name_org UNIQUE (name, org_id)
);

CREATE TABLE metadata.entity_attribute (
    entity_id       UUID    NOT NULL REFERENCES metadata.entity_definition(id) ON DELETE CASCADE,
    attribute_id    UUID    NOT NULL REFERENCES metadata.attribute_definition(id),
    display_order   INT     NOT NULL DEFAULT 0,
    is_required     BOOLEAN NOT NULL DEFAULT false,  -- override per-entity
    PRIMARY KEY (entity_id, attribute_id)
);

-- ── Object Type Definitions ────────────────────────────────────────────────

CREATE TABLE metadata.object_type_definition (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID,
    name            VARCHAR(100)    NOT NULL,
    label           VARCHAR(200)    NOT NULL,
    description     TEXT,
    icon            VARCHAR(100),
    color           VARCHAR(20),
    is_publishable  BOOLEAN         NOT NULL DEFAULT false,
    is_active       BOOLEAN         NOT NULL DEFAULT true,
    config          JSONB,          -- future extensibility
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_objtype_name_org UNIQUE (name, org_id)
);

-- sections within an object type (Basic Details, Contact, Pricing…)
CREATE TABLE metadata.object_type_section (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    object_type_id  UUID            NOT NULL REFERENCES metadata.object_type_definition(id) ON DELETE CASCADE,
    entity_id       UUID            NOT NULL REFERENCES metadata.entity_definition(id),
    label           VARCHAR(200),   -- override entity label if needed
    display_order   INT             NOT NULL DEFAULT 0,
    is_collapsible  BOOLEAN         NOT NULL DEFAULT false
);

-- ── Relationship Definitions (schema placeholder, logic in Phase 2) ────────

CREATE TABLE metadata.relationship_definition (
    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID,
    name                VARCHAR(100)    NOT NULL,
    label               VARCHAR(200)    NOT NULL,
    source_object_type  VARCHAR(100)    NOT NULL,
    target_object_type  VARCHAR(100)    NOT NULL,
    relationship_type   VARCHAR(50)     NOT NULL,  -- ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY, PARENT_CHILD
    is_required         BOOLEAN         NOT NULL DEFAULT false,
    cascade_delete      BOOLEAN         NOT NULL DEFAULT false,
    is_active           BOOLEAN         NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);
```

---

## AttributeType Enum (Java)

```java
package com.lagu.platform.metadata.domain;

public enum AttributeType {
    TEXT,
    LONG_TEXT,
    NUMBER,
    DECIMAL,
    BOOLEAN,
    DATE,
    DATETIME,
    TIME,
    EMAIL,
    PHONE,
    URL,
    ADDRESS,
    GEOLOCATION,
    CURRENCY,
    ENUM,
    MULTI_SELECT,
    FILE,
    IMAGE,
    ENTITY_REFERENCE,
    USER_REFERENCE,
    JSON
}
```

---

## Domain Entities

```java
// AttributeDefinition.java
@Entity
@Table(schema = "metadata", name = "attribute_definition")
@Data
@NoArgsConstructor
public class AttributeDefinition {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 200)
    private String label;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "attribute_type", nullable = false, length = 50)
    private AttributeType attributeType;

    @Column(name = "is_required")      private boolean required;
    @Column(name = "is_searchable")    private boolean searchable;
    @Column(name = "is_filterable")    private boolean filterable;
    @Column(name = "is_sortable")      private boolean sortable;
    @Column(name = "is_facetable")     private boolean facetable;
    @Column(name = "is_unique")        private boolean unique;

    @Column(name = "default_value")    private String defaultValue;

    // Store as JSONB via converter
    @Convert(converter = JsonbConverter.class)
    @Column(name = "validation_rules", columnDefinition = "jsonb")
    private Map<String, Object> validationRules;

    @Convert(converter = JsonbConverter.class)
    @Column(name = "enum_values", columnDefinition = "jsonb")
    private List<String> enumValues;

    @Convert(converter = JsonbConverter.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "is_active") private boolean active = true;

    @Column(name = "created_at") private OffsetDateTime createdAt;
    @Column(name = "updated_at") private OffsetDateTime updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = OffsetDateTime.now(); }
    @PreUpdate  void onUpdate() { updatedAt = OffsetDateTime.now(); }
}
```

Apply the same pattern for `EntityDefinition`, `ObjectTypeDefinition`, `ObjectTypeSection`.

---

## REST API

Base path: `/api/v1` — all routed via gateway at `/platform/metadata/**`

### Attribute Definitions

| Method | Path                          | Description                            |
|--------|-------------------------------|----------------------------------------|
| GET    | `/attributes`                 | List (paginated, filterable by type)   |
| POST   | `/attributes`                 | Create                                 |
| GET    | `/attributes/{id}`            | Get by ID                              |
| PUT    | `/attributes/{id}`            | Update (non-breaking changes only)     |
| DELETE | `/attributes/{id}`            | Soft delete (set is_active = false)    |
| GET    | `/attributes/by-name/{name}`  | Get by name (for runtime validation)   |

### Entity Definitions

| Method | Path                                      | Description                |
|--------|-------------------------------------------|----------------------------|
| GET    | `/entities`                               | List                       |
| POST   | `/entities`                               | Create                     |
| GET    | `/entities/{id}`                          | Get with attributes        |
| PUT    | `/entities/{id}`                          | Update                     |
| POST   | `/entities/{id}/attributes`               | Add attribute to entity    |
| DELETE | `/entities/{id}/attributes/{attributeId}` | Remove attribute            |

### Object Type Definitions

| Method | Path                             | Description                          |
|--------|----------------------------------|--------------------------------------|
| GET    | `/object-types`                  | List                                 |
| POST   | `/object-types`                  | Create                               |
| GET    | `/object-types/{id}`             | Get full definition (with sections)  |
| GET    | `/object-types/by-name/{name}`   | Get by name (used by record-service) |
| PUT    | `/object-types/{id}`             | Update                               |
| POST   | `/object-types/{id}/sections`    | Add section (entity) to object type  |
| PUT    | `/object-types/{id}/sections`    | Reorder sections                     |
| DELETE | `/object-types/{id}/sections/{sectionId}` | Remove section              |

### Schema Projection (used by record-service to build validation rules)

```
GET /object-types/{name}/schema
```

Returns a flat map of all fields (across all sections) with their validation rules:

```json
{
  "objectType": "VENUE",
  "fields": [
    {
      "name": "venueName",
      "label": "Venue Name",
      "type": "TEXT",
      "required": true,
      "searchable": true,
      "validation": { "maxLength": 200 }
    },
    {
      "name": "capacity",
      "label": "Capacity",
      "type": "NUMBER",
      "required": true,
      "filterable": true,
      "sortable": true,
      "validation": { "min": 1, "max": 100000 }
    }
  ]
}
```

This is the most critical endpoint — record-service calls it on every create/update.
**Cache this response in Redis with a 5-minute TTL.**

---

## Caching Strategy

Use Spring Cache + Redis:

```java
@Configuration
@EnableCaching
public class MetadataCacheConfig {
    public static final String CACHE_OBJECT_TYPE_SCHEMA = "object-type-schema";
    public static final String CACHE_ATTRIBUTE_DEFS     = "attribute-defs";
    public static final String CACHE_ENTITY_DEFS        = "entity-defs";

    @Bean
    public RedisCacheConfiguration cacheDefaults() {
        return RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(5))
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair
                    .fromSerializer(new GenericJackson2JsonRedisSerializer())
            );
    }
}
```

Evict on mutation:
```java
@CacheEvict(value = CACHE_OBJECT_TYPE_SCHEMA, key = "#objectTypeName")
public void updateObjectType(String objectTypeName, ...) { ... }
```

---

## Kafka Events Published

All events use the topic `platform.metadata.changed`.

```java
// MetadataChangedEvent.java (in libs/events)
@Data
@Builder
public class MetadataChangedEvent {
    private String eventType;        // OBJECT_TYPE_CREATED, ATTRIBUTE_UPDATED, etc.
    private String objectType;       // "VENUE", "PHOTOGRAPHER", etc. (if applicable)
    private UUID   resourceId;
    private UUID   orgId;
    private Instant occurredAt;
}
```

Downstream (search-service) consumes this to rebuild index mappings when a definition changes.

---

## Application Config

```yaml
# apps/metadata-service/src/main/resources/application.yml
spring:
  application:
    name: metadata-service
  datasource:
    url: jdbc:postgresql://localhost:5435/platformdb
    username: postgres
    password: postgres
    hikari:
      schema: metadata
  jpa:
    properties:
      hibernate:
        default_schema: metadata
    show-sql: false
  flyway:
    schemas: metadata
    locations: classpath:db/migration
  data:
    redis:
      host: localhost
      port: 6380
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka
  instance:
    prefer-ip-address: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus

server:
  port: 8080

springdoc:
  swagger-ui:
    path: /swagger-ui.html
```

---

## Validation Rules

When creating/updating an AttributeDefinition, enforce:

- `name` must be snake_case (`[a-z][a-z0-9_]*`)
- `enumValues` must be non-empty when `type = ENUM` or `MULTI_SELECT`
- `config.targetObjectType` must be set when `type = ENTITY_REFERENCE`
- Reject `PUT` that changes `attributeType` if the attribute is in use by any object type (breaking change)

---

## Seed Data (Bootstrap)

On startup, load platform-level (org_id = null) attributes that all object types can reuse:

| name         | type    | label        |
|--------------|---------|--------------|
| name         | TEXT    | Name         |
| description  | LONG_TEXT | Description|
| phone        | PHONE   | Phone        |
| email        | EMAIL   | Email        |
| website      | URL     | Website      |
| address_line1| TEXT    | Address Line 1|
| address_line2| TEXT    | Address Line 2|
| city         | TEXT    | City         |
| state        | TEXT    | State        |
| country      | TEXT    | Country      |
| postal_code  | TEXT    | Postal Code  |
| latitude     | DECIMAL | Latitude     |
| longitude    | DECIMAL | Longitude    |
| price        | DECIMAL | Price        |
| currency     | ENUM    | Currency     |
| capacity     | NUMBER  | Capacity     |
| is_active    | BOOLEAN | Active       |

Use a `data.sql` or a `DataInitializer` @Component with `@ConditionalOnProperty(name="platform.seed-data")`.

---

## Implementation Checklist

- [ ] Create `apps/metadata-service` module
- [ ] Create `AttributeDefinition` entity + repository + service + controller
- [ ] Create `EntityDefinition` entity + `EntityAttribute` join + repository + service + controller
- [ ] Create `ObjectTypeDefinition` + `ObjectTypeSection` entities + repository + service + controller
- [ ] Implement `/object-types/{name}/schema` projection endpoint
- [ ] Implement Redis caching on schema projection
- [ ] Implement `JsonbConverter` for JSONB columns
- [ ] Write Flyway migrations
- [ ] Implement Kafka producer for `MetadataChangedEvent`
- [ ] Implement input validation (snake_case names, enum consistency)
- [ ] Write seed data loader
- [ ] Write integration tests with Testcontainers (PostgreSQL)
- [ ] Register with Eureka
- [ ] Configure Swagger / OpenAPI
