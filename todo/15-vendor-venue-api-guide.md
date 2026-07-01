# Vendor & Venue API Guide — lagu-platform

How to achieve the same end-to-end flows as `vendor-management` using lagu-platform's service mesh.  
All examples use `localhost` ports from `application-loc.yml`.

---

## Service Port Map

| Service           | Local Port | Base Path                        |
|-------------------|-----------|----------------------------------|
| record-service    | 8080      | `/api/v1/records`                |
| document-service  | 8081      | `/api/v1/documents`              |
| search-service    | 8082      | `/api/v1/search`                 |
| metadata-service  | 8083      | `/api/v1/metadata`               |
| workflow-service  | 8085      | `/api/v1/change-sets`, `/api/v1/approvals`, `/api/v1/records/{id}/workflow` |
| schema-registry   | 8090      | `/api/v1/listing-types`, `/api/v1/tier-rules` |
| vendor-service    | 8107      | `/api/v1/vendors`                |
| listing-service   | 8108      | `/api/v1/listings`               |

**Auth headers** (injected by gateway in production; pass manually for local testing):
```
X-User-Id:    <uuid>      # authenticated user
X-Org-Id:     <uuid>      # vendor org (set after registration)
X-User-Roles: VENDOR      # or ADMIN
```

---

## Part 1 — Vendor Onboarding

Mirrors `POST /api/v1/vendors/register` → KYC fill → submit → admin approve in vendor-management.

---

### Step 1.1 — Register Vendor Org

Creates the org, the VENDOR record (status=DRAFT), and binds the calling user to that org.

```
POST http://localhost:8107/api/v1/vendors/register
X-User-Id: a1b2c3d4-0000-0000-0000-000000000001
X-User-Roles: VENDOR

{
  "businessName": "Royal Gardens Events",
  "country": "IN",
  "primaryVendorType": "VENUE"
}
```

**Response `201`:**
```json
{
  "orgId":      "aaaa-0001-...",
  "recordId":   "bbbb-0002-...",
  "businessName": "Royal Gardens Events",
  "status":     "DRAFT",
  "country":    "IN",
  "createdAt":  "2026-07-01T10:00:00Z"
}
```

> **Save `orgId` and `recordId`** — used in every subsequent call.

---

### Step 1.2 — Fill Business Profile (PATCH record fields)

Vendor fills their profile progressively. Each PATCH merges into the JSONB `data` blob.  
Fields are defined by the `VENDOR` listing type schema in schema-registry.

```
PATCH http://localhost:8080/api/v1/records/{recordId}
X-User-Id: a1b2c3d4-...
X-Org-Id:  aaaa-0001-...
X-User-Roles: VENDOR

{
  "businessType":     "SOLE_PROPRIETOR",
  "yearEstablished":  2015,
  "about":            "Premium venue and event management in Mumbai.",
  "logoUrl":          "https://cdn.example.com/logos/rge.png"
}
```

**Equivalent vendor-management call:**  
`PATCH /api/v1/vendors/{id}/business-identity`

---

### Step 1.3 — Add Contact Info

```
PATCH http://localhost:8080/api/v1/records/{recordId}
X-Org-Id:  aaaa-0001-...
X-User-Roles: VENDOR

{
  "primaryEmail":      "bookings@royalgardens.in",
  "primaryPhone":      "+91-9876543210",
  "whatsappNumber":    "+91-9876543210",
  "websiteUrl":        "https://royalgardens.in",
  "instagramHandle":   "@royalgardens",
  "facebookPageUrl":   "https://facebook.com/royalgardens"
}
```

**Equivalent vendor-management call:**  
`PATCH /api/v1/vendors/{id}/contact`

---

### Step 1.4 — Add Business Address

```
PATCH http://localhost:8080/api/v1/records/{recordId}
X-Org-Id:  aaaa-0001-...
X-User-Roles: VENDOR

{
  "addressLine1":  "123 Garden Road, Andheri West",
  "addressLine2":  "Near Metro Station",
  "landmark":      "Opposite City Mall",
  "city":          "Mumbai",
  "state":         "Maharashtra",
  "pinCode":       "400053",
  "latitude":      19.1364,
  "longitude":     72.8296
}
```

**Equivalent vendor-management call:**  
`POST /api/v1/vendors/{id}/addresses` with `addressType: REGISTERED`

---

### Step 1.5 — Add Tax Info

```
PATCH http://localhost:8080/api/v1/records/{recordId}
X-Org-Id:  aaaa-0001-...

{
  "panNumber":          "ABCDE1234F",
  "gstStatus":          "REGISTERED",
  "gstin":              "27ABCDE1234F1Z5",
  "fssaiLicenceNumber": "21423026000441"
}
```

**Equivalent vendor-management call:**  
`PATCH /api/v1/vendors/{id}/tax-info`

