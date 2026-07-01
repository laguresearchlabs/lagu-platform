# No-Code Vendor Platform — Implementation TODO

> Decisions: see `13-no-code-vendor-platform-adr.md`
> Prerequisite: Phases 1–5 in `03–08-phase*.md` should be complete or running.
> Date authored: 2026-07-01

---

## Phase 6 — Schema Registry Service

> Replaces and absorbs `metadata-service`. All other services consume configuration from here.
> This is the no-code brain. Build this before touching listing-service.

### 6.1 Schema Registry Service Setup

- [ ] Create `apps/schema-registry` module in lagu-platform (Gradle subproject)
- [ ] Schema: `schema_registry` (PostgreSQL, separate from `metadata`)
- [ ] Copy + migrate all existing metadata-service tables:
  - `attribute_definition` → `field_definition` (rename + add new columns)
  - `object_type_definition` → `listing_type_definition`
  - `object_type_section` → `listing_type_section`
  - `relationship_definition` → keep as-is
  - `category_definition` → keep as-is
  - `document_type_definition` → merge into `document_requirement` (add `required_for_tiers` column)
  - `tier_configuration` → keep as-is
  - `country_validation_config` → keep as-is
- [ ] Decommission `metadata-service` app (keep lib imports compiling via schema-registry client)

### 6.2 New Tables

- [ ] `schema_version`: `(id, listing_type, version INT, published_at, published_by, change_summary)`
- [ ] `tier_eligibility_rule`: fields per ADR-12
  - Seed: VENDOR/BASIC requires PAN_CARD verified; ENHANCED requires GST_CERTIFICATE + PAN_CARD verified
- [ ] `search_definition`: `(id, listing_type, consumer_facets JSONB, admin_facets JSONB, default_sort, boost_field)`
- [ ] `automation_trigger_template`: platform-level trigger templates seeded per listing type
  - Seed: `vendor_approved_notify`, `vendor_rejected_notify`, `document_verified_notify`

### 6.3 FieldDefinition Enhancements

- [ ] Add `searchConfig` sub-object to `FieldDefinition`:
  ```
  filterable      BOOLEAN
  facetable       BOOLEAN
  sortable        BOOLEAN
  promoted        BOOLEAN   -- extracted to listing table promoted column
  rangeFilter     BOOLEAN   -- renders as range slider in consumer search
  ```
- [ ] Add `type: ARRAY_OF_OBJECTS` to `FieldType` enum
- [ ] Add `itemSchema JSONB` column on `field_definition` for nested array item field definitions
- [ ] Add `arrayManageable BOOLEAN` — generates add/update/delete sub-entity endpoints in listing-service

### 6.4 Schema Versioning

- [ ] Implement `SchemaVersionService.publish(listingType)`:
  - Increments version counter
  - Snapshots full schema as JSONB in `schema_version` row
  - Publishes `SchemaPublishedEvent` to Kafka (`platform.schema.events` topic)
- [ ] Add `GET /api/v1/schemas/{listingType}/version/{version}` — retrieve historical schema snapshot
- [ ] Change classification on publish: SAFE / SOFT_BREAKING (warning) / HARD_BREAKING (blocked)
- [ ] Schema migration API:
  - [ ] `POST /api/v1/schema-migrations` — create migration (JSONata script + target version)
  - [ ] `POST /api/v1/schema-migrations/{id}/dry-run` — apply to 100 sample records, return diff
  - [ ] `POST /api/v1/schema-migrations/{id}/run` — async batch job, 500 records/batch

### 6.5 Tier Eligibility API

- [ ] `GET /api/v1/tier-check/{recordId}?targetTier=ENHANCED` — returns `{ eligible, checks[] }`
- [ ] `GET /api/v1/tier-rules?listingType=VENDOR` — list active rules
- [ ] `POST /api/v1/tier-rules` — admin create rule
- [ ] `PATCH /api/v1/tier-rules/{id}/active` — enable/disable

### 6.6 Schema Registry Admin UI (Next.js / Admin Console)

- [ ] **Form Builder**: drag-and-drop section + field editor
  - Field types: TEXT, LONG_TEXT, NUMBER, DECIMAL, BOOLEAN, DATE, ENUM, MULTI_SELECT, ARRAY_OF_OBJECTS, FILE, IMAGE
  - Per-field: label, key, required, placeholder, validation (min/max/pattern), searchConfig
  - Nested array editor: define item schema inline
  - Preview panel: renders the form as it will appear to vendors
