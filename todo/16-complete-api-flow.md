# Complete API Flow — lagu-platform (Vendor + Venue)

Full lifecycle from blank platform to a live, searchable venue listing.  
Follow phases in order on first setup; individual phases can be re-run independently.

---

## Conventions

**Stable IDs used throughout this document**
| Variable         | Value                                  | Set in step |
|-----------------|----------------------------------------|-------------|
| `ADMIN_USER`    | `adm-0000-0000-0000-000000000001`      | pre-existing |
| `VENDOR_USER`   | `usr-0000-0000-0000-000000000099`      | pre-existing |
| `VENDOR_ORG_ID` | `org-aaaa-0000-0000-000000000001`      | Step 1.1    |
| `VENDOR_REC_ID` | `rec-bbbb-0000-0000-000000000001`      | Step 1.1    |
| `VENUE_REC_ID`  | `rec-cccc-0000-0000-000000000001`      | Step 2.2    |
| `DOC_PAN_ID`    | `doc-dddd-0000-0000-000000000001`      | Step 1.7    |
| `DOC_GST_ID`    | `doc-dddd-0000-0000-000000000002`      | Step 1.8    |
| `DOC_BANK_ID`   | `doc-dddd-0000-0000-000000000003`      | Step 1.9    |
| `APPROVAL_ID`   | `apv-eeee-0000-0000-000000000001`      | Step 1.15   |
| `VENUE_APV_ID`  | `apv-eeee-0000-0000-000000000002`      | Step 2.15   |
| `CS_ID`         | `cs-ffff-0000-0000-000000000001`       | Step 4.1    |

**Auth headers** (injected by gateway in production; send manually in local testing)
```
X-User-Id:    <uuid>
X-Org-Id:     <uuid>    # vendor org; omit for admin/platform calls
X-User-Roles: VENDOR    # or ADMIN, or VENDOR,ADMIN
```

**Service ports (local)**
| Service          | Port |
|-----------------|------|
| record-service  | 8080 |
| document-service| 8081 |
| search-service  | 8082 |
| workflow-service| 8085 |
| schema-registry | 8090 |
| vendor-service  | 8107 |
| listing-service | 8108 |

---

## Phase 0 — Platform Setup (Admin, one-time)

Schema-registry is pre-seeded on startup (`SchemaRegistrySeeder`).  
Run these calls **only** when extending beyond the seed data or setting up from scratch.

---

### 0.1 Create Fields

Fields are reusable across all listing types. Create platform-level fields (no `orgId`).

**Basics fields:**
```
POST http://localhost:8090/api/v1/fields
X-User-Id:    adm-0000-...
X-User-Roles: ADMIN

{
  "name":          "listing_name",
  "label":         "Listing Name",
  "fieldType":     "TEXT",
  "required":      true,
  "searchable":    true,
  "filterable":    false,
  "sortable":      true,
  "promoted":      true,
  "rangeFilterable": false,
  "arrayManageable": false,
  "validationRules": { "maxLength": 200 }
}
```

**Response `201`:**
```json
{
  "id":        "fld-0001-...",
  "name":      "listing_name",
  "label":     "Listing Name",
  "fieldType": "TEXT",
  "promoted":  true,
  "active":    true
}
```

**Venue-specific ENUM field:**
```
POST http://localhost:8090/api/v1/fields
X-User-Roles: ADMIN

{
  "name":        "venue_type",
  "label":       "Venue Type",
  "fieldType":   "ENUM",
  "enumValues":  ["BANQUET_HALL","OUTDOOR_LAWN","ROOFTOP","RESORT","FARMHOUSE","BEACH"],
  "required":    false,
  "filterable":  true,
  "facetable":   true,
  "promoted":    true
}
```

**Capacity (range-filterable):**
```
POST http://localhost:8090/api/v1/fields
X-User-Roles: ADMIN

{
  "name":            "capacity",
  "label":           "Guest Capacity",
  "fieldType":       "NUMBER",
  "required":        false,
  "filterable":      true,
  "rangeFilterable": true,
  "promoted":        true,
  "validationRules": { "min": 1, "max": 50000 }
}
```

**Halls (array-of-objects):**
```
POST http://localhost:8090/api/v1/fields
X-User-Roles: ADMIN

{
  "name":             "halls",
  "label":            "Hall / Banquet Spaces",
  "fieldType":        "ARRAY_OF_OBJECTS",
  "required":         false,
  "arrayManageable":  true,
  "itemSchema": [
    { "name": "name",         "fieldType": "TEXT",    "required": true  },
    { "name": "capacity",     "fieldType": "NUMBER",  "required": false },
    { "name": "ac_available", "fieldType": "BOOLEAN", "required": false },
    { "name": "indoor",       "fieldType": "BOOLEAN", "required": false }
  ]
}
```

---

### 0.2 Create Field Groups

A field group is an ordered collection of fields that becomes one section in a listing type.

**Venue Basics group:**
```
POST http://localhost:8090/api/v1/field-groups
X-User-Roles: ADMIN

{
  "name":        "venue_basics",
  "label":       "Venue Basics",
  "description": "Core listing identity fields",
  "entries": [
    { "fieldName": "listing_name",  "displayOrder": 1, "required": true  },
    { "fieldName": "description",   "displayOrder": 2, "required": false },
    { "fieldName": "event_types",   "displayOrder": 3, "required": false },
    { "fieldName": "cover_image",   "displayOrder": 4, "required": false }
  ]
}
```

**Response `201`:**
```json
{
  "id":    "fg-0001-...",
  "name":  "venue_basics",
  "label": "Venue Basics",
  "fields": [
    { "id": "fld-0001-...", "name": "listing_name", "displayOrder": 1 },
    ...
  ]
}
```

**Venue Identity & Location group:**
```
POST http://localhost:8090/api/v1/field-groups
X-User-Roles: ADMIN

{
  "name":  "venue_identity",
  "label": "Venue Identity & Location",
  "entries": [
    { "fieldName": "venue_type",    "displayOrder": 1, "required": true  },
    { "fieldName": "address_line1", "displayOrder": 2, "required": true  },
    { "fieldName": "city",          "displayOrder": 3, "required": true  },
    { "fieldName": "state",         "displayOrder": 4, "required": true  },
    { "fieldName": "postal_code",   "displayOrder": 5, "required": true  },
    { "fieldName": "latitude",      "displayOrder": 6, "required": false },
    { "fieldName": "longitude",     "displayOrder": 7, "required": false }
  ]
}
```

**Capacity group:**
```
POST http://localhost:8090/api/v1/field-groups
X-User-Roles: ADMIN

{
  "name":  "venue_capacity",
  "label": "Capacity & Spaces",
  "entries": [
    { "fieldName": "capacity",      "displayOrder": 1, "required": true  },
    { "fieldName": "min_guests",    "displayOrder": 2, "required": false },
    { "fieldName": "ac_available",  "displayOrder": 3, "required": false },
    { "fieldName": "parking_slots", "displayOrder": 4, "required": false },
    { "fieldName": "halls",         "displayOrder": 5, "required": false }
  ]
}
```

**Pricing group:**
```
POST http://localhost:8090/api/v1/field-groups
X-User-Roles: ADMIN

{
  "name":  "venue_pricing",
  "label": "Pricing",
  "entries": [
    { "fieldName": "pricing_model", "displayOrder": 1, "required": true  },
    { "fieldName": "price",         "displayOrder": 2, "required": true  },
    { "fieldName": "min_price",     "displayOrder": 3, "required": false },
    { "fieldName": "max_price",     "displayOrder": 4, "required": false }
  ]
}
```

**Amenities group:**
```
POST http://localhost:8090/api/v1/field-groups
X-User-Roles: ADMIN

{
  "name":  "venue_amenities",
  "label": "Amenities & Facilities",
  "entries": [
    { "fieldName": "amenities",    "displayOrder": 1, "required": false },
    { "fieldName": "has_catering", "displayOrder": 2, "required": false },
    { "fieldName": "has_dj",       "displayOrder": 3, "required": false },
    { "fieldName": "decoration",   "displayOrder": 4, "required": false }
  ]
}
```

---

### 0.3 Create VENDOR Listing Type

VENDOR is not consumer-searchable; it's the org identity record.