---

### Step 1.6 — Add Bank Info

```
PATCH http://localhost:8080/api/v1/records/{recordId}
X-Org-Id:  aaaa-0001-...

{
  "accountHolderName": "Royal Gardens Events",
  "bankName":          "HDFC Bank",
  "accountNumber":     "50100123456789",
  "ifscCode":          "HDFC0001234",
  "accountType":       "CURRENT",
  "upiId":             "royalgardens@hdfcbank"
}
```

**Equivalent vendor-management call:**  
`PATCH /api/v1/vendors/{id}/bank-info`

---

### Step 1.7 — Upload KYC Documents

Upload each mandatory document separately via multipart form. Documents required for BASIC tier are seeded in schema-registry (`document_requirement` table for `VENDOR`).

**PAN Card:**
```
POST http://localhost:8081/api/v1/documents
X-User-Id:  a1b2c3d4-...
X-Org-Id:   aaaa-0001-...
X-User-Roles: VENDOR
Content-Type: multipart/form-data

file:           <binary — PAN card scan>
documentType:   IDENTITY_PROOF
identitySubType: PAN_CARD
```

**GST Certificate:**
```
POST http://localhost:8081/api/v1/documents
Content-Type: multipart/form-data

file:         <binary — GST registration certificate>
documentType: OTHER
```

**Bank Proof (cancelled cheque or passbook):**
```
POST http://localhost:8081/api/v1/documents
Content-Type: multipart/form-data

file:         <binary>
documentType: OTHER
expiryDate:   2030-12-31
```

**Response `201` for each:**
```json
{
  "id":           "dddd-0004-...",
  "documentType": "IDENTITY_PROOF",
  "subType":      "PAN_CARD",
  "status":       "UPLOADED",
  "fileUrl":      "https://cdn.example.com/docs/pan.jpg",
  "uploadedAt":   "2026-07-01T10:15:00Z"
}
```

**Equivalent vendor-management call:**  
`POST /api/v1/vendors/{id}/documents` with `documentType: PAN_CARD`

---

### Step 1.8 — Check KYC Readiness

Before submitting, verify all checklist items are green.

```
GET http://localhost:8107/api/v1/vendors/me/kyc
X-User-Id:    a1b2c3d4-...
X-Org-Id:     aaaa-0001-...
X-User-Roles: VENDOR
```

**Response:**
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

**Equivalent vendor-management call:**  
`GET /api/v1/vendors/{id}/readiness`

---

### Step 1.9 — (Optional) Check Tier Eligibility

Check whether the vendor meets BASIC tier rules before submitting.

```
GET http://localhost:8090/api/v1/tier-rules/check
    ?recordId=bbbb-0002-...
    &targetTier=BASIC
    &listingType=VENDOR
X-User-Id: a1b2c3d4-...
```

**Response:**
```json
{
  "recordId":   "bbbb-0002-...",
  "targetTier": "BASIC",
  "satisfied":  true,
  "rules": [
    { "ruleType": "DOCUMENT_VERIFIED", "documentCode": "PAN_CARD",   "satisfied": true  },
    { "ruleType": "FIELD_CONDITION",   "fieldPath":    "panNumber",   "satisfied": true  },
    { "ruleType": "DOCUMENT_VERIFIED", "documentCode": "BANK_PROOF",  "satisfied": false }
  ]
}
```

**Equivalent vendor-management call:**  
`GET /api/v1/vendors/{id}/verification/tier-check?targetTier=BASIC`

---

### Step 1.10 — Submit Vendor for Admin Review

Once `kycReady: true`, vendor submits their profile. Status transitions from `DRAFT → SUBMITTED`. A workflow approval instance is created automatically.

```
POST http://localhost:8107/api/v1/vendors/me/submit
X-User-Id:    a1b2c3d4-...
X-Org-Id:     aaaa-0001-...
X-User-Roles: VENDOR
```

**Response:**
```json
{
  "orgId":    "aaaa-0001-...",
  "recordId": "bbbb-0002-...",
  "status":   "SUBMITTED"
}
```

**Equivalent vendor-management call:**  
`POST /api/v1/vendors/{id}/submit`

---

### Step 1.11 — Admin: List Submitted Vendors

```
GET http://localhost:8107/api/v1/vendors?status=SUBMITTED
X-User-Id:    cccc-9999-...
X-User-Roles: ADMIN
```

**Equivalent vendor-management call:**  
`GET /api/v1/vendors?verificationStatus=PENDING_REVIEW`

---

### Step 1.12 — Admin: Review Documents

Before approving the vendor, admin reviews and verifies each uploaded document.

```
POST http://localhost:8081/api/v1/documents/{documentId}/review
X-User-Roles: ADMIN
```

```
POST http://localhost:8081/api/v1/documents/{documentId}/verify
X-User-Roles: ADMIN
```

