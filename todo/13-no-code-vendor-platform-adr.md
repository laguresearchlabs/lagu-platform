# No-Code Vendor Platform — Architecture Decision Records

> These ADRs extend the existing decisions in `11-decisions-and-risks.md`.
> Context: analysis of vendor-management vs lagu-platform capabilities (2026-07-01),
> leading to a greenfield redesign of the vendor/listing platform on top of lagu-platform core.

---

## ADR-07: Schema Registry as the Central No-Code Configuration Service

**Status**: Accepted

**Context**

lagu-platform already has `metadata-service` which stores ObjectTypeDefinition, AttributeDefinition,
WorkflowDefinition, TierConfiguration, DocumentTypeDefinition, etc. However these live as raw data
rows with no cohesive ownership — workflow-service owns transition data, document-service owns its
type list, automation-service owns trigger definitions. There is no single UI surface an admin
can use to configure a new listing type end-to-end.

The requirement is that adding a new vendor service type (e.g. "Tent House Rental") involves
zero code deployment. Everything — form schema, workflow, document requirements, tier rules,
search facets — must be configurable from a UI.

**Decision**

Introduce `schema-registry` as a new platform-core service. It absorbs `metadata-service`
completely and becomes the single source of truth for all configurable platform behaviour.

It owns:
- `ListingTypeDefinition` (replaces ObjectTypeDefinition) — sections, fields, validation
- `WorkflowDefinition` — states, transitions, guards, approval steps (currently in workflow-service tables but driven by data from metadata-service)
- `DocumentRequirements` — per listing type, per tier (replaces DocumentTypeDefinition)
- `TierEligibilityRule` — pre-upgrade eligibility checks (new — gap in current platform)
- `TierConfiguration` — commission, boost, limits per tier (already in metadata-service)
- `AutomationTriggerTemplate` — platform-level trigger definitions seeded per listing type
- `SearchDefinition` — consumer facets, admin facets, default sort per listing type
- `SchemaVersion` — version number per listing type; records carry `schema_version`

On schema publish, schema-registry emits `SchemaPublishedEvent` (Kafka) consumed by:
- `listing-service` → rebuilds dynamic section router + OpenSearch index mapping
- `workflow-engine` → upserts WorkflowDefinition rows
- `document-service` → refreshes DocumentTypeRegistry
- All services → evict Redis schema cache for that listing type

**Rejected alternatives**

- *Keep metadata-service as-is, scatter config across services*: impossible to build a
  coherent UI over distributed configuration; admin would need to navigate 5 services to
  fully configure one listing type.
- *Generate code from schema*: defeats the no-code goal; every schema change would require
  a build and deploy.

**Trade-offs**

- schema-registry becomes a critical dependency; if it is down, listing-service cannot
  validate new submissions. Mitigation: all consumers cache schemas in Redis (TTL 5 min)
  so the platform degrades gracefully to cached schemas when schema-registry is unavailable.
- Schema versioning adds complexity (see ADR-12).

---

## ADR-08: Hybrid PostgreSQL Storage for Listings (Promoted Columns + JSONB)

**Status**: Accepted

**Context**

vendor-management used MongoDB for venue-service to handle a deeply nested 14-section venue
document. The analysis showed three concrete problems with this for lagu-platform:

1. MongoDB has no transactions across collections and weak schema enforcement.
2. lagu-platform's existing `search-service` cannot handle nested arrays (halls[], packages[])
   in OpenSearch because JSONB flat mapping doesn't emit `"type": "nested"` blocks.
3. Per-venue section PATCH endpoints with cross-field validation don't fit the generic
   `PATCH /records/{id}` merge model.

**Decision**

All listing data is stored in a single PostgreSQL `listing` table using a hybrid model:

```sql
CREATE TABLE listing (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL,
    listing_type    VARCHAR(50) NOT NULL,
    status          VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    schema_version  INTEGER NOT NULL,

    -- Promoted columns: top 5-8 most common consumer filters.
    -- Populated from JSONB by listing-service on every write.
    -- Which fields are "promoted" is declared in schema-registry
    -- via FieldDefinition.searchConfig.promoted = true.
    city            VARCHAR(100),
    state           VARCHAR(100),
    starting_price  DECIMAL(12,2),
    guest_capacity  INTEGER,
    listing_name    VARCHAR(300),

    -- Full schema-driven data (every field, every section)
    data            JSONB NOT NULL,

    -- Optimistic locking for concurrent section edits
    version         BIGINT NOT NULL DEFAULT 0,

    created_by      VARCHAR(255),
    updated_by      VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Fast partial index for consumer search (most common query)
CREATE INDEX idx_listing_consumer
    ON listing(listing_type, city, starting_price)
    WHERE status = 'ACTIVE';

-- GIN for JSONB ad-hoc + OpenSearch miss fallback
CREATE INDEX idx_listing_data_gin
    ON listing USING GIN(data jsonb_path_ops);

-- Admin/vendor queries scoped to org
CREATE INDEX idx_listing_org
    ON listing(org_id, listing_type, status, created_at DESC);
```

Promoted columns absorb ~90% of common consumer filter queries without involving OpenSearch.
OpenSearch handles full-text, complex facet aggregations, and the cross-org consumer index.

Deeply nested arrays (halls, packages, etc.) are stored in the JSONB `data` column and indexed
in OpenSearch via `"type": "nested"` mappings auto-generated from schema-registry field definitions
that carry `type: ARRAY_OF_OBJECTS` + `searchable: true`.

**Rejected alternatives**

- *MongoDB*: No transactions; weak schema enforcement; separate operational stack;
  poor relational join capability for cross-service admin queries.
- *Pure JSONB only (no promoted columns)*: All filter queries hit the GIN index or
  OpenSearch. Adds OpenSearch as a hard runtime dependency for basic listing queries.
  PostgreSQL partial indexes on promoted columns are faster and simpler.
- *EAV*: Already rejected in ADR-01. Same reasoning applies.

**Trade-offs**

- Promoted columns are denormalised data — they must be kept in sync with the JSONB `data`
  blob. listing-service is the only writer and always updates both atomically.
- Schema-registry must declare which fields are `promoted: true`. Changing a field from
  promoted to non-promoted (or vice versa) requires a data migration to backfill the column.

---

## ADR-09: One listing-service for All Listing Types

**Status**: Accepted

**Context**

vendor-management started with `venue-service` as a standalone service for venues. As listing
types grow (PHOTOGRAPHER, CATERER, DECORATOR, MAKEUP_ARTIST, MEHNDI, MUSIC_DJ, TENT_HOUSE, …),
this pattern requires one new service per type — each with its own database, deploy pipeline,
health checks, circuit breakers, and bespoke validation code.

**Decision**

One `listing-service` handles all listing types. Listing type behaviour is schema-driven:

- Sections and fields come from schema-registry `ListingTypeDefinition`.
- Section-level PATCH endpoints are registered at startup by reading the schema.
  Any new listing type published to schema-registry causes listing-service to register
  new routes without a deploy.
- Array sub-entity operations (add/update/delete an item in a nested array like `halls[]`)
  are generated for any `ARRAY_OF_OBJECTS` field with `arrayManageable: true` in the schema.
- Validation rules per field and per section come from the schema.
- Submit prerequisites (readiness checks before status transition) are declared in the
  workflow transition's `conditions` block in schema-registry and evaluated by workflow-engine.

**Rejected alternatives**

- *Per-type services (venue-service, photographer-service, …)*: Does not scale operationally.
  Each new listing type requires a code deployment. Violates the no-code requirement.
- *Generic record-service for all listings*: record-service's flat JSONB + generic PATCH
  does not support section-level validation or nested array management. Extending it to do
  so would couple platform-core to domain concerns.

**Trade-offs**

- listing-service is a more complex service than a typical single-domain service.
  This complexity is contained: the dynamic routing and validation are schema-driven
  infrastructure code written once.
- If a listing type has highly unusual validation logic that cannot be expressed in the
  schema's declarative rules, it must be handled as a configurable `CUSTOM_VALIDATOR`
  hook — a registered Java bean keyed by a string the schema references. This is an
  escape hatch, not the default path.

