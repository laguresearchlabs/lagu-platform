# Lagu Platform — Complete Application Lifecycle

End-to-end walkthrough of the platform's request/response flows, from an admin defining a
listing schema to a logged-out consumer searching published listings. Every example reflects
the code as built (verified against `PlatformEndToEndIT` and the service sources).

All requests below go **through gateway-service** (`/api/v1/...`), which validates the JWT and
injects trusted identity headers downstream. Clients never set identity headers themselves.

---

## 1. Conventions

### 1.1 Response envelope

Every platform endpoint wraps its payload:

```json
{ "success": true,  "data": { ... } }
{ "success": false, "error": { "code": "RESOURCE_NOT_FOUND", "message": "...", "details": [ ... ] } }
```

Paginated endpoints return a `PageResult` inside `data`:

```json
{ "content": [ ... ], "page": 0, "size": 20, "total": 42, "totalPages": 3 }
```

### 1.2 Identity & roles

| Header (gateway-injected)   | Meaning                                                        |
|-----------------------------|----------------------------------------------------------------|
| `X-User-Id`                 | Caller UUID from the JWT                                       |
| `X-Org-Id`                  | Caller's organization (vendors: their own org)                 |
| `X-User-Roles`              | Mapped roles: `ADMIN` → `PLATFORM_ADMIN,CONFIG_ADMIN`; `VENDOR` → `ORG_OWNER,ORG_MANAGER,ORG_MEMBER`; `USER` → `ORG_MEMBER` |
| `X-Platform-Gateway-Secret` | Proves the request transited the gateway (services fail closed without it) |

Internal service-to-service calls authenticate as `SVC_<NAME>` principals via
`X-Internal-Service` + the shared secret; they never carry admin roles.

### 1.3 Common error codes

| HTTP | code                      | When                                                        |
|------|---------------------------|-------------------------------------------------------------|
| 400  | `VALIDATION_FAILED` / `VALIDATION_ERROR` | Bean validation / schema validation failure  |
| 401  | —                         | No/invalid identity (or missing gateway secret)             |
| 403  | `ORG_CONTEXT_REQUIRED`    | Authenticated but no org — org-scoped data denied           |
| 403  | `APPROVAL_ROLE_REQUIRED`  | Deciding an approval step without that step's role          |
| 403  | `SELF_APPROVAL_FORBIDDEN` | Requester tried to approve their own transition             |
| 404  | `RESOURCE_NOT_FOUND`      | Missing — also returned for cross-org access (no existence leak) |
| 409  | `CONCURRENT_MODIFICATION` | Optimistic-lock conflict — reload and retry                 |

---

## 2. Lifecycle at a glance

```
 ADMIN                    VENDOR                      PLATFORM                    CONSUMER
   │                         │                            │                          │
   │ 1. define & publish     │                            │                          │
   │    schema (VENUE, ...)  │                            │                          │
   │────────────────────────►│                            │                          │
   │                         │ 2. register vendor org     │                          │
   │                         │ 3. upload KYC documents    │                          │
   │ 4. review documents     │                            │                          │
   │ 5. set verification tier│                            │                          │
   │                         │ 6. create listing record   │                          │
   │                         │ 7. trigger "submit" ───────► workflow engine          │
   │ 8. start_review/approve/publish ────────────────────► state machine + approvals│
   │                         │                            │ 9. snapshot published    │
   │                         │                            │    → consumer index      │
   │                         │                            │                          │ 10. public
   │                         │                            │                          │ search
```

Statuses of a listing record (seeded vendor-review workflow):
`DRAFT → SUBMITTED → UNDER_REVIEW → APPROVED → PUBLISHED → (SUSPENDED ⇄ PUBLISHED) → ARCHIVED`,
with `REJECTED → SUBMITTED` via `resubmit` and `SUBMITTED → DRAFT` via `withdraw`.

---

## 3. Phase 0 — Admin defines the listing schema (schema-registry)

Roles: `CONFIG_ADMIN` or `PLATFORM_ADMIN`. The schema drives record validation
(record-service), index mappings (search-service), and form rendering (frontends).

### 3.1 Create a field

