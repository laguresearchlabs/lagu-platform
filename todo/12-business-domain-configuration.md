# Business Domain Configuration

## How Domains Are Bootstrapped

Every business domain (Venue, Photographer, Event, etc.) is a configuration artifact,
not code. This file shows exactly what the metadata configuration looks like for the
primary domains of the lagu-platform.

These configs are either seeded via the `DataInitializer` on first startup or created
by a `CONFIG_ADMIN` via the API.

---

## Shared Entities (Platform-Level Seed Data)

### Entity: `contact_details`
| Attribute   | Type  | Required | Searchable |
|-------------|-------|----------|------------|
| phone       | PHONE | false    | false      |
| email       | EMAIL | false    | true       |
| website     | URL   | false    | false      |

### Entity: `address`
| Attribute     | Type    | Required | Filterable |
|---------------|---------|----------|------------|
| address_line1 | TEXT    | false    | false      |
| address_line2 | TEXT    | false    | false      |
| city          | TEXT    | false    | true       |
| state         | TEXT    | false    | true       |
| country       | TEXT    | false    | true       |
| postal_code   | TEXT    | false    | false      |
| latitude      | DECIMAL | false    | false      |
| longitude     | DECIMAL | false    | false      |

### Entity: `pricing`
| Attribute     | Type    | Required | Notes                                         |
|---------------|---------|----------|-----------------------------------------------|
| pricing_model | ENUM    | false    | FIXED, PER_HOUR, PER_DAY, PER_PERSON, PER_PLATE, PACKAGE, CUSTOM_QUOTE |
| price         | DECIMAL | false    | Base price                                    |
| currency      | ENUM    | false    | INR, USD, EUR, GBP                            |
| tax_percent   | DECIMAL | false    | GST / VAT %                                   |
| min_price     | DECIMAL | false    | For CUSTOM_QUOTE min                          |
| max_price     | DECIMAL | false    | For CUSTOM_QUOTE max                          |

### Entity: `media`
| Attribute    | Type          | Required | Notes                  |
|--------------|---------------|----------|------------------------|
| cover_image  | IMAGE         | false    | Main display image     |
| gallery      | MULTI_SELECT  | false    | Array of image refs    |
| video_url    | URL           | false    | Intro video            |

---

## Vendor Domain

### ObjectType: `VENUE`

Sections (in order):
1. Basic Details → entity: `basic_details` (name, description, capacity, venue_type)
2. Contact → entity: `contact_details`
3. Address → entity: `address`
4. Pricing → entity: `pricing`
5. Media → entity: `media`
6. Policies → entity: `venue_policies`

Custom attributes for Venue:
| Attribute     | Type         | Filterable | Notes                                          |
|---------------|--------------|------------|------------------------------------------------|
| capacity      | NUMBER       | true       | Maximum guest count                            |
| venue_type    | ENUM         | true       | BANQUET_HALL, OUTDOOR, ROOFTOP, RESORT, HOTEL  |
| parking_slots | NUMBER       | true       | Available parking                              |
| has_catering  | BOOLEAN      | true       | In-house catering available                    |
| has_dj        | BOOLEAN      | false      | DJ facility available                          |
| decoration    | BOOLEAN      | false      | In-house decoration                            |
| ac_available  | BOOLEAN      | true       | Air conditioning                               |
| amenities     | MULTI_SELECT | true       | WIFI, GENERATOR, VALET, SWIMMING_POOL, GYM     |

Workflow: `VENUE_REVIEW`
```
DRAFT → SUBMITTED → UNDER_REVIEW → APPROVED → PUBLISHED → ARCHIVED
                              ↘ REJECTED → DRAFT
```

---

### ObjectType: `PHOTOGRAPHER`

Sections:
1. Basic Details → entity: `basic_details`
2. Contact → entity: `contact_details`
3. Specializations → entity: `photographer_specializations`
4. Equipment → entity: `photographer_equipment`
5. Pricing → entity: `pricing`
6. Portfolio → entity: `media`

Custom attributes:
| Attribute         | Type         | Notes                                          |
|-------------------|--------------|------------------------------------------------|
| experience_years  | NUMBER       | Years of experience                            |
| specializations   | MULTI_SELECT | WEDDING, PORTRAIT, CORPORATE, FASHION, PRODUCT |
| camera_brands     | MULTI_SELECT | CANON, NIKON, SONY, FUJIFILM                   |
| editing_software  | MULTI_SELECT | LIGHTROOM, PHOTOSHOP, CAPTURE_ONE              |
| delivery_days     | NUMBER       | Photo delivery time in days                    |
| travel_allowed    | BOOLEAN      | Willing to travel                              |
| travel_radius_km  | NUMBER       | Max travel distance                            |

---

### ObjectType: `CATERER`

Sections:
1. Basic Details
2. Contact Details
3. Address
4. Menu & Cuisine → entity: `caterer_menu`
5. Pricing → entity: `pricing` (model: PER_PLATE)
6. Media

Custom attributes:
| Attribute        | Type         | Notes                             |
|------------------|--------------|-----------------------------------|
| cuisine_types    | MULTI_SELECT | NORTH_INDIAN, SOUTH_INDIAN, CHINESE, CONTINENTAL, MUGHLAI |
| meal_types       | MULTI_SELECT | VEG, NON_VEG, VEGAN, JAIN        |
| min_guests       | NUMBER       | Minimum order                     |
| max_guests       | NUMBER       | Maximum capacity                  |
| provides_staff   | BOOLEAN      | Service staff included            |
| provides_cutlery | BOOLEAN      | Crockery/cutlery included         |

---

### ObjectType: `DECORATOR`