```
POST http://localhost:8090/api/v1/listing-types
X-User-Roles: ADMIN

{
  "name":               "VENDOR",
  "label":              "Vendor",
  "description":        "Event vendor / business organization",
  "icon":               "store",
  "color":              "#6366F1",
  "publishable":        false,
  "consumerSearchable": false,
  "sections": [
    {
      "fieldGroupName": "vendor_identity",
      "sectionKey":     "identity",
      "label":          "Business Identity",
      "displayOrder":   1,
      "collapsible":    false
    },
    {
      "fieldGroupName": "vendor_contact",
      "sectionKey":     "contact",
      "label":          "Contact",
      "displayOrder":   2,
      "collapsible":    true
    },
    {
      "fieldGroupName": "vendor_kyc",
      "sectionKey":     "kyc",
      "label":          "KYC & Tax",
      "displayOrder":   3,
      "collapsible":    true
    },
    {
      "fieldGroupName": "vendor_bank",
      "sectionKey":     "bank",
      "label":          "Bank & Payout",
      "displayOrder":   4,
      "collapsible":    true
    }
  ]
}
```

**Response `201`:**
```json
{
  "id":                 "lt-0001-...",
  "name":               "VENDOR",
  "label":              "Vendor",
  "publishable":        false,
  "consumerSearchable": false,
  "currentVersion":     0,
  "sections": [ ... ]
}
```

---

### 0.4 Create VENUE Listing Type

VENUE is publishable and consumer-searchable.

```
POST http://localhost:8090/api/v1/listing-types
X-User-Roles: ADMIN

{
  "name":               "VENUE",
  "label":              "Venue",
  "description":        "Banquet halls, lawns, and event spaces",
  "icon":               "location_city",
  "color":              "#EC4899",
  "publishable":        true,
  "consumerSearchable": true,
  "sections": [
    {
      "fieldGroupName": "venue_basics",
      "sectionKey":     "basics",
      "label":          "Listing Basics",
      "displayOrder":   1,
      "collapsible":    false
    },
    {
      "fieldGroupName": "venue_identity",
      "sectionKey":     "identity",
      "label":          "Identity & Location",
      "displayOrder":   2,
      "collapsible":    false
    },
    {
      "fieldGroupName": "venue_capacity",
      "sectionKey":     "capacity",
      "label":          "Capacity & Spaces",
      "displayOrder":   3,
      "collapsible":    true
    },
    {
      "fieldGroupName": "venue_pricing",
      "sectionKey":     "pricing",
      "label":          "Pricing",
      "displayOrder":   4,
      "collapsible":    true
    },
    {
      "fieldGroupName": "venue_amenities",
      "sectionKey":     "amenities",
      "label":          "Amenities",
      "displayOrder":   5,
      "collapsible":    true
    }
  ]
}
```

---

### 0.5 Configure Workflow — VENDOR

Defines the states and allowed transitions for vendor org records.

**Create workflow definition:**
```
POST http://localhost:8085/api/v1/workflow-definitions
X-User-Roles: ADMIN

{
  "name":          "vendor_lifecycle",
  "label":         "Vendor Lifecycle",
  "objectType":    "VENDOR",
  "initialStatus": "DRAFT"
}
```

**Response `201`:**
```json
{ "id": "wf-0001-...", "name": "vendor_lifecycle", "objectType": "VENDOR" }
```

**Add states:**
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0001-.../states
X-User-Roles: ADMIN

{ "name": "DRAFT",          "label": "Draft",          "displayOrder": 1, "terminal": false, "color": "#94A3B8" }
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0001-.../states
{ "name": "SUBMITTED",      "label": "Submitted",      "displayOrder": 2, "terminal": false, "color": "#F59E0B" }
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0001-.../states
{ "name": "ACTIVE",         "label": "Active",         "displayOrder": 3, "terminal": false, "color": "#10B981" }
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0001-.../states
{ "name": "SUSPENDED",      "label": "Suspended",      "displayOrder": 4, "terminal": false, "color": "#EF4444" }
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0001-.../states
{ "name": "REJECTED",       "label": "Rejected",       "displayOrder": 5, "terminal": true,  "color": "#DC2626" }
```

**Add transitions:**
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0001-.../transitions
X-User-Roles: ADMIN

{
  "fromState":        "DRAFT",
  "toState":          "SUBMITTED",
  "triggerName":      "SUBMIT",
  "triggerLabel":     "Submit for Review",
  "allowedRoles":     ["VENDOR"],
  "requiresApproval": false
}
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0001-.../transitions
{
  "fromState":        "SUBMITTED",
  "toState":          "ACTIVE",
  "triggerName":      "APPROVE",
  "triggerLabel":     "Approve",
  "allowedRoles":     ["ADMIN"],
  "requiresApproval": false
}
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0001-.../transitions
{
  "fromState":    "SUBMITTED",
  "toState":      "REJECTED",
  "triggerName":  "REJECT",
  "allowedRoles": ["ADMIN"]
}
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0001-.../transitions
{
  "fromState":    "ACTIVE",
  "toState":      "SUSPENDED",
  "triggerName":  "SUSPEND",
  "allowedRoles": ["ADMIN"]
}
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0001-.../transitions
{
  "fromState":    "DRAFT",
  "toState":      "DRAFT",
  "triggerName":  "WITHDRAW",
  "allowedRoles": ["VENDOR"]
}
```

---

### 0.6 Configure Workflow — VENUE

```
POST http://localhost:8085/api/v1/workflow-definitions
X-User-Roles: ADMIN

{
  "name":          "venue_lifecycle",
  "label":         "Venue Listing Lifecycle",
  "objectType":    "VENUE",
  "initialStatus": "DRAFT"
}
```

**Add states (same pattern — DRAFT, PENDING_REVIEW, ACTIVE, INACTIVE, REJECTED):**
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0002-.../states
{ "name": "DRAFT",          "label": "Draft",          "displayOrder": 1 }
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0002-.../states
{ "name": "PENDING_REVIEW", "label": "Pending Review", "displayOrder": 2, "color": "#F59E0B" }
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0002-.../states
{ "name": "ACTIVE",         "label": "Active",         "displayOrder": 3, "color": "#10B981" }
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0002-.../states
{ "name": "INACTIVE",       "label": "Inactive",       "displayOrder": 4 }
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0002-.../states
{ "name": "REJECTED",       "label": "Rejected",       "displayOrder": 5, "terminal": true }
```

**Add transitions:**
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0002-.../transitions
{ "fromState": "DRAFT",          "toState": "PENDING_REVIEW", "triggerName": "SUBMIT",   "allowedRoles": ["VENDOR"] }
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0002-.../transitions
{ "fromState": "PENDING_REVIEW", "toState": "ACTIVE",         "triggerName": "APPROVE",  "allowedRoles": ["ADMIN"] }
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0002-.../transitions
{ "fromState": "PENDING_REVIEW", "toState": "DRAFT",          "triggerName": "REQUEST_CHANGES", "allowedRoles": ["ADMIN"] }
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0002-.../transitions
{ "fromState": "PENDING_REVIEW", "toState": "REJECTED",       "triggerName": "REJECT",   "allowedRoles": ["ADMIN"] }
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0002-.../transitions
{ "fromState": "ACTIVE",         "toState": "INACTIVE",       "triggerName": "UNPUBLISH","allowedRoles": ["ADMIN","VENDOR"] }
```
```
POST http://localhost:8085/api/v1/workflow-definitions/wf-0002-.../transitions
{ "fromState": "INACTIVE",       "toState": "PENDING_REVIEW", "triggerName": "RESUBMIT", "allowedRoles": ["VENDOR"] }
```

---

### 0.7 Set Tier Configurations

Four tiers apply to all listing types by default.

```
POST http://localhost:8090/api/v1/tier-configs
X-User-Roles: ADMIN

{
  "tierName":          "NONE",
  "listingType":       null,
  "commissionRate":    12.0,
  "maxActiveBookings": 2,
  "searchBoostFactor": 1.0,
  "responseSlaHours":  48,
  "expiryDays":        0,
  "features":          {}
}
```

```
POST http://localhost:8090/api/v1/tier-configs

{
  "tierName":          "BASIC",
  "listingType":       null,
  "commissionRate":    10.0,
  "maxActiveBookings": 5,
  "searchBoostFactor": 1.2,
  "responseSlaHours":  24,
  "expiryDays":        365,
  "features":          { "prioritySupport": false, "badgeVisible": true }
}
```