Or reject with reason:
```
POST http://localhost:8081/api/v1/documents/{documentId}/reject
X-User-Roles: ADMIN

{ "rejectionReason": "PAN card image is blurry — please re-upload" }
```

**Equivalent vendor-management call:**  
`PATCH /api/v1/vendors/{id}/documents/{documentId}/verify`

---

### Step 1.13 — Admin: Get Pending Approvals

```
GET http://localhost:8085/api/v1/approvals/pending
X-User-Roles: ADMIN
```

**Response:**
```json
[
  {
    "id":         "eeee-0005-...",
    "recordId":   "bbbb-0002-...",
    "objectType": "VENDOR",
    "step":       "KYC_REVIEW",
    "assignedTo": ["ADMIN"],
    "createdAt":  "2026-07-01T10:30:00Z"
  }
]
```

---

### Step 1.14 — Admin: Approve Vendor

```
POST http://localhost:8085/api/v1/approvals/{approvalId}/decide
X-User-Id:    cccc-9999-...
X-User-Roles: ADMIN

{
  "decision": "APPROVE",
  "comment":  "All KYC documents verified. Vendor profile complete."
}
```

OR directly update status via vendor-service:
```
PATCH http://localhost:8107/api/v1/vendors/{orgId}/status
X-User-Id:    cccc-9999-...
X-User-Roles: ADMIN

{ "status": "ACTIVE" }
```

**Equivalent vendor-management call:**  
`PATCH /api/v1/vendors/{id}/status` with `{ "status": "ACTIVE" }`

---

### Step 1.15 — Admin: Set Verification Tier

After approval, set the vendor's initial verification tier.

```
PUT http://localhost:8080/api/v1/records/{recordId}/verification
X-User-Roles: ADMIN

{
  "tier":      "BASIC",
  "expiresAt": "2027-07-01T00:00:00Z",
  "notes":     "PAN + bank proof verified. GST registration confirmed."
}
```

**Equivalent vendor-management call:**  
`PATCH /api/v1/vendors/{id}/verification/tier` (upgrade to BASIC)

---

### Step 1.16 — (Optional) Add Team Members

Vendor can add colleagues as team members after org is ACTIVE.

```
POST http://localhost:8080/api/v1/records
X-Org-Id:     aaaa-0001-...
X-User-Roles: VENDOR

{
  "objectType": "VENDOR_USER",
  "data": {
    "userId":   "ffff-0006-...",
    "role":     "MANAGER",
    "email":    "manager@royalgardens.in"
  }
}
```

**Equivalent vendor-management call:**  
`POST /api/v1/vendors/{vendorId}/users`

---

## Part 2 — Venue Listing Creation

Mirrors the full `POST /api/v1/venues` → section updates → submit → publish flow in venue-service.

---

### Step 2.1 — Inspect the VENUE Schema

Fetch the field definitions and section layout before creating the record.  
This is the no-code equivalent of the static venue MongoDB document structure.

```
GET http://localhost:8090/api/v1/listing-types/VENUE/schema
```

**Response excerpt:**
```json
{
  "listingType": "VENUE",
  "version": 1,
  "sections": [
    {
      "sectionKey": "basics",
      "label":      "Listing Basics",
      "displayOrder": 1,
      "fields": [
        { "name": "listingName",      "fieldType": "TEXT",       "required": true,  "promoted": true  },
        { "name": "tagline",          "fieldType": "TEXT",       "required": false                     },
        { "name": "description",      "fieldType": "RICH_TEXT",  "required": false                     },
        { "name": "eventsServed",     "fieldType": "MULTI_ENUM", "enumValues": ["WEDDING","RECEPTION","BIRTHDAY","CORPORATE","SANGEET"] },
        { "name": "yearsOfExperience","fieldType": "INTEGER"                                            }
      ]
    },
    {
      "sectionKey": "venue_identity",
      "label":      "Venue Identity & Location",
      "fields": [
        { "name": "venueTypes",   "fieldType": "MULTI_ENUM", "enumValues": ["BANQUET_HALL","OUTDOOR_LAWN","ROOFTOP","FARMHOUSE"] },
        { "name": "addressLine1", "fieldType": "TEXT",       "required": true,  "promoted": true },
        { "name": "city",         "fieldType": "TEXT",       "required": true,  "promoted": true, "filterable": true },
        { "name": "state",        "fieldType": "TEXT",       "required": true                    },
        { "name": "pinCode",      "fieldType": "TEXT",       "required": true                    },
        { "name": "latitude",     "fieldType": "DECIMAL"                                         },
        { "name": "longitude",    "fieldType": "DECIMAL"                                         }
      ]
    },
    {
      "sectionKey": "capacity",
      "label":      "Capacity & Spaces",
      "fields": [
        { "name": "totalGuestCapacity", "fieldType": "INTEGER", "promoted": true, "rangeFilterable": true },
        { "name": "minBookingCapacity", "fieldType": "INTEGER"                                            },
        { "name": "diningCapacity",     "fieldType": "INTEGER"                                            },
        { "name": "indoorOutdoor",      "fieldType": "ENUM",    "enumValues": ["INDOOR","OUTDOOR","BOTH"], "promoted": true },
        { "name": "acAvailable",        "fieldType": "BOOLEAN"                                            },
        { "name": "carParkingCapacity", "fieldType": "INTEGER"                                            },
        { "name": "valetParking",       "fieldType": "BOOLEAN"                                            },
        { "name": "halls",              "fieldType": "ARRAY_OF_OBJECTS", "arrayManageable": true,
          "itemSchema": [
            { "name": "name",        "fieldType": "TEXT",    "required": true },
            { "name": "capacity",    "fieldType": "INTEGER"                   },
            { "name": "acAvailable", "fieldType": "BOOLEAN"                  },
            { "name": "indoorSpace", "fieldType": "BOOLEAN"                  }
          ]
        }
      ]
    }
  ]
}
```