- [ ] **Workflow Designer**: visual state machine editor
  - Add/rename/delete states, draw transitions
  - Per transition: allowed roles (multi-select), guard conditions (key/op/value rows), require approval toggle, approval definition picker
- [ ] **Document Configurator**: table editor — document type, label, required-for-tiers checkboxes, expiry-tracked toggle
- [ ] **Tier Rules Builder**: per tier, list of eligibility rule rows — ruleType, field/document, operator, value
- [ ] **Search Config**: select which fields are consumer facets, admin facets, default sort field
- [ ] **Publish button**: shows change classification (SAFE / WARNING / BLOCKED), confirmation dialog, triggers publish

### 6.7 Schema Registry Kafka Events

- [ ] Add `platform.schema.events` topic to `libs/events` PlatformTopics
- [ ] `SchemaPublishedEvent`: `{ listingType, version, publishedAt, changeClassification }`
- [ ] Add `SchemaPublishedConsumer` to listing-service, document-service, search-service, workflow-engine

---

## Phase 7 — Listing Service (Full Implementation)

> Replaces venue-service, listing-service stub, and admin-service from vendor-management.
> One service, all listing types, schema-driven.

### 7.1 Data Model

- [ ] Create `apps/listing-service` (new, replaces stub)
- [ ] Schema: `listings` (PostgreSQL)
- [ ] `listing` table per ADR-08:
  - Promoted columns: `city`, `state`, `starting_price`, `guest_capacity`, `listing_name`
  - Additional promoted columns declared in schema-registry at `promoted: true`
  - `data JSONB`, `schema_version INTEGER`, `version BIGINT` (optimistic lock)
- [ ] `listing_snapshot`: `(id, listing_id, org_id, listing_type, data JSONB, status, verification_tier, search_boost, published_at, version)`
- [ ] `listing_availability`: `(id, listing_id, org_id, slot_date DATE, slot_type, booking_ref UUID, note)` UNIQUE(listing_id, slot_date)
- [ ] Flyway migrations

### 7.2 Schema-Driven Section Routing

- [ ] `SchemaRouterInitializer`: on startup + on `SchemaPublishedEvent`, fetch all published schemas from schema-registry
- [ ] For each section in schema, register:
  - `GET  /api/v1/listings/{id}/sections/{sectionKey}`
  - `PATCH /api/v1/listings/{id}/sections/{sectionKey}`
- [ ] For each `ARRAY_OF_OBJECTS` field with `arrayManageable: true`:
  - `POST   /api/v1/listings/{id}/sections/{sectionKey}/{fieldKey}`
  - `PATCH  /api/v1/listings/{id}/sections/{sectionKey}/{fieldKey}/{itemId}`
  - `DELETE /api/v1/listings/{id}/sections/{sectionKey}/{fieldKey}/{itemId}`
- [ ] `SectionValidator`: validates request body against section's FieldDefinitions from schema; cross-field rules via `CUSTOM_VALIDATOR` hook registry

### 7.3 Core Listing CRUD

- [ ] `POST /api/v1/listings` — create DRAFT; body: `{ listingType, orgId, basics: { listingName } }`
- [ ] `GET  /api/v1/listings/{id}` — full listing (authenticated, org-scoped)
- [ ] `GET  /api/v1/listings/{id}/public` — public snapshot view (no auth)
- [ ] `DELETE /api/v1/listings/{id}` — blocked for ACTIVE listings
- [ ] `POST /api/v1/listings/{id}/submit` — trigger SUBMIT transition via record-service → workflow-engine
- [ ] `GET  /api/v1/listings/{id}/readiness` — checklist: which sections are complete, which sections are missing required fields

### 7.4 Promoted Column Sync

- [ ] `PromotedColumnExtractor`: reads `SearchDefinition.promoted` fields from schema-registry (cached)
- [ ] On every listing write, extract promoted fields from JSONB and write to promoted columns
- [ ] Schema migration job also backfills promoted columns

### 7.5 Availability Management

- [ ] `PUT /api/v1/listings/{id}/availability` — set availability for a date range (AVAILABLE/BLOCKED/BOOKED)
- [ ] `GET /api/v1/listings/{id}/availability?from=&to=` — get slot calendar
- [ ] `POST /api/v1/listings/{id}/availability/book` — internal endpoint called by booking-service (sets BOOKED + bookingRef)

### 7.6 Snapshot Management