```
POST http://localhost:8090/api/v1/tier-configs

{
  "tierName":          "ENHANCED",
  "listingType":       null,
  "commissionRate":    8.0,
  "maxActiveBookings": 15,
  "searchBoostFactor": 1.5,
  "responseSlaHours":  12,
  "expiryDays":        365,
  "features":          { "prioritySupport": true, "badgeVisible": true, "featuredSlots": 2 }
}
```

```
POST http://localhost:8090/api/v1/tier-configs

{
  "tierName":          "PREMIUM",
  "listingType":       null,
  "commissionRate":    6.0,
  "maxActiveBookings": 50,
  "searchBoostFactor": 2.0,
  "responseSlaHours":  4,
  "expiryDays":        180,
  "features":          { "prioritySupport": true, "badgeVisible": true, "featuredSlots": 5, "dedicatedManager": true }
}
```

---

### 0.8 Add Tier Eligibility Rules (VENDOR)

Rules are checked before upgrading a vendor's verification tier.

**BASIC — must have PAN document verified:**
```
POST http://localhost:8090/api/v1/tier-rules
X-User-Roles: ADMIN

{
  "listingType":      "VENDOR",
  "tier":             "BASIC",
  "ruleType":         "DOCUMENT_VERIFIED",
  "documentCode":     "PAN_CARD",
  "displayName":      "PAN Card Verified",
  "description":      "Vendor must have a verified PAN card uploaded",
  "forceOverridable": false,
  "displayOrder":     1
}
```

**BASIC — must have phone filled:**
```
POST http://localhost:8090/api/v1/tier-rules

{
  "listingType":      "VENDOR",
  "tier":             "BASIC",
  "ruleType":         "FIELD_CONDITION",
  "fieldPath":        "phone",
  "operator":         "NOT_EMPTY",
  "displayName":      "Phone Number Filled",
  "forceOverridable": false,
  "displayOrder":     2
}
```

**BASIC — must have bank proof:**
```
POST http://localhost:8090/api/v1/tier-rules

{
  "listingType":      "VENDOR",
  "tier":             "BASIC",
  "ruleType":         "DOCUMENT_VERIFIED",
  "documentCode":     "BANK_PROOF",
  "displayName":      "Bank Proof Verified",
  "forceOverridable": false,
  "displayOrder":     3
}
```

**ENHANCED — GST document:**
```
POST http://localhost:8090/api/v1/tier-rules

{
  "listingType":      "VENDOR",
  "tier":             "ENHANCED",
  "ruleType":         "DOCUMENT_VERIFIED",
  "documentCode":     "GST_CERTIFICATE",
  "displayName":      "GST Certificate Verified",
  "forceOverridable": true,
  "displayOrder":     1
}
```

**ENHANCED — minimum active bookings:**
```
POST http://localhost:8090/api/v1/tier-rules

{
  "listingType":      "VENDOR",
  "tier":             "ENHANCED",
  "ruleType":         "MIN_BOOKINGS",
  "minCount":         10,
  "displayName":      "Minimum 10 Completed Bookings",
  "forceOverridable": true,
  "displayOrder":     2
}
```

---

### 0.9 Add Document Requirements (VENDOR)

Defines which documents vendors must upload and at which tier they become mandatory.

**PAN Card (required for BASIC and above):**
```
POST http://localhost:8090/api/v1/document-requirements
X-User-Roles: ADMIN

{
  "listingType":      "VENDOR",
  "code":             "PAN_CARD",
  "label":            "PAN Card",
  "description":      "Permanent Account Number card or certificate",
  "required":         true,
  "requiredForTiers": ["BASIC", "ENHANCED", "PREMIUM"],
  "expiryTracked":    false,
  "allowedMimeTypes": ["image/jpeg","image/png","application/pdf"],
  "maxSizeMb":        5,
  "displayOrder":     1
}
```

**GST Certificate (required for ENHANCED):**
```
POST http://localhost:8090/api/v1/document-requirements

{
  "listingType":      "VENDOR",
  "code":             "GST_CERTIFICATE",
  "label":            "GST Registration Certificate",
  "description":      "GSTIN registration document from GST portal",
  "required":         false,
  "requiredForTiers": ["ENHANCED", "PREMIUM"],
  "expiryTracked":    false,
  "allowedMimeTypes": ["image/jpeg","image/png","application/pdf"],
  "maxSizeMb":        5,
  "displayOrder":     2
}
```

**Bank Proof (required for BASIC and above):**
```
POST http://localhost:8090/api/v1/document-requirements

{
  "listingType":      "VENDOR",
  "code":             "BANK_PROOF",
  "label":            "Bank Account Proof",
  "description":      "Cancelled cheque or passbook front page",
  "required":         true,
  "requiredForTiers": ["BASIC", "ENHANCED", "PREMIUM"],
  "expiryTracked":    false,
  "allowedMimeTypes": ["image/jpeg","image/png","application/pdf"],
  "maxSizeMb":        5,
  "displayOrder":     3
}
```

**FSSAI Licence (caterers only — optional):**
```
POST http://localhost:8090/api/v1/document-requirements

{
  "listingType":      "CATERER",
  "code":             "FSSAI_LICENCE",
  "label":            "FSSAI Food Licence",
  "description":      "Food safety and standards licence",
  "required":         false,
  "requiredForTiers": ["ENHANCED", "PREMIUM"],
  "expiryTracked":    true,
  "allowedMimeTypes": ["image/jpeg","image/png","application/pdf"],
  "maxSizeMb":        10,
  "displayOrder":     1
}
```

---

### 0.10 Set Up Search Definitions (VENUE)

Controls which facets are exposed in consumer and admin search.

```
POST http://localhost:8090/api/v1/search-definitions
X-User-Roles: ADMIN

{
  "listingType":      "VENUE",
  "consumerFacets":   ["city", "venue_type", "pricing_model", "has_catering", "ac_available"],
  "adminFacets":      ["city", "state", "venue_type", "status", "pricing_model"],
  "defaultSortField": "price",
  "defaultSortDir":   "asc",
  "boostField":       "search_boost"
}
```

**Response:**
```json
{
  "listingType":    "VENUE",
  "consumerFacets": ["city","venue_type","pricing_model","has_catering","ac_available"],
  "adminFacets":    ["city","state","venue_type","status","pricing_model"],
  "defaultSortField": "price"
}
```

---

### 0.11 Publish VENDOR Schema (versioning)

Publishing increments the version counter and fires a `SchemaPublishedEvent` on Kafka, which triggers cache eviction across all services.

```
POST http://localhost:8090/api/v1/listing-types/VENDOR/publish
X-User-Id:    adm-0000-...
X-User-Roles: ADMIN

{
  "changeSummary": "Initial VENDOR schema — identity, contact, KYC, bank sections"
}
```

**Response:**
```json
{
  "id":                  "sv-0001-...",
  "listingType":         "VENDOR",
  "version":             1,
  "changeClassification":"SAFE",
  "changeSummary":       "Initial VENDOR schema — identity, contact, KYC, bank sections",
  "publishedBy":         "adm-0000-...",
  "publishedAt":         "2026-07-01T09:00:00Z"
}
```

**Publish VENUE schema:**
```
POST http://localhost:8090/api/v1/listing-types/VENUE/publish
X-User-Id: adm-0000-...

{ "changeSummary": "Initial VENUE schema — basics, identity, capacity, pricing, amenities" }
```

---

## Phase 1 — Vendor Onboarding (Vendor User)

---

### 1.1 Register Vendor Org

Creates org, VENDOR record (status=DRAFT), and binds the calling user to that org.

```
POST http://localhost:8107/api/v1/vendors/register
X-User-Id:    usr-0000-...
X-User-Roles: VENDOR

{
  "businessName":     "Royal Gardens Events Pvt Ltd",
  "country":          "IN",
  "primaryVendorType": "VENUE"
}
```

**Response `201`:**
```json
{
  "orgId":        "org-aaaa-...",
  "recordId":     "rec-bbbb-...",
  "businessName": "Royal Gardens Events Pvt Ltd",
  "status":       "DRAFT",
  "country":      "IN",
  "createdAt":    "2026-07-01T10:00:00Z"
}
```

> **Save:** `orgId = org-aaaa-...` and `recordId = rec-bbbb-...`

---

### 1.2 Fetch VENDOR Schema (to know what fields to fill)

```
GET http://localhost:8090/api/v1/listing-types/VENDOR/schema
```