```http
POST /api/v1/fields
Content-Type: application/json

{
  "name": "name", "label": "Name", "fieldType": "TEXT",
  "required": true, "unique": false,
  "searchable": true, "filterable": true, "sortable": false,
  "facetable": false, "promoted": false,
  "rangeFilterable": false, "arrayManageable": false
}
```

```json
{ "success": true, "data": { "name": "name", "label": "Name", "fieldType": "TEXT", "required": true, ... } }
```

Field types: `TEXT, LONG_TEXT, NUMBER, DECIMAL, CURRENCY, BOOLEAN, DATE, DATETIME, ENUM,
PHONE, EMAIL, URL, GEOLOCATION, ...` (see `FieldType`).

### 3.2 Group fields and assemble a listing type

```http
POST /api/v1/field-groups
{ "name": "basic-info", "label": "Basic Info",
  "entries": [ { "fieldName": "name", "displayOrder": 0, "required": true } ] }
```

```http
POST /api/v1/listing-types
{ "name": "VENUE", "label": "Venue", "publishable": true, "consumerSearchable": true,
  "sections": [ { "fieldGroupName": "basic-info", "label": "Basic Info",
                  "sectionKey": "basic", "displayOrder": 0, "collapsible": false } ] }
```

### 3.3 Publish the schema version

```http
POST /api/v1/listing-types/VENUE/publish
{ "changeSummary": "initial publish" }
```

```json
{ "success": true, "data": { "listingType": "VENUE", "version": 1, "changeClassification": "SAFE", ... } }
```

Side effect: a `SCHEMA_PUBLISHED` event is staged in `schema_outbox` (same transaction) and
relayed to Kafka topic `platform.schema.events`; record-service and search-service refresh
their cached schemas from it.

Consumers of the schema read it via `GET /api/v1/listing-types/VENUE/schema`.

---

## 4. Phase 1 — Vendor registration (vendor-service)

Any authenticated user self-registers as a vendor. This provisions a **new org** (vendor = own
org), creates the canonical `VENDOR` record in record-service (as `SVC_VENDOR_SERVICE`, acting
user = the caller), and associates the org with the user in IAM.

```http
POST /api/v1/vendors/register
Authorization: Bearer <jwt>
Content-Type: application/json

{ "businessName": "Grand Events Co", "country": "IN", "primaryVendorType": "VENUE" }
```

```json
{
  "success": true,
  "data": {
    "orgId":        "d2dd68ed-80bf-4ac9-a08d-170407692ca6",
    "recordId":     "5a3c9c1e-2f6b-4b1a-9df4-9a1d5f2f2c11",
    "businessName": "Grand Events Co",
    "status":       "DRAFT",
    "country":      "IN",
    "kycChecklist": null,
    "createdAt":    "2026-07-04T10:15:00Z",
    "updatedAt":    "2026-07-04T10:15:00Z"
  }
}
```

> After registration the user must refresh their JWT so it carries the new `orgId` claim —
> all org-scoped endpoints below depend on it.

