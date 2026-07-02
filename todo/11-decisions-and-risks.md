# Decisions & Risks

## Architectural Decisions Made (ADRs)

---

### ADR-01: JSONB Over EAV for Record Storage

**Chosen**: PostgreSQL JSONB  
**Rejected**: EAV (entity_id / attribute_id / value tables)

**Why**:
- EAV requires 1 row per field per record → querying a 20-field record needs 20 joins or a pivot
- EAV type safety is a nightmare — everything is TEXT, you cast manually
- JSONB is indexed (GIN), queryable with operators (`->>`, `@>`, `?`), and performs well at scale
- PostgreSQL JSONB is the industry-standard approach for flexible schemas (used by Retool, Airtable internals)

**Trade-offs**:
- No foreign key constraints within the JSONB blob
- Reporting tools that expect relational tables need a view layer
- Mitigation: use generated columns or views for commonly-reported fields

---

### ADR-02: Schema-per-Service in One PostgreSQL Instance

**Chosen**: One PostgreSQL DB (`platformdb`), separate schemas (`metadata`, `records`, `workflow`, etc.)  
**Rejected**: Separate DB per service, or shared schema

**Why**:
- Separate DB per service is correct for true isolation, but creates operational complexity for local dev and small-scale deployments
- Separate schemas give logical isolation + separate Flyway migration paths without needing multiple PostgreSQL instances
- Cross-schema JOINs are possible when needed (e.g., for admin reporting)
- Can migrate to separate DB instances per service later with minimal code change (just change the JDBC URL)

**Migration path to full separation**: Update `SPRING_DATASOURCE_URL` per service → schemas become databases. Flyway handles the rest.

---

### ADR-03: Kafka for All Cross-Service Events

**Chosen**: Kafka  
**Rejected**: Synchronous HTTP (RestClient/Feign), Spring ApplicationEvents (in-process), DB polling outbox