---

## ADR-10: Dual-Index OpenSearch Strategy (Admin + Consumer)

**Status**: Accepted

**Context**

lagu-platform's search-service today only maintains a per-org index:
`platform-{orgId}-{listingType}`. A consumer browsing vendors needs to search *across all
vendor orgs* (e.g. "show me all wedding venues in Hyderabad with capacity ≥ 300"). There
is no cross-org index in the current platform, which is why vendor-management had to maintain
a shared MongoDB collection with a public-facing search endpoint.

**Decision**

Two parallel index families in OpenSearch:

| Index family | Name pattern | Who can query | Data | Staleness |
|---|---|---|---|---|
| Admin/vendor | `{orgId}-{listingType}` | Authenticated vendor/admin, scoped to their org | Full JSONB, all statuses | Near-realtime (on record UPDATED event) |
| Consumer | `public-{listingType}` | Public (no auth) | Snapshot data only, ACTIVE+PUBLISHED | Updated on SNAPSHOT_PUBLISHED event |

Write path:
1. listing-service saves a change → publishes `ListingEvent.UPDATED`
2. search-service always upserts into `{orgId}-{listingType}`
3. If the listing type is `publishable` (flag in schema-registry) AND there is an active
   `ListingSnapshot` → also upserts into `public-{listingType}` using snapshot data
   with `searchBoost = TierConfiguration.searchBoostFactor`

Suspension path:
1. Vendor suspended → listing-service unpublishes all snapshots → `ListingEvent.SNAPSHOT_UNPUBLISHED`
2. search-service deletes all docs for that org from `public-{listingType}`

Query routing in search-service:
- `POST /api/v1/search` (authenticated, `X-Org-Id` header present) → `{orgId}-{listingType}`
- `POST /api/v1/search/public` (no auth required) → `public-{listingType}`

Index mappings for nested arrays (`ARRAY_OF_OBJECTS` + `searchable: true` in schema) are
generated as OpenSearch `"type": "nested"` blocks. This enables queries like "venue with
at least one hall with capacity ≥ 200 and acAvailable = true".

**Rejected alternatives**

- *Single org-scoped index only*: Cannot serve cross-vendor consumer discovery.
- *MongoDB collection as consumer search store*: Adds a second datastore; operational
  overhead; MongoDB's text search is weaker than OpenSearch full-text + BM25.
- *PostgreSQL full-text for consumer search*: Works for basic text search but cannot
  deliver facet aggregations or geo-distance ranking at scale.

**Trade-offs**

- Consumer index reflects snapshot data, not live record data. A vendor editing an active
  listing will not see changes in consumer search until the admin publishes a new snapshot.
  This is intentional — consumers see the approved version.
- Dual writes increase the complexity of `SearchDocumentIndexer`. Covered by a clear
  event-type dispatch table (UPDATED → admin index; SNAPSHOT_PUBLISHED → consumer index).

---

## ADR-11: Schema Versioning with Record-Level schema_version

**Status**: Accepted

**Context**

In a no-code platform, admins can change listing type schemas at runtime: add fields, remove
optional fields, change validation rules, reorder sections. Records created under an old schema
version must remain valid; records created after a schema change must satisfy the new schema.

**Decision**

Every `listing` row carries `schema_version INTEGER`. The schema-registry maintains a version
counter per listing type, incremented on every publish.

Validation rules:
- On write: record is validated against the **current published schema version**.
- On read: record is returned as-is; the `schema_version` field tells the client which
  schema it was created under (useful for UI to know which fields are available).
- On form render: client fetches the schema for `schema_version` to know which fields exist.

Schema change policy enforced by schema-registry:
- **Safe changes** (no migration needed): add an optional field, relax validation (min→lower),
  change a label, reorder sections.
- **Soft-breaking changes** (warning, one-click migrate): remove an optional field,
  make an optional field required, rename a field key.
- **Hard-breaking changes** (blocked, explicit migration required): change a field type
  (TEXT→NUMBER), remove a required field.