---

### Step 2.2 — Create Venue Record (Draft)

Create with the minimum required basics section only. Additional sections are PATCHed in subsequent steps.

```
POST http://localhost:8080/api/v1/records
X-User-Id:    a1b2c3d4-...
X-Org-Id:     aaaa-0001-...
X-User-Roles: VENDOR

{
  "objectType": "VENUE",
  "status":     "DRAFT",
  "data": {
    "listingName":      "Royal Gardens Banquet Hall",
    "tagline":          "Where Dreams Become Celebrations",
    "description":      "A premium 500-pax banquet hall with indoor and outdoor spaces.",
    "eventsServed":     ["WEDDING", "RECEPTION", "SANGEET", "BIRTHDAY"],
    "yearsOfExperience": 12
  }
}
```

**Response `201`:**
```json
{
  "id":         "1111-list-...",
  "objectType": "VENUE",
  "orgId":      "aaaa-0001-...",
  "status":     "DRAFT",
  "data": { "listingName": "Royal Gardens Banquet Hall", ... },
  "createdAt":  "2026-07-01T11:00:00Z"
}
```

> Save `id` as `{listingId}`.

**Equivalent venue-service call:**  
`POST /api/v1/venues` with `{ vendorId, basics: { listingName, ... } }`

---

### Step 2.3 — Update Venue Identity & Location

```
PATCH http://localhost:8080/api/v1/records/{listingId}
X-Org-Id:     aaaa-0001-...
X-User-Roles: VENDOR

{
  "venueTypes":    ["BANQUET_HALL", "OUTDOOR_LAWN"],
  "addressLine1":  "123 Garden Road, Andheri West",
  "addressLine2":  "Next to City Mall",
  "landmark":      "Opposite Andheri Station",
  "city":          "Mumbai",
  "state":         "Maharashtra",
  "pinCode":       "400053",
  "latitude":      19.1364,
  "longitude":     72.8296,
  "googleMapsLink": "https://maps.google.com/?q=19.1364,72.8296",
  "nearestMetro":  "Andheri (300m)"
}
```

**Equivalent venue-service call:**  
`PATCH /api/v1/venues/{id}/identity`

---

### Step 2.4 — Update Capacity & Spaces

```
PATCH http://localhost:8080/api/v1/records/{listingId}
X-Org-Id:     aaaa-0001-...

{
  "totalGuestCapacity": 500,
  "minBookingCapacity":  50,
  "floatingCapacity":   100,
  "diningCapacity":     400,
  "indoorOutdoor":      "BOTH",
  "acAvailable":        true,
  "carParkingCapacity": 150,
  "valetParking":       true
}
```

**Equivalent venue-service call:**  
`PATCH /api/v1/venues/{id}/capacity`

---

### Step 2.5 — Add Hall Spaces (Array-of-Objects Field)

The `halls` field is `ARRAY_OF_OBJECTS` with `arrayManageable: true`.

```
PATCH http://localhost:8080/api/v1/records/{listingId}
X-Org-Id:     aaaa-0001-...

{
  "halls": [
    { "name": "Grand Ballroom", "capacity": 300, "acAvailable": true,  "indoorSpace": true  },
    { "name": "Lawn & Garden",  "capacity": 200, "acAvailable": false, "indoorSpace": false },
    { "name": "Rooftop Lounge", "capacity": 80,  "acAvailable": false, "indoorSpace": false }
  ]
}
```

**Equivalent venue-service calls:**  
`POST /api/v1/venues/{id}/halls` (one per hall)  
`PATCH /api/v1/venues/{id}/halls/{hallId}`

---

### Step 2.6 — Update Contact Info