- [ ] `ListingSnapshotService.publishSnapshot(listingId)` — only for ACTIVE listings; copies current JSONB as snapshot; sets verification_tier from RecordVerification; sets search_boost from TierConfiguration
- [ ] `POST /api/v1/listings/{id}/publish` — admin manual publish
- [ ] `POST /api/v1/listings/{id}/unpublish` — admin / vendor (e.g. seasonal closure)
- [ ] `WorkflowEventConsumer`: auto-publish on transition to active states; auto-unpublish on suspension

### 7.7 Vendor-Scoped Listing List

- [ ] `GET /api/v1/listings/my` — authenticated vendor; lists all listings for `X-Org-Id`; cursor-paginated; filter by `listingType`, `status`
- [ ] `GET /api/v1/listings/admin` — admin; cross-org; filter by `listingType`, `orgId`, `status`, `verificationTier`

---

## Phase 8 — Search Service: Dual-Index

> Extends existing search-service. Does not break existing per-org admin search.

### 8.1 Consumer Index

- [ ] Add `consumer-{listingType}` index naming convention alongside `platform-{orgId}-{listingType}`
- [ ] `SearchDocumentIndexer`:
  - On `ListingEvent.UPDATED` → upsert `platform-{orgId}-{listingType}` (existing)
  - On `ListingEvent.SNAPSHOT_PUBLISHED` → upsert `consumer-{listingType}` with snapshot data + searchBoost
  - On `ListingEvent.SNAPSHOT_UNPUBLISHED` → delete from `consumer-{listingType}`
- [ ] `POST /api/v1/search/public` — no auth; routes to `consumer-{listingType}`; applies searchBoost in scoring
- [ ] Add `ListingEvent` types to `libs/events`:
  - `SNAPSHOT_PUBLISHED`, `SNAPSHOT_UNPUBLISHED`, `AVAILABILITY_UPDATED`

### 8.2 Nested Type Support

- [ ] `IndexMappingBuilder`: on `SchemaPublishedEvent`, rebuild index mapping
- [ ] For `ARRAY_OF_OBJECTS` + `searchable: true` fields → emit `"type": "nested"` OpenSearch mapping
- [ ] `SearchService.buildQuery`: detect nested field paths (contain `.`) → wrap in `nested` query clause
- [ ] Consumer search filters support nested fields:
  ```json
  "filters": {
    "capacity.halls.capacity": { "gte": 200 },
    "capacity.halls.acAvailable": true
  }
  ```

### 8.3 Boost-Based Ranking

- [ ] Consumer index documents carry `_searchBoost` field
- [ ] `SearchService` applies function_score with `field_value_factor` on `_searchBoost` in consumer queries
- [ ] Boost value sourced from `TierConfiguration.searchBoostFactor` at snapshot publish time

### 8.4 Search Configuration from Schema Registry

- [ ] `SearchService` reads `SearchDefinition.consumerFacets` for facet aggregation fields
- [ ] `SearchService` reads `SearchDefinition.adminFacets` for admin search facets
- [ ] `SearchService` reads `SearchDefinition.defaultSort` for default ordering

---

## Phase 9 — Vendor Service Evolution

> Wires existing vendor-service stub to the now-complete platform. Removes all hardcoded FSM logic.

### 9.1 Wire FSM to Workflow-Engine

- [ ] Remove `VendorService.validateStatusTransition()` hardcoded switch statement
- [ ] `POST /vendors/{id}/submit` → delegates to `POST /records/{id}/status { trigger: "SUBMIT" }`
- [ ] `POST /vendors/{id}/withdraw` → delegates to `POST /records/{id}/status { trigger: "WITHDRAW" }`
- [ ] `PATCH /vendors/{orgId}/status` (admin) → delegates to `POST /records/{id}/status { trigger: "APPROVE" / "REJECT" / "SUSPEND" }`
- [ ] Seed VENDOR WorkflowDefinition in schema-registry:
  - States: DRAFT, SUBMITTED, UNDER_REVIEW, ACTIVE, REJECTED, SUSPENDED
  - Transitions: SUBMIT (DRAFT→SUBMITTED), WITHDRAW (SUBMITTED→DRAFT), START_REVIEW (SUBMITTED→UNDER_REVIEW), APPROVE (UNDER_REVIEW→ACTIVE), REJECT (UNDER_REVIEW→REJECTED), SUSPEND (ACTIVE→SUSPENDED), REACTIVATE (SUSPENDED→ACTIVE), RESUBMIT (REJECTED→DRAFT)

### 9.2 KYC Readiness via Automation

- [ ] Seed automation trigger in schema-registry: on `DocumentEvent.DOCUMENT_VERIFIED` where `objectType=VENDOR`
  - Action: `UPDATE_FIELD` on vendor record — recompute `kycDocumentsReady` boolean