Self-service endpoints (vendor's own org, derived from the token — no IDs in the path):

| Endpoint                    | Purpose                                    |
|-----------------------------|--------------------------------------------|
| `GET  /api/v1/vendors/me`   | Own profile + KYC checklist                |
| `GET  /api/v1/vendors/me/kyc` | Recompute + return KYC readiness         |
| `POST /api/v1/vendors/me/submit` | Submit profile for admin review (`DRAFT → SUBMITTED`) |

Admin endpoints (`CONFIG_ADMIN`/`PLATFORM_ADMIN`):

| Endpoint                                  | Purpose                              |
|-------------------------------------------|--------------------------------------|
| `GET   /api/v1/vendors?status=SUBMITTED`  | Review queue                         |
| `GET   /api/v1/vendors/{orgId}`           | One vendor                           |
| `PATCH /api/v1/vendors/{orgId}/status`    | `{ "status": "UNDER_REVIEW" }` etc.  |

Vendor profile status machine: `DRAFT → SUBMITTED → UNDER_REVIEW → ACTIVE | REJECTED`,
`ACTIVE ⇄ SUSPENDED`, `REJECTED → DRAFT`.

---

## 5. Phase 2 — KYC document upload & review (document-service)

Document types are loaded from schema-registry's catalog (`/api/v1/document-requirements/catalog`).
Vendor KYC uses: `GST_CERTIFICATE`, `PAN_CARD`, `BANK_CANCELLED_CHEQUE`, `IDENTITY_PROOF`
(sub-types: `AADHAAR | PASSPORT | DRIVING_LICENSE | VOTER_ID | PAN_CARD`).

### 5.1 Vendor uploads

```http
POST /api/v1/documents
Content-Type: multipart/form-data

file=<binary>  documentType=PAN_CARD  expiryDate=2030-01-15
```

```json
{
  "success": true,
  "data": {
    "id": "7be1...", "orgId": "d2dd...", "userId": "91c4...",
    "documentType": "PAN_CARD", "fileName": "pan.pdf", "mimeType": "application/pdf",
    "fileSizeBytes": 182034, "status": "UPLOADED", "expiryDate": "2030-01-15",
    "uploadedAt": "2026-07-04T10:20:00Z"
  }
}
```

### 5.2 Checklist / readiness

```http
GET /api/v1/documents/submission-status
```

```json
{
  "success": true,
  "data": {
    "documents": [
      { "documentType": "PAN_CARD", "label": "PAN Card", "required": false,
        "status": "VERIFIED", "documentId": "7be1...", "uploadedAt": "..." },
      { "documentType": "GST_CERTIFICATE", "label": "GST Registration Certificate",
        "required": false, "status": "MISSING" }
    ],
    "allRequiredSubmitted": true,
    "allRequiredVerified": false
  }
}
```

Statuses per document: `MISSING → UPLOADED → UNDER_REVIEW → VERIFIED | REJECTED | EXPIRED`.

### 5.3 Review (requires `DOCUMENT REVIEW` permission — `ORG_MANAGER`/`ORG_OWNER`)

| Endpoint                              | Effect                       |
|---------------------------------------|------------------------------|
| `GET  /api/v1/documents/pending-review` | Oldest-first review queue  |
| `POST /api/v1/documents/{id}/review`  | Claim → `UNDER_REVIEW`       |
| `POST /api/v1/documents/{id}/verify`  | → `VERIFIED`                 |
| `POST /api/v1/documents/{id}/reject`  | `{ "rejectionReason": "Blurry scan" }` → `REJECTED` |

KYC readiness (`GET /api/v1/vendors/me/kyc`) recomputes from verified documents:

```json
{ "success": true, "data": {
    "hasGstDoc": false, "hasPanDoc": true, "hasBankDoc": true, "hasIdentityDoc": true,
    "businessNameFilled": true, "kycReady": true } }
```

`kycReady` requires verified PAN + bank + identity proof and a business name (GST is
optional — it gates the higher tiers instead).

---

## 6. Phase 3 — Verification tier (record-service)

The tier ladder is configuration, seeded into schema-registry's `TierConfiguration`:

| Tier       | Commission | Max active bookings | Search boost | SLA (hrs) | Eligibility (seeded rules)             |
|------------|-----------:|--------------------:|-------------:|----------:|----------------------------------------|
| `NONE`     |        20% |                   3 |          1.0 |        72 | —                                       |
| `BASIC`    |        15% |                  10 |          1.5 |        48 | PAN + bank verified                     |
| `ENHANCED` |        12% |                  25 |          1.8 |        36 | PAN + bank + GST verified               |
| `PREMIUM`  |        10% |           unlimited |          2.0 |        24 | (manual / commercial)                   |

Eligibility check: `GET /api/v1/tier-checks/...` (schema-registry `TierCheckService`).

Setting a tier requires `RECORD_VERIFICATION MANAGE` (platform admin, or automation-service):

```http
PUT /api/v1/records/{recordId}/verification
{ "tier": "ENHANCED", "expiresAt": "2027-07-04T00:00:00Z", "notes": "GST verified 2026-07-04" }
```

```json
{ "success": true, "data": {
    "id": "c0ffee...", "recordId": "5a3c...", "tier": "ENHANCED", "status": "VERIFIED",
    "verifiedBy": "admin-uuid", "verifiedAt": "2026-07-04T11:00:00Z",
    "expiresAt": "2027-07-04T00:00:00Z", "notes": "GST verified 2026-07-04" } }
```

`tier` is validated against `NONE|BASIC|ENHANCED|PREMIUM`. Related:
`POST .../verification/revoke { "reason": "..." }`, `POST /api/v1/records/verification/expire-overdue`
(automation-service calls this on schedule; emits `EXPIRED`/`REVOKED` verification events).

---

## 7. Phase 4 — Listing creation & workflow (record-service + workflow-service)

### 7.1 Create the listing record

Requires `ORG_MANAGER`/`ORG_OWNER`. Data is validated against the **published** schema.
Records always start in `DRAFT` — a client-supplied status is rejected (workflow bypass).

```http
POST /api/v1/records
{ "objectType": "VENUE",
  "data": { "name": "Grand Hall", "capacity": 500, "city": "Chennai" } }
```

```json
{ "success": true, "data": {
    "id": "0be6...", "orgId": "d2dd...", "objectType": "VENUE", "status": "DRAFT",
    "data": { "name": "Grand Hall", "capacity": 500, "city": "Chennai" },
    "createdBy": "91c4...", "createdAt": "2026-07-04T11:05:00Z", "updatedAt": "...",
    "verificationTier": "ENHANCED", "verificationStatus": "VERIFIED" } }
```

Other record operations: `GET /api/v1/records?objectType=VENUE&status=DRAFT&page=0&size=20`
(org-scoped, paginated), `GET/PUT/PATCH/DELETE /api/v1/records/{id}`,
`GET /api/v1/records/{id}/history`. All are tenancy-enforced: callers see only their org's
records (`PLATFORM_ADMIN` excepted); concurrent updates return **409**.

### 7.2 Request a transition (asynchronous!)

```http
POST /api/v1/records/{id}/status
{ "trigger": "submit", "comment": "Ready for review" }
```

```json
{ "success": true, "data": { "id": "0be6...", "status": "DRAFT", ... } }
```

**The response still shows the old status.** The request publishes a
`STATUS_TRANSITION_REQUESTED` event (with the caller's roles and a guard context of
record data + verification tier) via the record outbox; workflow-service validates and applies
it, then record-service picks up the resulting `TRANSITIONED` event and updates `status`.
Poll 7.3 for the outcome (typically ≤ 2–3 s).

Seeded vendor-review triggers and who may fire them:

| From → To                   | trigger             | Allowed roles                  |
|-----------------------------|---------------------|--------------------------------|
| DRAFT → SUBMITTED           | `submit`            | ORG_OWNER, ORG_MANAGER         |
| SUBMITTED → DRAFT           | `withdraw`          | ORG_OWNER                      |
| SUBMITTED → UNDER_REVIEW    | `start_review`      | CONFIG_ADMIN, PLATFORM_ADMIN   |
| UNDER_REVIEW → APPROVED     | `approve`           | CONFIG_ADMIN, PLATFORM_ADMIN   |
| UNDER_REVIEW → REJECTED     | `reject`            | CONFIG_ADMIN, PLATFORM_ADMIN   |
| REJECTED → SUBMITTED        | `resubmit`          | ORG_OWNER, ORG_MANAGER         |
| APPROVED → PUBLISHED        | `publish`           | CONFIG_ADMIN, PLATFORM_ADMIN   |
| PUBLISHED → SUSPENDED       | `suspend`           | CONFIG_ADMIN, PLATFORM_ADMIN   |
| SUSPENDED → PUBLISHED       | `reinstate`         | CONFIG_ADMIN, PLATFORM_ADMIN   |
| PUBLISHED/SUSPENDED → ARCHIVED | `archive` / `archive_suspended` | CONFIG_ADMIN, PLATFORM_ADMIN |

Trigger matching is case-insensitive. A disallowed role or failed guard condition produces a
`TRANSITION_REJECTED` workflow event (the record's status simply never changes); guard
conditions that cannot be evaluated **fail closed**.

### 7.3 Poll workflow state / discover allowed actions

```http
GET /api/v1/records/{recordId}/workflow
```

```json
{ "success": true, "data": {
    "recordId": "0be6...", "currentState": "SUBMITTED", "objectType": "VENUE",
    "workflowId": "aa10...", "isTerminal": false,
    "allowedTransitions": [
      { "triggerName": "withdraw",     "triggerLabel": "Withdraw",     "toState": "DRAFT",        "requiresApproval": false },
      { "triggerName": "start_review", "triggerLabel": "Start Review", "toState": "UNDER_REVIEW", "requiresApproval": false }
    ],
    "updatedAt": "2026-07-04T11:06:02Z" } }
```

`allowedTransitions` is already filtered to the caller's roles — frontends render it directly
as action buttons. Transition audit: `GET /api/v1/records/{recordId}/workflow/history`.

### 7.4 Approvals (when a transition has `requiresApproval: true`)

Seeded transitions execute immediately; admin-configured workflows may attach an
`ApprovalDefinition` (`SEQUENTIAL | PARALLEL | ANY_ONE` with per-step approver roles).
Then the transition parks in a pending approval instead of executing:

```http
GET  /api/v1/approvals/pending            (my org, my roles; ?olderThanMinutes= for escalation)
POST /api/v1/approvals/{id}/decide
{ "decision": "APPROVED", "comment": "Verified against site visit" }
```

```json
{ "success": true, "data": {
    "id": "e901...", "recordId": "0be6...", "status": "PENDING",
    "currentStep": 2, "totalSteps": 2, "approvalType": "SEQUENTIAL",
    "currentApproverRole": "ORG_OWNER",
    "decisions": [ { "stepOrder": 1, "approverUserId": "91c4...", "decision": "APPROVED",
                     "comment": "...", "decidedAt": "..." } ],
    "createdAt": "...", "completedAt": null } }
```

Rules enforced at decision time: the deciding user must hold the step's role
(403 `APPROVAL_ROLE_REQUIRED`); one decision per user per instance; the transition's
requester may not approve it (403 `SELF_APPROVAL_FORBIDDEN`); `PARALLEL` completes only when
**every** step has an approval from its own role-holder, in any order; any `REJECTED`
decision rejects the whole instance. On full approval the parked transition executes and the
record proceeds as in 7.2.

---

## 8. Phase 5 — Snapshot publication (listing-service)

When workflow-service emits `TRANSITIONED` into `PUBLISHED`/`APPROVED`/`ACTIVE`,
listing-service fetches the record and upserts a **`ListingSnapshot`** — the immutable,
consumer-facing copy — deriving `searchBoost` from the verification tier (never from caller
input). Transitions into `SUSPENDED`/`ARCHIVED`/`REJECTED` unpublish it. Failures retry 3×
then park on `platform.workflow.events.DLT`.

Vendor/consumer-facing snapshot endpoints:

| Endpoint                                            | Purpose                                    |
|-----------------------------------------------------|--------------------------------------------|
| `GET /api/v1/listings/my`                           | Vendor: own snapshots                      |
| `GET /api/v1/listings/{recordId}/snapshot`          | One snapshot                               |
| `GET /api/v1/listings/search?objectType=VENUE`      | Published listings (DB fallback path)      |
| `GET /api/v1/listings/{recordId}/availability?from=2026-08-01&to=2026-08-31` | Calendar   |
| `PUT /api/v1/listings/{recordId}/availability`      | Vendor sets slots `{from,to,slotType}`     |
| `POST /api/v1/listings/{recordId}/publish`          | Admin manual (re)publish                   |

Snapshot shape:

```json
{ "recordId": "0be6...", "orgId": "d2dd...", "objectType": "VENUE",
  "data": { "name": "Grand Hall", "capacity": 500, "city": "Chennai" },
  "status": "PUBLISHED", "verificationTier": "ENHANCED", "searchBoost": 1.8,
  "version": 3, "publishedAt": "2026-07-04T11:10:04Z" }
```

Each snapshot (un)publication also emits a `ListingEvent` (via `listing_outbox`) on
`platform.listing.events`, which feeds consumer search below.

---

## 9. Phase 6 — Consumer marketplace search (search-service)

### 9.1 Org-scoped search (vendors/admins — authenticated)

```http
POST /api/v1/search
{ "objectType": "VENUE", "query": "hall", "filters": { "data.city": "Chennai" },
  "facets": ["data.city"], "page": 0, "size": 20 }
```

Searches the caller's own org index (`platform-{orgId}-venue`), all statuses, with a
document-level `orgId` filter as defense in depth.

### 9.2 Consumer search — **public, no authentication**

```http
POST /api/v1/search/consumer
Content-Type: application/json

{ "objectType": "VENUE", "query": "palace",
  "filters": { "data.city": "Chennai", "data.capacity": { "gte": 200 } },
  "facets": ["data.city"], "page": 0, "size": 20 }
```

```json
{
  "total": 2, "page": 0, "size": 20,
  "results": [
    { "recordId": "0be6...", "objectType": "VENUE", "status": "PUBLISHED",
      "data": { "name": "Boosted Palace Venue", "city": "Chennai", "capacity": 500 },
      "score": 1.24 },
    { "recordId": "77aa...", "objectType": "VENUE", "status": "PUBLISHED",
      "data": { "name": "Plain Palace Venue", "city": "Chennai", "capacity": 250 },
      "score": 0.62 }
  ],
  "facets": { "data.city": [ { "value": "Chennai", "count": 2 } ] }
}
```

Semantics:
- Queries the **cross-org** index `platform-consumer-venue`, which only ever contains
  workflow-approved PUBLISHED snapshots — that's why the endpoint is safely public
  (gateway permits `POST /api/v1/search/consumer` unauthenticated; search-service lists it
  in `platform.security.public-paths`).
- Relevance is multiplied by the vendor's tier `searchBoost` via `function_score`
  (PREMIUM 2.0× … NONE 1.0×) — verified vendors rank higher.
- An object type with no published listings returns an empty page, not an error.
- Typeahead: `GET /api/v1/search/suggest?objectType=VENUE&field=data.name&prefix=Gra`
  (authenticated, org-scoped).

---

## 10. Behind the scenes — eventing

Every state change and its event **commit atomically** via a per-service transactional
outbox, relayed to Kafka in order, at-least-once (consumers upsert, so replays are benign).
Failed consumers retry 3× then park on `<topic>.DLT`.

| Topic                        | Producer (outbox)            | Consumers                                            |
|------------------------------|------------------------------|------------------------------------------------------|
| `platform.schema.events`     | schema-registry (`schema_outbox`) | record-service, search-service (schema caches)  |
| `platform.record.events`     | record-service (`record_outbox`)  | workflow-service (transition requests), search-service (org indexes), notification/automation |
| `platform.workflow.events`   | workflow-service (`workflow_outbox`) | record-service (applies status), listing-service (snapshots), notification/automation |
| `platform.listing.events`    | listing-service (`listing_outbox`)   | search-service (consumer indexes)                |
| `platform.verification.events` | record-service (`record_outbox`)   | listing/search (boost updates), notification    |

A single publish, end to end:

```
POST /records/{id}/status {trigger:"publish"}
  └─ record_outbox → platform.record.events (STATUS_TRANSITION_REQUESTED + roles + guard ctx)
       └─ workflow-service: role check → guard check → [approval?] → TRANSITIONED
            └─ workflow_outbox → platform.workflow.events
                 ├─ record-service: record.status = PUBLISHED (+ STATUS_CHANGED event)
                 └─ listing-service: upsert ListingSnapshot (searchBoost from tier)
                      └─ listing_outbox → platform.listing.events (PUBLISHED)
                           └─ search-service: index into platform-consumer-venue
                                └─ consumer POST /search/consumer finds it, tier-boosted
```

---

## 11. Not yet built

- **booking-service** — module exists, empty. The intended flow (inquiry → quote → confirm →
  complete, availability holds via `ListingAvailability.bookSlot`, commission from
  `TierConfiguration`) is designed in `todo/13`–`16` but has no implementation. Consumer
  journeys currently end at search + availability viewing.
- **review-service** — post-event ratings; not started.