```
PATCH http://localhost:8080/api/v1/records/{listingId}
X-Org-Id:     aaaa-0001-...

{
  "bookingEmail":  "bookings@royalgardens.in",
  "bookingPhone1": "+91-9876543210",
  "bookingPhone2": "+91-9123456789"
}
```

**Equivalent venue-service call:**  
`PATCH /api/v1/venues/{id}/contact`

---

### Step 2.7 — Update Slot Pricing

```
PATCH http://localhost:8080/api/v1/records/{listingId}
X-Org-Id:     aaaa-0001-...

{
  "pricingModel":      "PER_PLATE",
  "startingPrice":     800,
  "morningSlotPrice":  250000,
  "morningSlotTiming": "07:00 - 13:00",
  "eveningSlotPrice":  350000,
  "eveningSlotTiming": "18:00 - 23:00",
  "fullDayPrice":      550000,
  "perPlateVeg":       800,
  "perPlateNonVeg":    1000,
  "advancePercentage": 30,
  "securityDeposit":   50000
}
```

**Equivalent venue-service calls:**  
`PATCH /api/v1/venues/{id}/pricing` + `PATCH /api/v1/venues/{id}/slot-pricing`

---

### Step 2.8 — Update Catering Policy

```
PATCH http://localhost:8080/api/v1/records/{listingId}
X-Org-Id:     aaaa-0001-...

{
  "inHouseCateringAvailable":     true,
  "outsideCatererAllowed":        false,
  "outsideCatererChargesPerPlate": 200,
  "vegNonVegPolicy":              "VEG_NONVEG_BOTH",
  "alcoholPolicy":                "ALLOWED_WITH_PERMIT",
  "barSetupAvailable":            true,
  "cateringNotes":                "Min 100 plates for in-house catering"
}
```

**Equivalent venue-service call:**  
`PATCH /api/v1/venues/{id}/catering`

---

### Step 2.9 — Update Amenities

```
PATCH http://localhost:8080/api/v1/records/{listingId}
X-Org-Id:     aaaa-0001-...

{
  "amenityFeatures": [
    "AC", "GENERATOR_BACKUP", "DECORATION_SERVICES",
    "VALET_PARKING", "WIFI", "PROJECTOR_SCREEN", "STAGE"
  ],
  "restroomsCount":                 12,
  "separateLadiesGentsRestrooms":   true,
  "guestRooms":                     5,
  "otherAmenities":                 "Bridal suite, changing room, DJ podium"
}
```

**Equivalent venue-service call:**  
`PATCH /api/v1/venues/{id}/amenities`

---

### Step 2.10 — Update Policies & Timings

```
PATCH http://localhost:8080/api/v1/records/{listingId}
X-Org-Id:     aaaa-0001-...

{
  "openingTime":               "07:00",
  "closingTime":               "23:30",
  "musicDjCutoffTime":         "22:30",
  "outsideDecoratorAllowed":   true,
  "outsideVendorPolicy":       "Decorators must coordinate with in-house team",
  "houseRules":                "No confetti, no open flames inside",
  "fireNocAvailable":          true,
  "policePermissionAvailable": true,
  "exciseLicenceAvailable":    true,
  "floorPlanUrl":              "https://cdn.example.com/floorplans/rge.pdf"
}
```

**Equivalent venue-service call:**  
`PATCH /api/v1/venues/{id}/policies`

---

### Step 2.11 — Update Service Area & Fulfillment

```
PATCH http://localhost:8080/api/v1/records/{listingId}
X-Org-Id:     aaaa-0001-...

{
  "serviceCities":      ["Mumbai", "Thane", "Navi Mumbai"],
  "outstationPolicy":   "ON_REQUEST",
  "fulfillmentMode":    "VENUE_ONLY",
  "maxBookingsPerDay":  2,
  "minAdvanceBookingDays": 30
}
```

**Equivalent venue-service calls:**  
`PATCH /api/v1/venues/{id}/service-area` + `PATCH /api/v1/venues/{id}/fulfillment` + `PATCH /api/v1/venues/{id}/availability`

---

### Step 2.12 — Upload Cover Photo & Portfolio Media

```
POST http://localhost:8080/api/v1/records/{listingId}/files/coverPhotoUrl
X-Org-Id:     aaaa-0001-...
X-User-Roles: VENDOR
Content-Type: multipart/form-data

file: <binary — JPG/PNG, max 5MB>
```

Upload portfolio images (field is a list of URLs):
```
POST http://localhost:8080/api/v1/records/{listingId}/files/portfolioMedia
Content-Type: multipart/form-data

file: <binary>
```

**Equivalent venue-service call:**  
`PATCH /api/v1/venues/{id}/media`

---

### Step 2.13 — Set Availability Calendar