**Response excerpt:**
```json
{
  "listingType": "VENDOR",
  "version": 1,
  "sections": [
    {
      "sectionKey": "identity",
      "label": "Business Identity",
      "fields": [
        { "name": "name",           "fieldType": "TEXT",    "required": true  },
        { "name": "business_type",  "fieldType": "ENUM",    "enumValues": ["SOLE_PROPRIETOR","PARTNERSHIP","LLP","PVT_LTD","INDIVIDUAL"] },
        { "name": "year_established","fieldType": "NUMBER", "required": false },
        { "name": "description",    "fieldType": "LONG_TEXT","required": false }
      ]
    },
    {
      "sectionKey": "contact",
      "fields": [
        { "name": "phone",   "fieldType": "PHONE", "required": true },
        { "name": "email",   "fieldType": "EMAIL", "required": true },
        { "name": "website", "fieldType": "URL",   "required": false }
      ]
    },
    {
      "sectionKey": "kyc",
      "fields": [
        { "name": "pan_number", "fieldType": "TEXT", "validationRules": { "pattern": "^[A-Z]{5}[0-9]{4}[A-Z]$" } },
        { "name": "gstin",      "fieldType": "TEXT" },
        { "name": "gst_status", "fieldType": "ENUM", "enumValues": ["NOT_REGISTERED","REGISTERED","COMPOSITION"] }
      ]
    },
    {
      "sectionKey": "bank",
      "fields": [
        { "name": "account_holder", "fieldType": "TEXT" },
        { "name": "bank_name",      "fieldType": "TEXT" },
        { "name": "account_number", "fieldType": "TEXT" },
        { "name": "ifsc_code",      "fieldType": "TEXT", "validationRules": { "pattern": "^[A-Z]{4}0[A-Z0-9]{6}$" } },
        { "name": "account_type",   "fieldType": "ENUM", "enumValues": ["SAVINGS","CURRENT"] }
      ]
    }
  ]
}
```

---

### 1.3 Fill Business Identity Section

```
PATCH http://localhost:8080/api/v1/records/rec-bbbb-...
X-User-Id:    usr-0000-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "name":             "Royal Gardens Events Pvt Ltd",
  "business_type":    "PVT_LTD",
  "year_established": 2010,
  "description":      "Premier event management and venue company based in Mumbai with 14 years of experience in weddings, corporate events, and social celebrations."
}
```

**Response:**
```json
{
  "id":         "rec-bbbb-...",
  "objectType": "VENDOR",
  "orgId":      "org-aaaa-...",
  "status":     "DRAFT",
  "data": {
    "name":             "Royal Gardens Events Pvt Ltd",
    "business_type":    "PVT_LTD",
    "year_established": 2010,
    "description":      "Premier event management..."
  },
  "updatedAt": "2026-07-01T10:05:00Z"
}
```

---

### 1.4 Fill Contact Section

```
PATCH http://localhost:8080/api/v1/records/rec-bbbb-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "phone":   "+91-9876543210",
  "email":   "info@royalgardens.in",
  "website": "https://www.royalgardens.in"
}
```

---

### 1.5 Fill Address

```
PATCH http://localhost:8080/api/v1/records/rec-bbbb-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "address_line1": "12th Floor, One BKC Tower",
  "address_line2": "Bandra Kurla Complex",
  "city":          "Mumbai",
  "state":         "Maharashtra",
  "country":       "IN",
  "postal_code":   "400051",
  "latitude":      19.0636,
  "longitude":     72.8656
}
```

---

### 1.6 Fill KYC & Tax Section

```
PATCH http://localhost:8080/api/v1/records/rec-bbbb-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "pan_number": "AAACR5055K",
  "gst_status": "REGISTERED",
  "gstin":      "27AAACR5055K1Z5"
}
```

---

### 1.7 Fill Bank & Payout Section

```
PATCH http://localhost:8080/api/v1/records/rec-bbbb-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "account_holder": "Royal Gardens Events Pvt Ltd",
  "bank_name":      "HDFC Bank",
  "account_number": "50100234567890",
  "ifsc_code":      "HDFC0001234",
  "account_type":   "CURRENT"
}
```

---

### 1.8 Upload PAN Card

```
POST http://localhost:8081/api/v1/documents
X-User-Id:    usr-0000-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR
Content-Type: multipart/form-data

file:            <binary — PAN card image/PDF, max 5 MB>
documentType:    IDENTITY_PROOF
identitySubType: PAN_CARD
```

**Response `201`:**
```json
{
  "id":           "doc-dddd-0001-...",
  "documentType": "IDENTITY_PROOF",
  "subType":      "PAN_CARD",
  "status":       "UPLOADED",
  "fileUrl":      "https://cdn.lagu.in/docs/org-aaaa/pan-card-2026.jpg",
  "uploadedAt":   "2026-07-01T10:20:00Z"
}
```

---

### 1.9 Upload GST Certificate

```
POST http://localhost:8081/api/v1/documents
X-Org-Id:  org-aaaa-...
Content-Type: multipart/form-data

file:         <binary — GST certificate PDF>
documentType: OTHER
```

**Response `201`:**
```json
{
  "id":           "doc-dddd-0002-...",
  "documentType": "OTHER",
  "status":       "UPLOADED",
  "fileUrl":      "https://cdn.lagu.in/docs/org-aaaa/gst-cert-2026.pdf",
  "uploadedAt":   "2026-07-01T10:22:00Z"
}
```

---

### 1.10 Upload Bank Proof (Cancelled Cheque)

```
POST http://localhost:8081/api/v1/documents
X-Org-Id:  org-aaaa-...
Content-Type: multipart/form-data

file:         <binary — cancelled cheque scan>
documentType: OTHER
```

**Response `201`:**
```json
{
  "id":     "doc-dddd-0003-...",
  "status": "UPLOADED"
}
```

---

### 1.11 Check KYC Readiness

```
GET http://localhost:8107/api/v1/vendors/me/kyc
X-User-Id:    usr-0000-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR
```

**Response — all green:**
```json
{
  "hasGstDoc":          true,
  "hasPanDoc":          true,
  "hasBankDoc":         true,
  "hasIdentityDoc":     true,
  "businessNameFilled": true,
  "addressFilled":      true,
  "phoneFilled":        true,
  "kycReady":           true
}
```

**If `kycReady: false`** — check which flag is false and complete the missing steps before continuing.

---

### 1.12 (Optional) Preview Tier Eligibility for BASIC

```
GET http://localhost:8090/api/v1/tier-rules/check
    ?recordId=rec-bbbb-...
    &targetTier=BASIC
    &listingType=VENDOR
X-User-Id: usr-0000-...
```

**Response:**
```json
{
  "recordId":   "rec-bbbb-...",
  "targetTier": "BASIC",
  "satisfied":  true,
  "rules": [
    {
      "ruleType":    "DOCUMENT_VERIFIED",
      "documentCode":"PAN_CARD",
      "displayName": "PAN Card Verified",
      "satisfied":   false,
      "reason":      "Document uploaded but not yet verified by admin"
    },
    {
      "ruleType":  "FIELD_CONDITION",
      "fieldPath": "phone",
      "displayName":"Phone Number Filled",
      "satisfied": true
    },
    {
      "ruleType":    "DOCUMENT_VERIFIED",
      "documentCode":"BANK_PROOF",
      "displayName": "Bank Proof Verified",
      "satisfied":   false,
      "reason":      "Document uploaded but not yet verified by admin"
    }
  ]
}
```

> Documents are `UPLOADED` but not yet `VERIFIED` — admin must verify them (Steps 1.14–1.15) before `satisfied` turns true.

---

### 1.13 Submit Vendor for Admin Review

Status: `DRAFT → SUBMITTED`

```
POST http://localhost:8107/api/v1/vendors/me/submit
X-User-Id:    usr-0000-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR
```

**Response:**
```json
{
  "orgId":    "org-aaaa-...",
  "recordId": "rec-bbbb-...",
  "status":   "SUBMITTED",
  "updatedAt":"2026-07-01T10:30:00Z"
}
```

---

### 1.14 Admin: List Vendors Pending Review

```
GET http://localhost:8107/api/v1/vendors?status=SUBMITTED
X-User-Id:    adm-0000-...
X-User-Roles: ADMIN
```

