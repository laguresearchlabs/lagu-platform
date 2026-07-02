# lagu-platform — Architecture Overview

## What This Is

A configurable, metadata-driven business platform — the single backend that replaces separate
Vendor Management, Event Management, Venue Management, Photographer Management, and Catering
Management systems. Business domains become configuration, not code.

---

## Position in the Lagu Ecosystem

```
┌──────────────────────────────────────────────────────────────────┐
│                         Client Apps                              │
│              (web portal / mobile / partner API)                 │
└─────────────────────────────┬────────────────────────────────────┘
                              │ HTTPS
                              ▼
                   ┌──────────────────┐
                   │  gateway-service │  JWT validation, rate-limit,
                   │  (Spring Cloud   │  circuit-breaker, routing
                   │   Gateway)       │
                   └────────┬─────────┘
                            │ X-User-Id / X-Org-Id / X-User-Roles headers
            ┌───────────────┼───────────────┐
            │               │               │
            ▼               ▼               ▼
   ┌────────────┐  ┌─────────────────┐  ┌──────────┐
   │iam-services│  │  lagu-platform  │  │  other   │
   │ (auth/otp/ │  │  (this repo)    │  │  future  │
   │  user)     │  └────────┬────────┘  │  services│
   └────────────┘           │           └──────────┘
                            │
              ┌─────────────▼──────────────┐
              │       Kafka (event bus)     │
              └─────────────────────────────┘
                            │
         ┌──────────────────┼────────────────┐
         ▼                  ▼                ▼
   PostgreSQL             Redis          OpenSearch
   (JSONB records)       (cache)        (Phase 4 search)
```

**lagu-platform services never call iam-services directly.** The gateway extracts identity from
the JWT and forwards it as trusted headers. Services validate these headers, not the JWT itself.

---

## Internal Service Map

```
lagu-platform/
├── apps/
│   ├── schema-registry      field/listing-type/relationship definitions (absorbed metadata-service — see todo/13-no-code-vendor-platform-adr.md)
│   ├── record-service       Phase 1  — dynamic CRUD, JSONB storage, form validation
│   ├── workflow-service     Phase 3  — state machines, transition rules, approvals
│   ├── search-service       Phase 4  — OpenSearch indexing & query
│   └── automation-service   Phase 5  — trigger rules, action execution
└── libs/
    ├── common               shared DTOs, base exceptions, page utils, security context
    ├── events               Kafka event schemas (POJOs + constants)
    └── security             gateway header extraction, org/user context holder
```

All services register with the existing **registry-service** (Eureka) and are routed through
**gateway-service**.

---

## Tech Stack

| Concern            | Choice                                      | Notes                                      |
|--------------------|---------------------------------------------|--------------------------------------------|
| Language           | Java 25                                     | Consistent with project preference         |
| Framework          | Spring Boot 4.1.0                           | See risk note in `11-decisions-and-risks`  |
| Build              | Gradle 9.x (Kotlin DSL)                     | All new modules use Kotlin DSL             |
| Database           | PostgreSQL 16 + JSONB                       | One DB, separate schemas per service       |
| DB Migrations      | Flyway                                      | Per-service migration scripts              |
| Cache              | Redis 7                                     | Definition cache, record cache             |
| Event Bus          | Kafka 3.x (via Bitnami image)               | Topics defined in `10-kafka-event-contracts` |
| Search             | OpenSearch 2.x (Phase 4)                    | Phase 1–3 use PG full-text search          |
| Service Discovery  | Eureka (via registry-service)               | Spring Cloud Eureka Client                 |
| API Docs           | Springdoc OpenAPI 3                         | `/swagger-ui.html` per service             |
| Observability      | Micrometer + OTel + Prometheus              | Consistent with existing services          |
| Mapping            | MapStruct                                   | Entity ↔ DTO                               |
| Boilerplate        | Lombok                                      | Consistent with existing services          |

---

## Core Design Principles

### 1. Metadata is the product
Business domains (Venue, Photographer, Event, etc.) are object type configurations, not separate
services. Adding a new domain = admin creates a new ObjectTypeDefinition.

### 2. Never hardcode business fields
Every field in every form is an `AttributeDefinition`. The record-service reads them at runtime
to validate and store data. No `ALTER TABLE` for new business domains.

### 3. Records are typed JSON documents
```sql
CREATE TABLE records (
  id            UUID PRIMARY KEY,
  org_id        UUID NOT NULL,
  object_type   VARCHAR(100) NOT NULL,
  status        VARCHAR(50)  NOT NULL,
  data          JSONB        NOT NULL,
  created_by    UUID,
  created_at    TIMESTAMPTZ  DEFAULT now(),
  updated_at    TIMESTAMPTZ  DEFAULT now()
);
```
GIN index on `data` for JSONB queries. No column-per-field EAV nightmare.

### 4. Events drive everything
State changes publish Kafka events. Workflow transitions, approval steps, and automation rules
all consume these events — they don't call each other synchronously.

### 5. Multi-tenancy is `org_id`
Every table that holds business data includes `org_id`. Services filter by it on every query.
No cross-tenant data leaks.

---

## Phase Roadmap

| Phase | Scope                                                        | Deliverable                            |
|-------|--------------------------------------------------------------|----------------------------------------|
| 1     | Metadata service + Record CRUD                               | Configurable form engine, dynamic CRUD |
| 2     | Relationships + Teams + Role-based access control            | Org hierarchy, permission gates        |
| 3     | Workflow engine + Approval engine                            | Status lifecycle, multi-step approvals |
| 4     | Search service (OpenSearch)                                  | Faceted search, filters, ranking       |
| 5     | Automation engine                                            | Triggers, actions, notifications       |

Each phase is independently deployable. Phase N does not block using Phase N-1 in production.