```
PUT http://localhost:8108/api/v1/listings/{listingId}/availability
X-Org-Id:     aaaa-0001-...
X-User-Roles: VENDOR

{
  "from":     "2026-08-01",
  "to":       "2026-12-31",
  "slotType": "AVAILABLE"
}
```

Block specific dates (repeat for each blocked range):
```
PUT http://localhost:8108/api/v1/listings/{listingId}/availability
X-Org-Id:     aaaa-0001-...

{
  "from":     "2026-10-02",
  "to":       "2026-10-02",
  "slotType": "BLOCKED"
}
```

**Equivalent venue-service call:**  
`PATCH /api/v1/venues/{id}/availability`

---

### Step 2.14 — Check Workflow Status

Verify the listing's current state and which triggers are valid.

```
GET http://localhost:8085/api/v1/records/{listingId}/workflow
X-Org-Id:     aaaa-0001-...
X-User-Roles: VENDOR
```

**Response:**
```json
{
  "recordId":          "1111-list-...",
  "currentState":      "DRAFT",
  "availableTriggers": ["SUBMIT"],
  "pendingApprovals":  []
}
```

**Equivalent venue-service call:**  
`GET /api/v1/venues/{id}` → check `status` field

---

### Step 2.15 — Submit Venue for Admin Review

```
POST http://localhost:8080/api/v1/records/{listingId}/status
X-Org-Id:     aaaa-0001-...
X-User-Roles: VENDOR

{
  "trigger":  "SUBMIT",
  "comment":  "Venue profile complete. Ready for review."
}
```

**Response:**
```json
{
  "id":     "1111-list-...",
  "status": "PENDING_REVIEW"
}
```

**Equivalent venue-service call:**  
`POST /api/v1/venues/{id}/submit`

---

### Step 2.16 — Admin: View Pending Venue Approvals

```
GET http://localhost:8085/api/v1/approvals/pending
X-User-Roles: ADMIN
```

Response includes approval instances with `objectType: "VENUE"`.

---

### Step 2.17 — Admin: Approve Venue Listing

```
POST http://localhost:8085/api/v1/approvals/{approvalId}/decide
X-User-Id:    cccc-9999-...
X-User-Roles: ADMIN

{
  "decision": "APPROVE",
  "comment":  "Venue details verified. Address confirmed. Capacity checked."
}
```

Listing status transitions to `ACTIVE`.

**Equivalent venue-service call:**  
`PATCH /api/v1/venues/{id}/status` with `{ "status": "ACTIVE" }`

---

### Step 2.18 — Publish Listing to Consumer Search Index

After approval, publish a snapshot to make the venue discoverable by consumers.

```
POST http://localhost:8108/api/v1/listings/{listingId}/publish
X-User-Roles: ADMIN

{
  "orgId":             "aaaa-0001-...",
  "objectType":        "VENUE",
  "verificationTier":  "BASIC",
  "searchBoost":       1.0,
  "data": {
    "listingName":      "Royal Gardens Banquet Hall",
    "city":             "Mumbai",
    "totalGuestCapacity": 500,
    "indoorOutdoor":    "BOTH",
    "startingPrice":    800,
    "venueTypes":       ["BANQUET_HALL", "OUTDOOR_LAWN"],
    "eventsServed":     ["WEDDING", "RECEPTION", "SANGEET"]
  }
}
```

**Equivalent:** In venue-service, admin `PATCH /api/v1/venues/{id}/status` → status=ACTIVE automatically exposes to search.

---

### Step 2.19 — Consumer Searches for Venues

```
GET http://localhost:8108/api/v1/listings/search?objectType=VENUE&page=0&size=20
```

Or via search-service with facets:
```
GET http://localhost:8082/api/v1/search
    ?objectType=VENUE
    &city=Mumbai
    &indoorOutdoor=BOTH
    &minCapacity=200
    &maxCapacity=600
    &page=0&size=20
```

**Equivalent venue-service call:**  
`GET /api/v1/venues?city=Mumbai&minCapacity=200&maxCapacity=600`

---

## Part 3 — Post-Activation: Change Requests

When a vendor is ACTIVE, profile changes go through the change-set workflow (equivalent to `VendorChangeRequest` queue in vendor-management).

### Step 3.1 — Vendor Submits a Change

```
POST http://localhost:8085/api/v1/change-sets
X-User-Id:    a1b2c3d4-...
X-Org-Id:     aaaa-0001-...
X-User-Roles: VENDOR

{
  "recordId":   "bbbb-0002-...",
  "orgId":      "aaaa-0001-...",
  "objectType": "VENDOR",
  "workflowId": null,
  "originalData": {
    "primaryPhone": "+91-9876543210"
  },
  "proposedData": {
    "primaryPhone": "+91-9000000001"
  }
}
```

**Response `201`:**
```json
{
  "id":     "cs-0001-...",
  "status": "PENDING",
  "submittedAt": "2026-07-01T12:00:00Z"
}
```