Migration tooling in schema-registry:
- `POST /schema-migrations` — define a JSONata transformation script.
- `POST /schema-migrations/{id}/dry-run` — applies to 100 random records, returns diff.
- `POST /schema-migrations/{id}/run` — batch job (500 records/batch), bumps `schema_version`.

**Trade-offs**

- Storing `schema_version` per record adds a join (or lookup) to get the right schema for
  history views. Acceptable — history views are low-frequency admin operations.
- The JSONata migration scripts must be stored, versioned, and audited in schema-registry.
  This is additional data management overhead but necessary for reproducibility.

---

## ADR-12: Tier Eligibility Rules as First-Class Platform Concept

**Status**: Accepted

**Context**

vendor-management has a `verification_tier_rules` table that enforces eligibility conditions
before an admin can upgrade a vendor's verification tier (e.g. ENHANCED requires a verified
GST document + PAN document + at least 2 service types). lagu-platform's `RecordVerification`
allows admins to set any tier with no eligibility enforcement.

**Decision**

Add `TierEligibilityRule` to schema-registry (stored alongside `TierConfiguration`).
`RecordVerificationService.set()` evaluates all active rules for the target tier before
persisting. Rules are evaluated client-side (in schema-registry) at the time of the
`TierCheckRequest`:

```
TierEligibilityRule {
  id
  listingType     VARCHAR(50)       -- VENDOR, VENUE, etc.
  tier            VARCHAR(20)       -- BASIC, ENHANCED, PREMIUM
  ruleType        ENUM              -- DOCUMENT_VERIFIED, FIELD_CONDITION, MIN_BOOKINGS
  documentCode    VARCHAR(50)       -- nullable; for DOCUMENT_VERIFIED
  fieldPath       VARCHAR(200)      -- nullable; for FIELD_CONDITION (e.g. taxInfo.gstStatus)
  operator        VARCHAR(10)       -- EQ, NEQ, GTE, LTE, IN, NOT_NULL
  value           VARCHAR(200)      -- nullable; for FIELD_CONDITION
  minCount        INTEGER           -- for MIN_BOOKINGS
  forceOverride   BOOLEAN           -- admin can bypass with explicit flag
  displayName     VARCHAR(200) NOT NULL
  description     VARCHAR(500)
  displayOrder    INTEGER
  active          BOOLEAN DEFAULT true
}
```

The admin can manage these rules from the schema-registry UI under "Tier Rules" for each
listing type. `GET /api/v1/tier-check/{recordId}?targetTier=ENHANCED` returns
`{ eligible: boolean, checks: [{ rule, satisfied, hint }] }` so the UI can show vendors
what they need before requesting an upgrade.

**Rejected alternatives**

- *Hardcode tier rules in vendor-service code*: Not maintainable; cannot be configured
  from UI; requires deploy to add a new rule.
- *Skip eligibility enforcement entirely*: Places all burden on admin discretion; creates
  risk of incorrect tier assignment and inconsistent vendor experience.

---

## ADR-13: Thin Vendor-Service Delegates Lifecycle to Workflow-Engine

**Status**: Accepted

**Context**

vendor-management's `VendorLifecycleService` has a hardcoded status FSM:
PENDING → PENDING_REVIEW → ACTIVE/REJECTED; ACTIVE → SUSPENDED. The 7-item submit
prerequisite check is also hardcoded. lagu-platform's vendor-service (stub) similarly
keeps a hardcoded `validateStatusTransition` method rather than delegating to workflow-engine.

**Decision**

vendor-service contains no FSM logic. It is a thin facade:
1. `POST /vendors/{id}/submit` → calls `POST /records/{id}/status` with trigger `SUBMIT`
   on record-service, which dispatches to workflow-engine.
2. Workflow-engine evaluates the transition's `conditions` block to enforce prerequisites.
3. Prerequisites that require cross-service data (e.g. "has at least one verified document")
   are pre-computed as fields on the VENDOR record JSONB and kept up-to-date by automation-engine
   reacting to `DocumentEvent.DOCUMENT_VERIFIED` → `UPDATE_FIELD kycDocumentsReady=true`.