**Why**:
- Synchronous HTTP creates tight coupling and cascading failures (if workflow-service is down, record-service can't create records)
- Kafka decouples producers from consumers entirely — search-service can restart and replay events
- Audit/replay is built-in — every event is durable and replayable
- Multiple consumers per topic is free (search + workflow + automation all consume record events independently)

**Risk**: Kafka adds operational complexity. For very early dev, you can stub the Kafka publisher with a no-op and re-enable when infra is ready.

---

### ADR-04: Spring Boot 4.1.0 + Java 25

**Chosen by user**: Spring Boot 4.1.0 + Java 25

**Resolved (2026-07-02)**: Spring Cloud 2025.1.2 (released 2026-06-11, "Oakwood") added compatibility
with Spring Boot 4.1.0 — confirmed against the official release notes at
https://spring.io/blog/2026/06/11/spring-cloud-2025-1-2-aka-oakwood-has-been-released/.
`gradle/libs.versions.toml` already pins `spring-boot = "4.1.0"` and `spring-cloud = "2025.1.2"`,
which is the correct, currently-supported pairing — no change needed.

---

### ADR-05: One Service = One Port in Docker Compose

Assigned ports avoid conflicts with existing services:

| Existing           | Port  |
|--------------------|-------|
| IAM PostgreSQL     | 5434  |
| IAM Redis          | 6379  |

| New Platform       | Port  |
|--------------------|-------|
| Platform PostgreSQL| 5435  |
| Platform Redis     | 6380  |
| Kafka              | 9092  |
| OpenSearch         | 9200  |

If running all services locally simultaneously, verify these don't clash with any other services in the ecosystem.

---

### ADR-06: No Frontend in Phase 1-3

The metadata and record APIs are designed to be consumed by any frontend later. For now,
use Swagger UI at `/swagger-ui.html` per service for testing.

When frontend is added (React/Next.js or other), the gateway routes it cleanly:
```
/platform/**     → backend services (existing)
/app/**          → frontend Next.js server
```

---

## Risks

### R-01: ObjectType schema changes break existing records

**Risk**: If admin removes a required field from an ObjectTypeDefinition, existing records
become "invalid" under the new schema.

**Mitigation**:
- Schema changes are additive-only by default. Remove the `required` flag before removing a field.
- Add a `BREAKING_CHANGE` flag on attribute updates. System warns admin and prevents immediate deletion of in-use attributes.
- Implement a `record-service` validation mode: `STRICT` (default) vs `LAX` (ignore unknown/missing fields during read).

---

### R-02: Metadata cache stale after definition change

**Risk**: record-service caches the object type schema in Redis. If admin updates the schema,
record-service may validate against stale rules for up to the TTL (5 minutes).

**Mitigation**:
- `MetadataChangedEvent` consumed by record-service to evict its own cache immediately
- Default TTL of 5 minutes is acceptable for most use cases
- Add a `POST /admin/cache/evict/{objectType}` endpoint in record-service for forced eviction

---

### R-03: JSONB GIN index performance at high record counts

**Risk**: GIN index on the full `data` jsonb column becomes expensive as record counts grow
(millions of records per org).

**Mitigation**:
- Add expression indexes on commonly filtered fields:
  ```sql
  CREATE INDEX idx_record_capacity ON records.record ((data->>'capacity')::int)
    WHERE object_type = 'VENUE';
  ```
- Generated columns for high-cardinality filter fields:
  ```sql
  ALTER TABLE records.record
    ADD COLUMN city TEXT GENERATED ALWAYS AS (data->>'city') STORED;
  CREATE INDEX idx_record_city ON records.record (org_id, object_type, city);
  ```
- Phase 4 OpenSearch takes over heavy query load, reducing PostgreSQL to writes only

---

### R-04: Kafka consumer lag during high record creation bursts

**Risk**: If record-service creates 10,000 records in bulk import, search-service falls behind.

**Mitigation**:
- OpenSearch bulk indexing (batch consumer using `@KafkaListener` + `BatchMessageConverter`)
- Monitor consumer lag via Kafdrop (included in docker-compose)
- Set consumer `max.poll.records=500` and batch-index to OpenSearch in one request

---

### R-05: Circular dependency: record-service calls metadata-service, metadata-service publishes events consumed by record-service

This is NOT a circular dependency — these are two different flows:
- record-service → metadata-service: synchronous HTTP (schema lookup, cached)
- metadata-service → Kafka → record-service: async cache eviction

No circular risk, but be careful not to have record-service call metadata-service in the
Kafka consumer path (would re-introduce synchronous coupling in the event path).

---

### R-06: Approval step role must match user's runtime role

**Risk**: The approval step says `approver_role = "CONFIG_ADMIN"` but the user performing
the approval doesn't have that role at approval time (role was revoked after the approval
instance was created).

**Mitigation**:
- Always check the current role at decision time, not at approval creation time
- If approver's role is revoked, escalate the step to the next available user with that role
- Log the role check result in `approval_step_decision` for audit purposes

---

## Open Questions (to revisit before Phase 2)

1. **Pricing Engine**: The design mentions configurable pricing models (FIXED, PER_HOUR, PER_PERSON, etc.). 
   Should this be:
   - A specific attribute type (`PRICING_MODEL` in AttributeType enum)?
   - A separate entity that vendors attach to their records?
   - A structured JSONB field with a known schema?
   
   Recommendation: Add `PRICING` as a first-class entity definition (in Phase 1 seed data)
   with fields: `pricingModel (ENUM)`, `amount (DECIMAL)`, `currency (ENUM)`, `unit (TEXT)`.
   Vendors use ENTITY_REFERENCE to attach pricing to their record.

2. **Reporting Engine**: Mentioned in the design but not scheduled. Phase 4 or 5?
   OpenSearch + Dashboards covers 80% of reporting needs. Custom report builder = Phase 6.

3. **Image/File handling**: Records can have FILE and IMAGE attribute types.
   There's an existing `image-service` in the ecosystem. Should record-service call
   image-service for upload URLs, or should files be handled at the gateway level?
   Recommendation: record-service stores file references (URLs/IDs). Upload goes directly
   to image-service. Record's `data.profilePhoto` = `image-service:/{imageId}`.