**Equivalent vendor-management call:**  
`PATCH /api/v1/vendors/{id}/contact` when vendor status=ACTIVE → auto-queued as VendorChangeRequest

---

### Step 3.2 — Admin Reviews Change Set

```
GET http://localhost:8085/api/v1/change-sets/pending
X-User-Roles: ADMIN
```

```
POST http://localhost:8085/api/v1/change-sets/{changeSetId}/review
X-User-Id:    cccc-9999-...
X-User-Roles: ADMIN

{
  "decision":      "APPROVE",
  "adminComment":  "Phone number change verified.",
  "correctedData": null
}
```

Approve with correction (admin overrides one field):
```json
{
  "decision":     "APPROVE",
  "correctedData": { "primaryPhone": "+91-9000000001" }
}
```

**Equivalent vendor-management call:**  
`PATCH /api/v1/vendors/admin/change-requests/vendors/{requestId}/review`

---

### Step 3.3 — Vendor Withdraws a Pending Change

```
POST http://localhost:8085/api/v1/change-sets/{changeSetId}/withdraw
X-User-Id:    a1b2c3d4-...
X-Org-Id:     aaaa-0001-...
X-User-Roles: VENDOR
```

**Equivalent vendor-management call:**  
`POST /api/v1/vendors/{id}/withdraw`

---

## Part 4 — Admin: Vendor Tier Management

### Upgrade to ENHANCED Tier

Check eligibility first (Step 1.9 pattern), then set:

```
PUT http://localhost:8080/api/v1/records/{vendorRecordId}/verification
X-User-Roles: ADMIN

{
  "tier":      "ENHANCED",
  "expiresAt": "2027-07-01T00:00:00Z",
  "notes":     "GST registration + Udyam certificate verified. 12+ months active."
}
```

**Equivalent vendor-management call:**  
`PATCH /api/v1/vendors/{id}/verification/tier` with `{ "targetTier": "ENHANCED" }`

---

### Revoke Verification

```
POST http://localhost:8080/api/v1/records/{vendorRecordId}/verification/revoke
X-User-Roles: ADMIN

{ "reason": "GST registration lapsed" }
```

**Equivalent vendor-management call:**  
`PATCH /api/v1/vendors/{id}/verification/tier/downgrade`

---

### Suspend Vendor

```
PATCH http://localhost:8107/api/v1/vendors/{orgId}/status
X-User-Roles: ADMIN

{ "status": "SUSPENDED" }
```

**Equivalent vendor-management call:**  
`PATCH /api/v1/vendors/{id}/status` with `{ "status": "SUSPENDED" }`

---

## Part 5 — Admin: Venue Listing Management

### Feature a Venue

There is no dedicated feature flag endpoint yet in lagu-platform.  
Workaround: patch `searchBoost` via re-publish:

```
POST http://localhost:8108/api/v1/listings/{listingId}/publish
X-User-Roles: ADMIN

{
  "orgId": "aaaa-0001-...",
  "objectType": "VENUE",
  "searchBoost": 2.5,
  "data": { ... current data ... }
}
```

**Equivalent venue-service call:**  
`PATCH /api/v1/venues/{id}/featured` with `{ "featured": true }`

---

### Unpublish a Venue

```
POST http://localhost:8108/api/v1/listings/{listingId}/unpublish
X-User-Roles: ADMIN
```

**Equivalent venue-service call:**  
`PATCH /api/v1/venues/{id}/status` with `{ "status": "INACTIVE" }`

---

### View Listing Transition History

```
GET http://localhost:8085/api/v1/records/{listingId}/workflow/history?page=0&size=20
X-User-Roles: ADMIN
```

---

## Mapping Table: vendor-management → lagu-platform