- [ ] `GET /vendors/{id}/readiness` — reads VENDOR record JSONB fields + calls document-service submission-status; assembles checklist response
- [ ] Add transition guard conditions on SUBMIT transition:
  - `data.kycDocumentsReady = true`
  - `data.hasRegisteredOfficeAddress = true`
  - `data.hasPhoneNumber = true`
  - `data.hasBankInfo = true`

### 9.3 Tier Eligibility Integration

- [ ] `GET /vendors/{id}/tier-check?targetTier=ENHANCED` → proxies to schema-registry `/tier-check/{recordId}?targetTier=ENHANCED`
- [ ] `PATCH /vendors/{orgId}/verification/tier` → calls `RecordVerificationService.set()` which now runs eligibility check via schema-registry; supports `forceOverride` flag
- [ ] Add `expiry_days` column to `TierConfiguration`; seed: BASIC=0, ENHANCED=365, PREMIUM=180
- [ ] `RecordVerificationService.set()` computes `expires_at = now() + tierConfig.expiryDays` if > 0
- [ ] Seed automation trigger: nightly `EXPIRE_VERIFICATION` action for expired tier records

### 9.4 ChangeSet Enhancements

- [ ] Add `section VARCHAR(50)` column to `workflow.change_set`
- [ ] `POST /change-sets` accepts `?section=BANK_INFO` query param; vendor-service passes section on profile PATCH
- [ ] Add `PUT /change-sets/{id}` endpoint: vendor edits proposed_data while status = PENDING
- [ ] Add `POST /change-sets/bulk-review` endpoint: `{ actions: [{id, decision, comment}] }`

### 9.5 Vendor Team Roles

- [ ] Seed additional RoleDefinition rows: SERVICE_MANAGER, BOOKING_MANAGER, SUPPORT, FINANCE, VIEWER
- [ ] Seed PermissionDefinition rows mapping each role to appropriate resource+action pairs:
  - SERVICE_MANAGER: LISTING:CREATE, LISTING:UPDATE, LISTING:READ
  - BOOKING_MANAGER: BOOKING:READ, BOOKING:UPDATE, AVAILABILITY:UPDATE
  - FINANCE: PAYOUT:READ, COMMISSION:READ
  - VIEWER: LISTING:READ, BOOKING:READ (read-only)

### 9.6 Document Number Support

- [ ] Add `document_number VARCHAR(100)` column to `documents.document`
- [ ] Update document upload endpoint to accept optional `documentNumber` param
- [ ] Seed DocumentTypeDefinition rows (via schema-registry) for vendor documents:
  PAN_CARD, GST_CERTIFICATE, AADHAAR_CARD, CANCELLED_CHEQUE, TRADE_LICENCE,
  UDYAM_CERTIFICATE, INCORPORATION_CERTIFICATE, FSSAI_LICENCE, DRIVING_LICENCE, PASSPORT

---

## Phase 10 — Booking Service

> First version: availability blocking + inquiry flow only.
> Full quote→confirm→complete in a later iteration.

### 10.1 Data Model

- [ ] Create `apps/booking-service` (implement the existing stub)
- [ ] Schema: `bookings`
- [ ] `inquiry`: `(id, listing_id, org_id[vendor], consumer_user_id, event_date DATE, event_type, guest_count, message, status[NEW/VIEWED/RESPONDED/CLOSED], created_at)`
- [ ] `booking`: `(id, inquiry_id, listing_id, vendor_org_id, consumer_user_id, event_date DATE, slot_type, status[CONFIRMED/CANCELLED/COMPLETED], advance_amount, total_amount, commission_rate, commission_amount, confirmed_at, completed_at)`

### 10.2 Inquiry Flow

- [ ] `POST /api/v1/inquiries` — consumer sends inquiry; availability check against listing-service
- [ ] `GET  /api/v1/inquiries/{id}` — both consumer and vendor can view
- [ ] `GET  /api/v1/inquiries/my` — consumer: their inquiries; vendor: incoming inquiries for their listings
- [ ] `POST /api/v1/inquiries/{id}/respond` — vendor responds (marks RESPONDED, message)
- [ ] `POST /api/v1/inquiries/{id}/close` — either party closes

### 10.3 Booking Confirmation

- [ ] `POST /api/v1/bookings` — from confirmed inquiry; calls listing-service to block slot
- [ ] Commission calculation: `booking.commissionRate` sourced from `TierConfiguration.commissionRate` for vendor's current tier
- [ ] `POST /api/v1/bookings/{id}/complete` — marks COMPLETED; triggers `BookingCompletedEvent`
- [ ] `POST /api/v1/bookings/{id}/cancel` — marks CANCELLED; releases slot via listing-service