**Response:**
```json
[
  {
    "orgId":        "org-aaaa-...",
    "recordId":     "rec-bbbb-...",
    "businessName": "Royal Gardens Events Pvt Ltd",
    "status":       "SUBMITTED",
    "country":      "IN",
    "createdAt":    "2026-07-01T10:00:00Z"
  }
]
```

---

### 1.15 Admin: List Uploaded Documents

```
GET http://localhost:8081/api/v1/documents/pending-review?page=0&size=20
X-User-Roles: ADMIN
```

**Response:**
```json
{
  "content": [
    { "id": "doc-dddd-0001-...", "documentType": "IDENTITY_PROOF", "subType": "PAN_CARD",  "status": "UPLOADED" },
    { "id": "doc-dddd-0002-...", "documentType": "OTHER",                                   "status": "UPLOADED" },
    { "id": "doc-dddd-0003-...", "documentType": "OTHER",                                   "status": "UPLOADED" }
  ],
  "totalElements": 3
}
```

---

### 1.16 Admin: Claim PAN Card for Review

```
POST http://localhost:8081/api/v1/documents/doc-dddd-0001-.../review
X-User-Id:    adm-0000-...
X-User-Roles: ADMIN
```

**Response:**
```json
{ "id": "doc-dddd-0001-...", "status": "UNDER_REVIEW" }
```

---

### 1.17 Admin: Verify PAN Card

```
POST http://localhost:8081/api/v1/documents/doc-dddd-0001-.../verify
X-User-Id:    adm-0000-...
X-User-Roles: ADMIN
```

**Response:**
```json
{ "id": "doc-dddd-0001-...", "status": "VERIFIED", "verifiedAt": "2026-07-01T11:00:00Z" }
```

Repeat Steps 1.16–1.17 for `doc-dddd-0002-...` (GST) and `doc-dddd-0003-...` (Bank Proof).

**Admin: Reject a document (if needed):**
```
POST http://localhost:8081/api/v1/documents/doc-dddd-0002-.../reject
X-User-Roles: ADMIN

{ "rejectionReason": "GST certificate is expired — please upload a renewed certificate" }
```

---

### 1.18 Admin: Get Pending Approvals

```
GET http://localhost:8085/api/v1/approvals/pending
X-User-Id:    adm-0000-...
X-User-Roles: ADMIN
```

**Response:**
```json
[
  {
    "id":         "apv-eeee-0001-...",
    "recordId":   "rec-bbbb-...",
    "objectType": "VENDOR",
    "step":       "KYC_REVIEW",
    "assignedTo": ["ADMIN"],
    "createdAt":  "2026-07-01T10:30:00Z"
  }
]
```

---

### 1.19 Admin: Approve Vendor

```
POST http://localhost:8085/api/v1/approvals/apv-eeee-0001-.../decide
X-User-Id:    adm-0000-...
X-User-Roles: ADMIN

{
  "decision": "APPROVE",
  "comment":  "All KYC documents verified. Business address confirmed. PAN and bank details match."
}
```

**Response:**
```json
{
  "id":       "apv-eeee-0001-...",
  "decision": "APPROVE",
  "status":   "COMPLETED",
  "decidedAt":"2026-07-01T11:15:00Z"
}
```

Alternatively, update vendor status directly:
```
PATCH http://localhost:8107/api/v1/vendors/org-aaaa-.../status
X-User-Roles: ADMIN

{ "status": "ACTIVE" }
```

---

### 1.20 Admin: Set Initial Verification Tier (BASIC)

Documents are verified → upgrade to BASIC tier.

```
PUT http://localhost:8080/api/v1/records/rec-bbbb-.../verification
X-User-Id:    adm-0000-...
X-User-Roles: ADMIN

{
  "tier":      "BASIC",
  "expiresAt": "2027-07-01T00:00:00Z",
  "notes":     "PAN card, GST certificate, and bank proof all verified. Upgraded to BASIC tier."
}
```

**Response:**
```json
{
  "recordId":  "rec-bbbb-...",
  "tier":      "BASIC",
  "expiresAt": "2027-07-01T00:00:00Z",
  "setAt":     "2026-07-01T11:20:00Z"
}
```

---

## Phase 2 — Venue Listing Creation

Vendor org is now `ACTIVE`. Vendor creates their first venue listing.

---

### 2.1 Fetch VENUE Schema

Always fetch the schema first — the field names and types are the source of truth.

```
GET http://localhost:8090/api/v1/listing-types/VENUE/schema
```

**Response:**
```json
{
  "listingType": "VENUE",
  "version": 1,
  "sections": [
    {
      "sectionKey": "basics",
      "label": "Listing Basics",
      "fields": [
        { "name": "listing_name", "fieldType": "TEXT",         "required": true,  "promoted": true  },
        { "name": "description",  "fieldType": "LONG_TEXT",    "required": false                    },
        { "name": "cover_image",  "fieldType": "IMAGE",        "required": false                    }
      ]
    },
    {
      "sectionKey": "identity",
      "label": "Identity & Location",
      "fields": [
        { "name": "venue_type",    "fieldType": "ENUM",    "required": true,  "promoted": true, "filterable": true,
          "enumValues": ["BANQUET_HALL","OUTDOOR_LAWN","ROOFTOP","RESORT","FARMHOUSE","BEACH"] },
        { "name": "address_line1", "fieldType": "TEXT",    "required": true                    },
        { "name": "city",          "fieldType": "TEXT",    "required": true,  "promoted": true, "filterable": true },
        { "name": "state",         "fieldType": "TEXT",    "required": true                    },
        { "name": "postal_code",   "fieldType": "TEXT",    "required": true                    },
        { "name": "latitude",      "fieldType": "DECIMAL", "required": false                   },
        { "name": "longitude",     "fieldType": "DECIMAL", "required": false                   }
      ]
    },
    {
      "sectionKey": "capacity",
      "label": "Capacity & Spaces",
      "fields": [
        { "name": "capacity",      "fieldType": "NUMBER", "required": true, "promoted": true, "rangeFilterable": true },
        { "name": "min_guests",    "fieldType": "NUMBER"                                                              },
        { "name": "ac_available",  "fieldType": "BOOLEAN","promoted": true, "filterable": true                       },
        { "name": "parking_slots", "fieldType": "NUMBER"                                                              },
        { "name": "halls",         "fieldType": "ARRAY_OF_OBJECTS", "arrayManageable": true,
          "itemSchema": [
            { "name": "name",         "fieldType": "TEXT",    "required": true  },
            { "name": "capacity",     "fieldType": "NUMBER"                     },
            { "name": "ac_available", "fieldType": "BOOLEAN"                   },
            { "name": "indoor",       "fieldType": "BOOLEAN"                   }
          ]
        }
      ]
    },
    {
      "sectionKey": "pricing",
      "label": "Pricing",
      "fields": [
        { "name": "pricing_model", "fieldType": "ENUM",    "required": true,
          "enumValues": ["FIXED","PER_HOUR","PER_DAY","PER_PERSON","PER_PLATE","PACKAGE"] },
        { "name": "price",         "fieldType": "DECIMAL", "required": true, "promoted": true, "rangeFilterable": true },
        { "name": "min_price",     "fieldType": "DECIMAL"                                                               },
        { "name": "max_price",     "fieldType": "DECIMAL"                                                               }
      ]
    },
    {
      "sectionKey": "amenities",
      "label": "Amenities & Facilities",
      "fields": [
        { "name": "amenities",   "fieldType": "MULTI_SELECT", "filterable": true,
          "enumValues": ["WIFI","GENERATOR","VALET","SWIMMING_POOL","GYM","BAR","STAGE"] },
        { "name": "has_catering","fieldType": "BOOLEAN", "filterable": true             },
        { "name": "has_dj",      "fieldType": "BOOLEAN"                                },
        { "name": "decoration",  "fieldType": "BOOLEAN"                                }
      ]
    }
  ]
}
```

---

### 2.2 Create Venue Record (Draft — Basics Only)

```
POST http://localhost:8080/api/v1/records
X-User-Id:    usr-0000-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "objectType": "VENUE",
  "status":     "DRAFT",
  "data": {
    "listing_name": "Royal Gardens Banquet Hall",
    "description":  "A premium 500-pax banquet and lawn venue in the heart of Mumbai, ideal for weddings, receptions, sangeet, and corporate events. AC ballroom + open-air lawn available."
  }
}
```