| vendor-management Endpoint                              | lagu-platform Equivalent                                           |
|---------------------------------------------------------|--------------------------------------------------------------------|
| `POST /api/v1/vendors/register`                         | `POST :8107/api/v1/vendors/register`                               |
| `GET /api/v1/vendors/me`                                | `GET :8107/api/v1/vendors/me`                                      |
| `PATCH /api/v1/vendors/{id}/business-identity`          | `PATCH :8080/api/v1/records/{recordId}` (businessType, about, ...) |
| `PATCH /api/v1/vendors/{id}/contact`                    | `PATCH :8080/api/v1/records/{recordId}` (primaryEmail, phone, ...) |
| `PATCH /api/v1/vendors/{id}/tax-info`                   | `PATCH :8080/api/v1/records/{recordId}` (panNumber, gstin, ...)    |
| `PATCH /api/v1/vendors/{id}/bank-info`                  | `PATCH :8080/api/v1/records/{recordId}` (accountNumber, ifsc, ...) |
| `POST /api/v1/vendors/{id}/addresses`                   | `PATCH :8080/api/v1/records/{recordId}` (addressLine1, city, ...)  |
| `POST /api/v1/vendors/{id}/documents`                   | `POST :8081/api/v1/documents` (multipart)                          |
| `PATCH /api/v1/vendors/{id}/documents/{docId}/verify`   | `POST :8081/api/v1/documents/{id}/verify`                          |
| `GET /api/v1/vendors/{id}/readiness`                    | `GET :8107/api/v1/vendors/me/kyc`                                  |
| `POST /api/v1/vendors/{id}/submit`                      | `POST :8107/api/v1/vendors/me/submit`                              |
| `PATCH /api/v1/vendors/{id}/status`                     | `PATCH :8107/api/v1/vendors/{orgId}/status`                        |
| `PATCH /api/v1/vendors/{id}/verification/tier`          | `PUT :8080/api/v1/records/{recordId}/verification`                 |
| `GET /api/v1/vendors/{id}/verification/tier-check`      | `GET :8090/api/v1/tier-rules/check`                                |
| `POST /api/v1/vendors/{vendorId}/users`                 | `POST :8080/api/v1/records` (objectType: VENDOR_USER)              |
| `GET /api/v1/vendors/admin/change-requests/vendors`     | `GET :8085/api/v1/change-sets/pending`                             |
| `PATCH .../change-requests/vendors/{requestId}/review`  | `POST :8085/api/v1/change-sets/{id}/review`                        |
| `POST /api/v1/venues`                                   | `POST :8080/api/v1/records` (objectType: VENUE)                    |
| `PATCH /api/v1/venues/{id}/basics`                      | `PATCH :8080/api/v1/records/{listingId}` (listingName, ...)        |
| `PATCH /api/v1/venues/{id}/identity`                    | `PATCH :8080/api/v1/records/{listingId}` (venueTypes, address, ...) |
| `PATCH /api/v1/venues/{id}/capacity`                    | `PATCH :8080/api/v1/records/{listingId}` (totalGuestCapacity, ...) |
| `POST /api/v1/venues/{id}/halls`                        | `PATCH :8080/api/v1/records/{listingId}` (halls: [...])            |
| `PATCH /api/v1/venues/{id}/slot-pricing`                | `PATCH :8080/api/v1/records/{listingId}` (morningSlotPrice, ...)   |
| `PATCH /api/v1/venues/{id}/catering`                    | `PATCH :8080/api/v1/records/{listingId}` (inHouseCatering, ...)    |
| `PATCH /api/v1/venues/{id}/amenities`                   | `PATCH :8080/api/v1/records/{listingId}` (amenityFeatures, ...)    |
| `PATCH /api/v1/venues/{id}/policies`                    | `PATCH :8080/api/v1/records/{listingId}` (openingTime, ...)        |
| `PATCH /api/v1/venues/{id}/media`                       | `POST :8080/api/v1/records/{listingId}/files/{fieldName}`          |
| `POST /api/v1/venues/{id}/submit`                       | `POST :8080/api/v1/records/{listingId}/status` (trigger: SUBMIT)   |
| `PATCH /api/v1/venues/{id}/status`                      | `POST :8085/api/v1/approvals/{id}/decide`                          |
| `PATCH /api/v1/venues/{id}/featured`                    | `POST :8108/api/v1/listings/{listingId}/publish` (searchBoost)     |
| `GET /api/v1/venues?city=&minCapacity=`                 | `GET :8082/api/v1/search?objectType=VENUE&city=&minCapacity=`      |

---

## Key Differences to be Aware Of

| Concern                        | vendor-management                              | lagu-platform                                              |
|-------------------------------|------------------------------------------------|-------------------------------------------------------------|
| Vendor profile storage        | Dedicated SQL tables per field group           | Single JSONB record; fields defined by VENDOR listing type  |
| Venue document model          | MongoDB document with embedded sub-objects     | PostgreSQL JSONB record (VENUE listing type); halls as ARRAY_OF_OBJECTS field |
| Document upload               | `VendorDocumentRequest` (URL + metadata)       | Multipart upload → image-service → URL stored in doc record |
| Change request queue          | `VendorChangeRequest` / `ListingChangeRequest` | `ChangeSet` in workflow-service (universal)                 |
| Approval workflow             | Bespoke `AdminChangeRequestController`         | Generic `ApprovalEngine` + `StateMachineEngine`             |
| Verification tiers            | `VerificationTier` enum (NONE/BASIC/ENHANCED/PREMIUM) | Same tiers; stored on record as `RecordVerification`   |
| Consumer search               | `GET /api/v1/venues` (MongoDB query)           | `GET :8082/api/v1/search` (OpenSearch dual-index)           |
| Schema changes                | Code change + migration required               | Admin UI → schema-registry → `SchemaPublishedEvent` → zero downtime |