4. `GET /vendors/{id}/readiness` is implemented in vendor-service as a computed summary
   by reading the current VENDOR record JSONB fields + calling document-service submission-status.
   It does not gate the FSM; it only informs the UI.

**Trade-offs**

- Prerequisite fields in the VENDOR record JSONB (e.g. `kycDocumentsReady`) are eventually
  consistent — they are updated asynchronously when document-service fires events. A document
  getting verified and the vendor submitting in the same second could transiently show
  `kycDocumentsReady: false`. Acceptable — the submit transition guard re-evaluates the
  condition at transition time; worst case the vendor retries seconds later.
- The automation rule that sets `kycDocumentsReady` must be seeded as a platform-level
  trigger in schema-registry (not org-specific).

---

## Risks (New)

### R-07: Schema Registry becomes a SPOF

schema-registry is now a dependency for every listing write (for validation) and for
listing-service startup (to build the route table). If it is unavailable:

**Mitigation**:
- All consumers (listing-service, document-service, workflow-engine) cache schemas in Redis
  (TTL 5 min). Writes continue against cached schema.
- schema-registry should run 2+ replicas (stateless read path; only writes mutate the DB).
- Startup dependency: listing-service fetches schemas at startup and fails fast if schema-registry
  is unreachable. In K8s, an init container can wait for schema-registry health before
  listing-service starts.

---

### R-08: Promoted Column Drift

If a field declared `promoted: true` in the schema is renamed or removed, existing `listing`
rows have a stale value in the promoted column (e.g. `city` column holds "Mumbai" but the
field is now called `primaryCity` in the JSONB).

**Mitigation**:
- Schema-registry prevents renaming a `promoted: true` field without a migration.
- listing-service repopulates promoted columns during schema migration batch job.
- Add an integrity check job: `SELECT id FROM listing WHERE city != (data->>'city')` — alerts
  on drift; runs daily.

---

### R-09: OpenSearch Nested Queries are Expensive

`"type": "nested"` in OpenSearch is powerful but has a per-document performance cost: each
nested document is an internal Lucene document. A venue with 20 halls has 21 Lucene documents.
At scale (millions of venues), nested queries on `halls.capacity` are slower than flat field queries.

**Mitigation**:
- Limit `ARRAY_OF_OBJECTS` + `searchable: true` to arrays that are genuinely needed as
  search facets (e.g. halls). Non-searchable nested arrays (e.g. package inclusions text)
  are stored as JSONB only, not indexed in OpenSearch.
- schema-registry enforces: `searchable: true` on an `ARRAY_OF_OBJECTS` field shows a
  warning to the admin ("This enables nested OpenSearch indexing and has a performance cost").
- The promoted column for `guest_capacity` (the max hall capacity) eliminates the most
  common nested query ("capacity >= N") since the promoted column handles it.

---

## Open Questions (carry-over + new)

**From prior sessions (11-decisions-and-risks.md):**
- Pricing engine (ENTITY_REFERENCE vs embedded JSONB) — recommend embedded JSONB per listing type

**New (2026-07-01):**

1. **ENHANCED tier**: vendor-management uses 4 tiers (NONE/BASIC/ENHANCED/PREMIUM).
   lagu-platform seeds 3. Should ENHANCED be added to TierConfiguration seeds, or collapsed
   into PREMIUM?

2. **Vendor team roles**: vendor-management has 7 roles (OWNER, MANAGER, SERVICE_MANAGER,
   BOOKING_MANAGER, SUPPORT, FINANCE, VIEWER). Platform seeds 3 org roles. Decision needed:
   add all 7 as RoleDefinition rows in seeds, or define a subset?

3. **Booking-service scope for Phase 1**: Full booking lifecycle (inquiry → quote → confirm →
   complete) or just availability blocking + inquiry? Recommend starting with availability +
   inquiry only — full booking engine is a separate phase.

4. **Admin console tech**: Schema builder UI requires a drag-and-drop form designer.
   Build custom in Next.js or use a library (e.g. react-beautiful-dnd + react-hook-form)?
   Recommend react-hook-form + custom drag-and-drop — schema builder is core product UI
   and needs tight control over UX.