**Response `201`:**
```json
{
  "id":         "rec-cccc-...",
  "objectType": "VENUE",
  "orgId":      "org-aaaa-...",
  "status":     "DRAFT",
  "data": {
    "listing_name": "Royal Gardens Banquet Hall",
    "description":  "A premium 500-pax banquet..."
  },
  "createdAt":  "2026-07-01T12:00:00Z"
}
```

> Save `id = rec-cccc-...` as `VENUE_REC_ID`.

---

### 2.3 Update Identity & Location Section

```
PATCH http://localhost:8080/api/v1/records/rec-cccc-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "venue_type":    "BANQUET_HALL",
  "address_line1": "123 Garden Road, Andheri West",
  "address_line2": "Near Infinity Mall",
  "city":          "Mumbai",
  "state":         "Maharashtra",
  "country":       "IN",
  "postal_code":   "400053",
  "latitude":      19.1364,
  "longitude":     72.8296
}
```

**Response:**
```json
{
  "id":     "rec-cccc-...",
  "status": "DRAFT",
  "data": {
    "listing_name": "Royal Gardens Banquet Hall",
    "venue_type":   "BANQUET_HALL",
    "city":         "Mumbai",
    "state":        "Maharashtra",
    "postal_code":  "400053",
    "latitude":     19.1364,
    "longitude":    72.8296,
    ...
  }
}
```

---

### 2.4 Update Capacity Section

```
PATCH http://localhost:8080/api/v1/records/rec-cccc-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "capacity":      500,
  "min_guests":    50,
  "ac_available":  true,
  "parking_slots": 150
}
```

---

### 2.5 Add Hall Spaces

The `halls` field is `ARRAY_OF_OBJECTS` — the full array is replaced on each PATCH.

```
PATCH http://localhost:8080/api/v1/records/rec-cccc-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "halls": [
    {
      "name":         "Grand Ballroom",
      "capacity":     300,
      "ac_available": true,
      "indoor":       true
    },
    {
      "name":         "Garden Lawn",
      "capacity":     200,
      "ac_available": false,
      "indoor":       false
    },
    {
      "name":         "Rooftop Lounge",
      "capacity":     80,
      "ac_available": false,
      "indoor":       false
    }
  ]
}
```

---

### 2.6 Update Pricing Section

```
PATCH http://localhost:8080/api/v1/records/rec-cccc-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "pricing_model": "PER_PLATE",
  "price":         800,
  "min_price":     600,
  "max_price":     1500
}
```

---

### 2.7 Update Amenities Section

```
PATCH http://localhost:8080/api/v1/records/rec-cccc-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "amenities":   ["WIFI", "GENERATOR", "VALET", "BAR", "STAGE"],
  "has_catering": true,
  "has_dj":       false,
  "decoration":   true
}
```

---

### 2.8 Upload Cover Image

```
POST http://localhost:8080/api/v1/records/rec-cccc-.../files/cover_image
X-User-Id:    usr-0000-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR
Content-Type: multipart/form-data

file: <binary — JPG/PNG, max 10 MB>
```

**Response:**
```json
{
  "id":     "rec-cccc-...",
  "status": "DRAFT",
  "data": {
    "cover_image": "https://cdn.lagu.in/listings/rec-cccc/cover_image.jpg",
    ...
  }
}
```

---

### 2.9 Link Venue to Vendor (Relationship)

Explicitly link the venue record to the vendor record for cross-record navigation.

```
POST http://localhost:8080/api/v1/records/rec-cccc-.../relationships
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "relationshipName": "OWNED_BY",
  "targetRecordId":   "rec-bbbb-..."
}
```

**Response `201`:**
```json
{
  "sourceRecordId":   "rec-cccc-...",
  "relationshipName": "OWNED_BY",
  "targetRecordId":   "rec-bbbb-...",
  "createdAt":        "2026-07-01T12:30:00Z"
}
```

---

### 2.10 Set Availability Calendar

Mark the venue as available for a date range.

```
PUT http://localhost:8108/api/v1/listings/rec-cccc-.../availability
X-User-Id:    usr-0000-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "from":     "2026-08-01",
  "to":       "2026-12-31",
  "slotType": "AVAILABLE"
}
```

**Response:**
```json
[
  { "date": "2026-08-01", "slotType": "AVAILABLE" },
  ...
  { "date": "2026-12-31", "slotType": "AVAILABLE" }
]
```

Block a specific date:
```
PUT http://localhost:8108/api/v1/listings/rec-cccc-.../availability
X-Org-Id:     org-aaaa-...

{
  "from":     "2026-10-24",
  "to":       "2026-10-24",
  "slotType": "BLOCKED"
}
```

---

### 2.11 Check Workflow Status Before Submitting

```
GET http://localhost:8085/api/v1/records/rec-cccc-.../workflow
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR
```

**Response:**
```json
{
  "recordId":          "rec-cccc-...",
  "currentState":      "DRAFT",
  "availableTriggers": ["SUBMIT"],
  "pendingApprovals":  []
}
```

---

### 2.12 Submit Venue for Admin Review

Status: `DRAFT → PENDING_REVIEW`

```
POST http://localhost:8080/api/v1/records/rec-cccc-.../status
X-User-Id:    usr-0000-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "trigger": "SUBMIT",
  "comment": "Venue profile complete. All required sections filled."
}
```

**Response:**
```json
{
  "id":     "rec-cccc-...",
  "status": "PENDING_REVIEW"
}
```

---

### 2.13 Admin: Get Full Venue Record for Review

```
GET http://localhost:8080/api/v1/records/rec-cccc-...
X-User-Roles: ADMIN
```

**Response:**
```json
{
  "id":         "rec-cccc-...",
  "objectType": "VENUE",
  "orgId":      "org-aaaa-...",
  "status":     "PENDING_REVIEW",
  "data": {
    "listing_name":  "Royal Gardens Banquet Hall",
    "venue_type":    "BANQUET_HALL",
    "city":          "Mumbai",
    "capacity":      500,
    "price":         800,
    "pricing_model": "PER_PLATE",
    "amenities":     ["WIFI","GENERATOR","VALET","BAR","STAGE"],
    "has_catering":  true,
    "cover_image":   "https://cdn.lagu.in/listings/rec-cccc/cover_image.jpg",
    "halls": [
      { "name": "Grand Ballroom", "capacity": 300, "ac_available": true, "indoor": true },
      { "name": "Garden Lawn",    "capacity": 200, "ac_available": false, "indoor": false }
    ]
  }
}
```

---

### 2.14 Admin: Approve Venue

```
GET http://localhost:8085/api/v1/approvals/pending
X-User-Roles: ADMIN
```

```
POST http://localhost:8085/api/v1/approvals/apv-eeee-0002-.../decide
X-User-Id:    adm-0000-...
X-User-Roles: ADMIN

{
  "decision": "APPROVE",
  "comment":  "Venue details verified. Address confirmed on Maps. Capacity and pricing consistent."
}
```

**Response:**
```json
{
  "id":       "apv-eeee-0002-...",
  "decision": "APPROVE",
  "status":   "COMPLETED",
  "decidedAt":"2026-07-01T14:00:00Z"
}
```

Venue record status becomes `ACTIVE`.

---

### 2.15 Admin: Request Changes (if issues found)

```
POST http://localhost:8085/api/v1/approvals/apv-eeee-0002-.../decide
X-User-Roles: ADMIN

{
  "decision": "REJECT",
  "comment":  "Cover image is low resolution. Address coordinates look incorrect — please update."
}
```

Status returns to `DRAFT`. Vendor fixes and re-submits (Step 2.12).

---

### 2.16 Publish to Consumer Search Index

```
POST http://localhost:8108/api/v1/listings/rec-cccc-.../publish
X-User-Id:    adm-0000-...
X-User-Roles: ADMIN

{
  "orgId":            "org-aaaa-...",
  "objectType":       "VENUE",
  "verificationTier": "BASIC",
  "searchBoost":      1.2,
  "data": {
    "listing_name":  "Royal Gardens Banquet Hall",
    "venue_type":    "BANQUET_HALL",
    "city":          "Mumbai",
    "state":         "Maharashtra",
    "capacity":      500,
    "price":         800,
    "pricing_model": "PER_PLATE",
    "amenities":     ["WIFI","GENERATOR","VALET","BAR","STAGE"],
    "has_catering":  true,
    "ac_available":  true,
    "cover_image":   "https://cdn.lagu.in/listings/rec-cccc/cover_image.jpg"
  }
}
```