| Attribute        | Type         | Notes                             |
|------------------|--------------|-----------------------------------|
| style_types      | MULTI_SELECT | FLORAL, MODERN, TRADITIONAL, ROYAL, BEACH |
| event_types      | MULTI_SELECT | WEDDING, BIRTHDAY, CORPORATE, BABY_SHOWER |
| price_per_sqft   | DECIMAL      | For venue decoration              |
| includes_flowers | BOOLEAN      |                                   |
| includes_lights  | BOOLEAN      |                                   |

---

### ObjectType: `MAKEUP_ARTIST`

| Attribute        | Type         | Notes                             |
|------------------|--------------|-----------------------------------|
| specializations  | MULTI_SELECT | BRIDAL, PARTY, AIRBRUSH, EDITORIAL |
| home_service     | BOOLEAN      | Available at client location      |
| brands_used      | MULTI_SELECT | MAC, HUDA, BOBBI_BROWN, KRYOLAN   |

---

## Event Domain

### ObjectType: `WEDDING_EVENT`

Sections:
1. Event Details → entity: `event_details`
2. Schedule → entity: `event_schedule`
3. Couple Info → entity: `couple_details`
4. Budget → entity: `event_budget`
5. Venue (ENTITY_REFERENCE to VENUE)
6. Vendors (MANY_TO_MANY relationship to all vendor types)

Custom attributes:
| Attribute         | Type             | Notes                             |
|-------------------|------------------|-----------------------------------|
| event_date        | DATE             | Filterable                        |
| event_time        | TIME             |                                   |
| expected_guests   | NUMBER           |                                   |
| event_sub_types   | MULTI_SELECT     | MEHENDI, HALDI, SANGEET, RECEPTION |
| venue_id          | ENTITY_REFERENCE | target: VENUE                     |
| budget_inr        | DECIMAL          | Total budget                      |
| status            | ENUM             | Managed by workflow                |

Workflow: `EVENT_LIFECYCLE`
```
PLANNING → CONFIRMED → IN_PROGRESS → COMPLETED → ARCHIVED
        ↘ CANCELLED
```

---

### ObjectType: `CORPORATE_EVENT`

| Attribute         | Type         | Notes                    |
|-------------------|--------------|--------------------------|
| company_name      | TEXT         |                          |
| event_type        | ENUM         | CONFERENCE, SEMINAR, PRODUCT_LAUNCH, TEAM_OUTING |
| event_date        | DATE         |                          |
| expected_attendees| NUMBER       |                          |
| budget_inr        | DECIMAL      |                          |
| venue_id          | ENTITY_REFERENCE | target: VENUE         |
| requires_recording| BOOLEAN      | Video recording needed   |
| requires_streaming| BOOLEAN      | Live streaming needed    |

---

## Relationships to Configure

| Name                 | Source          | Target       | Type        |
|----------------------|-----------------|--------------|-------------|
| EVENT_VENUE          | WEDDING_EVENT   | VENUE        | ONE_TO_ONE  |
| EVENT_PHOTOGRAPHERS  | WEDDING_EVENT   | PHOTOGRAPHER | MANY_TO_MANY|
| EVENT_CATERERS       | WEDDING_EVENT   | CATERER      | MANY_TO_MANY|
| EVENT_DECORATORS     | WEDDING_EVENT   | DECORATOR    | MANY_TO_MANY|
| EVENT_BUDGET_ITEMS   | WEDDING_EVENT   | BUDGET_ITEM  | PARENT_CHILD|
| EVENT_MEMBERS        | WEDDING_EVENT   | (user ref)   | ONE_TO_MANY |
| ORG_VENUES           | ORG             | VENUE        | ONE_TO_MANY |
| ORG_PHOTOGRAPHERS    | ORG             | PHOTOGRAPHER | ONE_TO_MANY |

---

## Pricing Model Mapping

| ObjectType    | Recommended Model | Example                        |
|---------------|-------------------|--------------------------------|
| VENUE         | FIXED             | ₹75,000 for the event          |
| CATERER       | PER_PLATE         | ₹800 per person                |
| PHOTOGRAPHER  | PACKAGE           | ₹40,000 for wedding package    |
| DECORATOR     | CUSTOM_QUOTE      | Quote based on requirements    |
| MAKEUP_ARTIST | PER_HOUR / PACKAGE | ₹5,000 for bridal session     |

Admin can configure multiple pricing options per vendor — e.g., a Photographer offers
both a PACKAGE and an hourly rate.

---

## Workflow Configurations to Seed

| Workflow Name          | Object Type      | States                                           |
|------------------------|------------------|--------------------------------------------------|
| VENDOR_REVIEW          | All vendor types | DRAFT → SUBMITTED → UNDER_REVIEW → APPROVED / REJECTED → PUBLISHED → SUSPENDED / ARCHIVED |
| EVENT_LIFECYCLE        | WEDDING_EVENT    | PLANNING → CONFIRMED → IN_PROGRESS → COMPLETED / CANCELLED → ARCHIVED |
| CORPORATE_EVENT_LIFECYCLE | CORPORATE_EVENT | PLANNING → SUBMITTED → CONFIRMED → IN_PROGRESS → COMPLETED / CANCELLED |

---

## Seed Data Order

When initializing:

1. Create platform-level `AttributeDefinition` records (shared fields)
2. Create shared `EntityDefinition` records (contact_details, address, pricing, media)
3. Create `ObjectTypeDefinition` for each vendor/event type
4. Link sections (object type → entities)
5. Create `WorkflowDefinition` for each object type
6. Create `RelationshipDefinition` records
7. Create platform roles + permissions