### 10.4 Events

- [ ] Add to `libs/events`:
  - `BookingEvent`: `INQUIRY_CREATED`, `BOOKING_CONFIRMED`, `BOOKING_COMPLETED`, `BOOKING_CANCELLED`
- [ ] Automation trigger templates: `booking_confirmed_notify`, `booking_completed_notify`

---

## Platform Extensions Checklist

> Cross-cutting tasks that touch existing platform services.

### workflow-engine

- [ ] ChangeSet `section` field (Phase 9.4)
- [ ] `PUT /change-sets/{id}` edit endpoint (Phase 9.4)
- [ ] `POST /change-sets/bulk-review` (Phase 9.4)
- [ ] Consume `SchemaPublishedEvent` to upsert WorkflowDefinition rows from schema-registry

### document-service

- [ ] `document_number VARCHAR(100)` column (Phase 9.6)
- [ ] Load document types from schema-registry instead of metadata-service

### search-service

- [ ] Dual-index (Phase 8.1)
- [ ] Nested type support (Phase 8.2)
- [ ] Public search endpoint (Phase 8.1)
- [ ] Consume `SchemaPublishedEvent` to rebuild index mappings

### automation-engine

- [ ] Consume `SchemaPublishedEvent` to seed platform-level trigger templates
- [ ] `EXPIRE_VERIFICATION` and `REVOKE_VERIFICATION` action types — verify these exist (they are in current code; confirm wiring)

### libs/events

- [ ] Add `platform.schema.events` topic constant
- [ ] Add `SchemaPublishedEvent`
- [ ] Add `ListingEvent` types: SNAPSHOT_PUBLISHED, SNAPSHOT_UNPUBLISHED, AVAILABILITY_UPDATED
- [ ] Add `BookingEvent` types

### libs/security

- [ ] Add `VENDOR_SERVICE_MANAGER`, `VENDOR_BOOKING_MANAGER`, `VENDOR_FINANCE`, `VENDOR_VIEWER` role constants
- [ ] `PlatformSecurityContext.isVendorRole()` helper

---

## Admin Console UI Pages

> These need to exist before the no-code platform is usable end-to-end.

- [ ] **Schema Registry** — listing types list → click type → tab: Form Builder | Workflow | Documents | Tier Rules | Search Config | Publish
- [ ] **Vendor Management** — vendor list with tier/status filters; vendor detail with KYC checklist; tier upgrade modal with eligibility check results
- [ ] **Change Request Queue** — pending change requests across all vendors; diff view (proposed vs current); approve/reject/bulk actions
- [ ] **Search Preview** — test consumer search queries against the public-{listingType} index with a live UI
- [ ] **Tier Configuration** — table: tier × listingType → commission rate, max bookings, search boost, SLA hours, expiry days
- [ ] **Booking Dashboard** — inquiry list, booking list, cancellation rate, commission summary

---

## Migration from vendor-management

> Only relevant if existing vendor-management data needs to be carried over.

- [ ] **Vendor data**: map `vendors` table → VENDOR record JSONB per schema-registry field keys
- [ ] **VendorDocument** → document-service rows (map DocumentType enum values to new DocumentTypeDefinition codes)
- [ ] **VendorAddress** → JSONB fields under `address` section in VENDOR record
- [ ] **VendorUser** → RoleDefinition assignments in metadata-service
- [ ] **venue (MongoDB)** → listing table JSONB; map each section to schema-registry field keys; set `schema_version = 1`
- [ ] **vendor_change_requests** → workflow ChangeSet rows (map section enum to section string)
- [ ] **verification_tier_rules** → TierEligibilityRule rows in schema-registry
- [ ] **service_types** → CategoryDefinition rows in schema-registry
- [ ] Run integrity check after migration: validate every listing row against its schema_version

---

## Phase Dependency Order

```
Phase 6 (Schema Registry)
    │
    ├──▶ Phase 7 (Listing Service)
    │         │
    │         └──▶ Phase 8 (Dual-Index Search)
    │
    └──▶ Phase 9 (Vendor Service Evolution)
              │
              └──▶ Phase 10 (Booking Service)
```

Phases 7 and 9 can be developed in parallel once Phase 6 is done.
Phase 8 can be started in parallel with Phase 7 once schema-registry's `SchemaPublishedEvent` contract is finalised.