**Response:**
```json
{
  "recordId":         "rec-cccc-...",
  "orgId":            "org-aaaa-...",
  "objectType":       "VENUE",
  "verificationTier": "BASIC",
  "searchBoost":      1.2,
  "publishedAt":      "2026-07-01T14:05:00Z"
}
```

---

## Phase 3 — Consumer Discovery

---

### 3.1 Full-text Venue Search

```
POST http://localhost:8082/api/v1/search
X-User-Id:    usr-0000-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "objectType": "VENUE",
  "query":      "banquet hall Mumbai wedding",
  "page":       0,
  "size":       20,
  "sort":       [{ "field": "price", "direction": "asc" }],
  "facets":     ["city", "venue_type", "has_catering", "ac_available"]
}
```

**Response:**
```json
{
  "total":   1,
  "page":    0,
  "size":    20,
  "results": [
    {
      "recordId":    "rec-cccc-...",
      "objectType":  "VENUE",
      "score":       1.85,
      "data": {
        "listing_name": "Royal Gardens Banquet Hall",
        "city":         "Mumbai",
        "venue_type":   "BANQUET_HALL",
        "capacity":     500,
        "price":        800,
        "cover_image":  "https://cdn.lagu.in/listings/rec-cccc/cover_image.jpg"
      }
    }
  ],
  "facets": {
    "city":        [{ "value": "Mumbai",       "count": 1 }],
    "venue_type":  [{ "value": "BANQUET_HALL", "count": 1 }],
    "has_catering":[{ "value": "true",         "count": 1 }],
    "ac_available":[{ "value": "true",         "count": 1 }]
  }
}
```

---

### 3.2 Filtered Search with Range Query

```
POST http://localhost:8082/api/v1/search

{
  "objectType": "VENUE",
  "query":      null,
  "filters": {
    "city":         "Mumbai",
    "venue_type":   "BANQUET_HALL",
    "has_catering": true,
    "capacity":     { "gte": 200, "lte": 600 },
    "price":        { "gte": 500, "lte": 1200 }
  },
  "page": 0,
  "size": 20,
  "sort": [{ "field": "price", "direction": "asc" }]
}
```

---

### 3.3 Get Listing Snapshot (Consumer Detail Page)

```
GET http://localhost:8108/api/v1/listings/rec-cccc-.../snapshot
```

**Response:**
```json
{
  "recordId":         "rec-cccc-...",
  "objectType":       "VENUE",
  "verificationTier": "BASIC",
  "publishedAt":      "2026-07-01T14:05:00Z",
  "data": { ... full venue data ... }
}
```

---

### 3.4 Check Venue Availability

```
GET http://localhost:8108/api/v1/listings/rec-cccc-.../availability
    ?from=2026-10-01&to=2026-10-31
```

**Response:**
```json
[
  { "date": "2026-10-01", "slotType": "AVAILABLE" },
  ...
  { "date": "2026-10-23", "slotType": "AVAILABLE" },
  { "date": "2026-10-24", "slotType": "BLOCKED"   },
  { "date": "2026-10-25", "slotType": "AVAILABLE" },
  ...
]
```

---

### 3.5 Typeahead Suggestions

```
GET http://localhost:8082/api/v1/search/suggest
    ?objectType=VENUE&field=city&prefix=Mum
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR
```

**Response:**
```json
["Mumbai", "Mumbra"]
```

---

## Phase 4 — Post-Activation Changes (Change Sets)

When vendor status is `ACTIVE`, profile changes go through the change-set approval queue.

---

### 4.1 Vendor Submits a Profile Change

```
POST http://localhost:8085/api/v1/change-sets
X-User-Id:    usr-0000-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "recordId":    "rec-bbbb-...",
  "orgId":       "org-aaaa-...",
  "objectType":  "VENDOR",
  "workflowId":  null,
  "originalData": {
    "phone": "+91-9876543210",
    "email": "info@royalgardens.in"
  },
  "proposedData": {
    "phone": "+91-9000001234",
    "email": "bookings@royalgardens.in"
  }
}
```

**Response `201`:**
```json
{
  "id":          "cs-ffff-0001-...",
  "recordId":    "rec-bbbb-...",
  "orgId":       "org-aaaa-...",
  "objectType":  "VENDOR",
  "status":      "PENDING",
  "submittedAt": "2026-07-15T09:00:00Z"
}
```

---

### 4.2 Vendor Views Their Pending Change Sets

```
GET http://localhost:8085/api/v1/change-sets?status=PENDING
X-User-Id:    usr-0000-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR
```

---

### 4.3 Vendor Withdraws a Change Before Review

```
POST http://localhost:8085/api/v1/change-sets/cs-ffff-0001-.../withdraw
X-User-Id:    usr-0000-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR
```

**Response:**
```json
{ "id": "cs-ffff-0001-...", "status": "WITHDRAWN" }
```

---

### 4.4 Admin: List All Pending Change Sets

```
GET http://localhost:8085/api/v1/change-sets/pending
X-User-Roles: ADMIN
```

**Response:**
```json
[
  {
    "id":          "cs-ffff-0001-...",
    "recordId":    "rec-bbbb-...",
    "orgId":       "org-aaaa-...",
    "objectType":  "VENDOR",
    "status":      "PENDING",
    "proposedData":{ "phone": "+91-9000001234", "email": "bookings@royalgardens.in" },
    "submittedAt": "2026-07-15T09:00:00Z"
  }
]
```

---

### 4.5 Admin: Approve Change Set (Apply Proposed Data)

```
POST http://localhost:8085/api/v1/change-sets/cs-ffff-0001-.../review
X-User-Id:    adm-0000-...
X-User-Roles: ADMIN

{
  "decision":     "APPROVE",
  "adminComment": "Contact details verified. Phone and email updated.",
  "correctedData": null
}
```

**Response:**
```json
{ "id": "cs-ffff-0001-...", "status": "APPROVED", "reviewedAt": "2026-07-15T10:00:00Z" }
```

---

### 4.6 Admin: Approve with Correction (Override One Field)

```
POST http://localhost:8085/api/v1/change-sets/cs-ffff-0001-.../review
X-User-Roles: ADMIN

{
  "decision":      "APPROVE",
  "adminComment":  "Email approved as-is, but correcting phone format",
  "correctedData": {
    "phone": "+919000001234"
  }
}
```

---

### 4.7 Admin: Reject Change Set

```
POST http://localhost:8085/api/v1/change-sets/cs-ffff-0001-.../review
X-User-Roles: ADMIN

{
  "decision":     "REJECT",
  "adminComment": "New email domain cannot be verified. Please use the registered business email."
}
```

---

### 4.8 Vendor Updates Venue After It Goes Live

Venue updates (while ACTIVE) follow the same change-set pattern — submit → admin approves → record updates:

```
POST http://localhost:8085/api/v1/change-sets
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR

{
  "recordId":   "rec-cccc-...",
  "orgId":      "org-aaaa-...",
  "objectType": "VENUE",
  "originalData": { "price": 800 },
  "proposedData": { "price": 950 }
}
```

After admin approves, re-publish the snapshot to reflect updated price in search:
```
POST http://localhost:8108/api/v1/listings/rec-cccc-.../publish
X-User-Roles: ADMIN

{
  "orgId":       "org-aaaa-...",
  "objectType":  "VENUE",
  "searchBoost": 1.2,
  "data": { ... updated data including price: 950 ... }
}
```

---

### 4.9 View Change History for a Record

```
GET http://localhost:8085/api/v1/change-sets/record/rec-bbbb-...
X-Org-Id:     org-aaaa-...
X-User-Roles: VENDOR
```

---

## Phase 5 — Admin Management

---

### 5.1 Upgrade Vendor to ENHANCED Tier

```
GET http://localhost:8090/api/v1/tier-rules/check
    ?recordId=rec-bbbb-...&targetTier=ENHANCED&listingType=VENDOR
X-User-Roles: ADMIN
```

If all rules satisfied:
```
PUT http://localhost:8080/api/v1/records/rec-bbbb-.../verification
X-User-Roles: ADMIN

{
  "tier":      "ENHANCED",
  "expiresAt": "2027-07-01T00:00:00Z",
  "notes":     "GST certificate verified. 15 completed bookings confirmed. Upgraded to ENHANCED."
}
```

---

### 5.2 Revoke Verification (Downgrade to NONE)

```
POST http://localhost:8080/api/v1/records/rec-bbbb-.../verification/revoke
X-User-Roles: ADMIN

{ "reason": "GST registration lapsed. Vendor must renew and re-upload certificate." }
```

---

### 5.3 Suspend Vendor

```
PATCH http://localhost:8107/api/v1/vendors/org-aaaa-.../status
X-User-Roles: ADMIN

{ "status": "SUSPENDED" }
```

---

### 5.4 View Vendor Workflow Transition History

```
GET http://localhost:8085/api/v1/records/rec-bbbb-.../workflow/history?page=0&size=20
X-User-Roles: ADMIN
```

**Response:**
```json
{
  "content": [
    { "fromState": "SUBMITTED", "toState": "ACTIVE",     "trigger": "APPROVE", "transitionedAt": "2026-07-01T11:15:00Z" },
    { "fromState": "DRAFT",     "toState": "SUBMITTED",  "trigger": "SUBMIT",  "transitionedAt": "2026-07-01T10:30:00Z" }
  ],
  "totalElements": 2
}
```

---

### 5.5 Unpublish Venue

```
POST http://localhost:8108/api/v1/listings/rec-cccc-.../unpublish
X-User-Roles: ADMIN
```

**Response `204 No Content`**

---

### 5.6 Reindex Venue Search Index

Use after schema changes or bulk data corrections.

```
POST http://localhost:8082/admin/reindex/VENUE
X-User-Roles: ADMIN
```

**Response `202`:**
```json
{ "status": "REINDEX_STARTED", "objectType": "VENUE", "orgId": "org-aaaa-..." }
```

---

### 5.7 View Listing Workflow History

```
GET http://localhost:8085/api/v1/records/rec-cccc-.../workflow/history
X-User-Roles: ADMIN
```

---

### 5.8 Get Full Vendor Profile (Admin View)

```
GET http://localhost:8107/api/v1/vendors/org-aaaa-...
X-User-Roles: ADMIN
```

---

## Flow Summary — State Machines

### Vendor States
```
DRAFT ──SUBMIT──▶ SUBMITTED ──APPROVE──▶ ACTIVE ──SUSPEND──▶ SUSPENDED
                       │                    │
                  REJECT▼              WITHDRAW▼
                  REJECTED             DRAFT (re-edit)
```

### Venue Listing States
```
DRAFT ──SUBMIT──▶ PENDING_REVIEW ──APPROVE──▶ ACTIVE ──UNPUBLISH──▶ INACTIVE
                        │                                                │
               REQUEST_CHANGES▼                                   RESUBMIT▼
                       DRAFT                                   PENDING_REVIEW
                        │
                   REJECT▼
                   REJECTED (terminal)
```

### Document States
```
UPLOADED ──review──▶ UNDER_REVIEW ──verify──▶ VERIFIED
                           │
                      reject▼
                      REJECTED ──(re-upload)──▶ UPLOADED
```

---

## Complete API Reference

| # | Method | URL | Service | Actor |
|---|--------|-----|---------|-------|
| 0.1 | POST | `:8090/api/v1/fields` | schema-registry | ADMIN |
| 0.2 | POST | `:8090/api/v1/field-groups` | schema-registry | ADMIN |
| 0.3 | POST | `:8090/api/v1/listing-types` | schema-registry | ADMIN |
| 0.4 | POST | `:8090/api/v1/listing-types` | schema-registry | ADMIN |
| 0.5 | POST | `:8085/api/v1/workflow-definitions` | workflow-service | ADMIN |
| 0.5 | POST | `:8085/api/v1/workflow-definitions/{id}/states` | workflow-service | ADMIN |
| 0.5 | POST | `:8085/api/v1/workflow-definitions/{id}/transitions` | workflow-service | ADMIN |
| 0.7 | POST | `:8090/api/v1/tier-configs` | schema-registry | ADMIN |
| 0.8 | POST | `:8090/api/v1/tier-rules` | schema-registry | ADMIN |
| 0.9 | POST | `:8090/api/v1/document-requirements` | schema-registry | ADMIN |
| 0.10 | POST | `:8090/api/v1/search-definitions` | schema-registry | ADMIN |
| 0.11 | POST | `:8090/api/v1/listing-types/{name}/publish` | schema-registry | ADMIN |
| 1.1 | POST | `:8107/api/v1/vendors/register` | vendor-service | VENDOR |
| 1.2 | GET | `:8090/api/v1/listing-types/VENDOR/schema` | schema-registry | VENDOR |
| 1.3–1.7 | PATCH | `:8080/api/v1/records/{recordId}` | record-service | VENDOR |
| 1.8–1.10 | POST | `:8081/api/v1/documents` | document-service | VENDOR |
| 1.11 | GET | `:8107/api/v1/vendors/me/kyc` | vendor-service | VENDOR |
| 1.12 | GET | `:8090/api/v1/tier-rules/check` | schema-registry | VENDOR |
| 1.13 | POST | `:8107/api/v1/vendors/me/submit` | vendor-service | VENDOR |
| 1.14 | GET | `:8107/api/v1/vendors?status=SUBMITTED` | vendor-service | ADMIN |
| 1.15 | GET | `:8081/api/v1/documents/pending-review` | document-service | ADMIN |
| 1.16 | POST | `:8081/api/v1/documents/{id}/review` | document-service | ADMIN |
| 1.17 | POST | `:8081/api/v1/documents/{id}/verify` | document-service | ADMIN |
| 1.18 | GET | `:8085/api/v1/approvals/pending` | workflow-service | ADMIN |
| 1.19 | POST | `:8085/api/v1/approvals/{id}/decide` | workflow-service | ADMIN |
| 1.20 | PUT | `:8080/api/v1/records/{recordId}/verification` | record-service | ADMIN |
| 2.1 | GET | `:8090/api/v1/listing-types/VENUE/schema` | schema-registry | VENDOR |
| 2.2 | POST | `:8080/api/v1/records` | record-service | VENDOR |
| 2.3–2.7 | PATCH | `:8080/api/v1/records/{listingId}` | record-service | VENDOR |
| 2.8 | POST | `:8080/api/v1/records/{listingId}/files/{field}` | record-service | VENDOR |
| 2.9 | POST | `:8080/api/v1/records/{listingId}/relationships` | record-service | VENDOR |
| 2.10 | PUT | `:8108/api/v1/listings/{listingId}/availability` | listing-service | VENDOR |
| 2.11 | GET | `:8085/api/v1/records/{listingId}/workflow` | workflow-service | VENDOR |
| 2.12 | POST | `:8080/api/v1/records/{listingId}/status` | record-service | VENDOR |
| 2.13 | GET | `:8080/api/v1/records/{listingId}` | record-service | ADMIN |
| 2.14 | POST | `:8085/api/v1/approvals/{id}/decide` | workflow-service | ADMIN |
| 2.16 | POST | `:8108/api/v1/listings/{listingId}/publish` | listing-service | ADMIN |
| 3.1–3.2 | POST | `:8082/api/v1/search` | search-service | PUBLIC |
| 3.3 | GET | `:8108/api/v1/listings/{listingId}/snapshot` | listing-service | PUBLIC |
| 3.4 | GET | `:8108/api/v1/listings/{listingId}/availability` | listing-service | PUBLIC |
| 3.5 | GET | `:8082/api/v1/search/suggest` | search-service | PUBLIC |
| 4.1 | POST | `:8085/api/v1/change-sets` | workflow-service | VENDOR |
| 4.3 | POST | `:8085/api/v1/change-sets/{id}/withdraw` | workflow-service | VENDOR |
| 4.4 | GET | `:8085/api/v1/change-sets/pending` | workflow-service | ADMIN |
| 4.5 | POST | `:8085/api/v1/change-sets/{id}/review` | workflow-service | ADMIN |
| 5.1 | PUT | `:8080/api/v1/records/{recordId}/verification` | record-service | ADMIN |
| 5.2 | POST | `:8080/api/v1/records/{recordId}/verification/revoke` | record-service | ADMIN |
| 5.3 | PATCH | `:8107/api/v1/vendors/{orgId}/status` | vendor-service | ADMIN |
| 5.5 | POST | `:8108/api/v1/listings/{listingId}/unpublish` | listing-service | ADMIN |
| 5.6 | POST | `:8082/admin/reindex/{objectType}` | search-service | ADMIN |
