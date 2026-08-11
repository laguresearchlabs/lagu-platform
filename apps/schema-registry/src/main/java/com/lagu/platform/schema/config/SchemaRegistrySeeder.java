package com.lagu.platform.schema.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.schema.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaRegistrySeeder implements ApplicationRunner {

    private final FieldDefinitionRepository       fieldRepo;
    private final FieldGroupRepository            fieldGroupRepo;
    private final ListingTypeDefinitionRepository listingTypeRepo;
    private final TierConfigurationRepository     tierConfigRepo;
    private final DocumentRequirementRepository   docReqRepo;
    private final TierEligibilityRuleRepository   tierRuleRepo;
    private final CountryValidationConfigRepository countryRepo;
    private final CategoryDefinitionRepository    categoryRepo;
    private final RelationshipDefinitionRepository relDefRepo;

    @Value("${platform.seeder.enabled:true}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        log.info("Running SchemaRegistrySeeder...");
        seedFields();
        seedArrayFields();
        seedFieldGroups();
        seedListingTypes();
        seedTierConfigurations();
        seedDocumentRequirements();
        seedTierEligibilityRules();
        seedCountryValidationConfigs();
        seedCategories();
        seedRelationshipDefinitions();
        seedCardPresentation();
        log.info("SchemaRegistrySeeder complete");
    }

    // ── 1. Field Definitions ──────────────────────────────────────────────────

    private void seedFields() {
        List<FieldSpec> specs = List.of(
            // Shared basics
            field("name",              "Name",              FieldType.TEXT,        true,  true,  null),
            field("description",       "Description",       FieldType.LONG_TEXT,   false, false, null),
            // Contact
            field("phone",             "Phone",             FieldType.PHONE,       false, false, null),
            field("email",             "Email",             FieldType.EMAIL,       false, true,  null),
            field("website",           "Website",           FieldType.URL,         false, false, null),
            // Address
            field("address_line1",     "Address Line 1",    FieldType.TEXT,        false, false, null),
            field("address_line2",     "Address Line 2",    FieldType.TEXT,        false, false, null),
            field("city",              "City",              FieldType.TEXT,        false, true,  null),
            field("state",             "State",             FieldType.TEXT,        false, true,  null),
            field("country",           "Country",           FieldType.TEXT,        false, true,  null),
            field("postal_code",       "Postal Code",       FieldType.TEXT,        false, false, null),
            field("landmark",          "Landmark",          FieldType.TEXT,        false, false, null),
            field("latitude",          "Latitude",          FieldType.DECIMAL,     false, false, null),
            field("longitude",         "Longitude",         FieldType.DECIMAL,     false, false, null),
            // Pricing
            field("pricing_model",     "Pricing Model",     FieldType.ENUM,        false, true,
                List.of("FIXED","PER_HOUR","PER_DAY","PER_PERSON","PER_PLATE","PACKAGE","CUSTOM_QUOTE")),
            field("price",             "Price",             FieldType.DECIMAL,     false, true,  null),
            field("currency",          "Currency",          FieldType.ENUM,        false, false,
                List.of("INR","USD","EUR","GBP")),
            field("tax_percent",       "Tax %",             FieldType.DECIMAL,     false, false, null),
            field("min_price",         "Min Price",         FieldType.DECIMAL,     false, false, null),
            field("max_price",         "Max Price",         FieldType.DECIMAL,     false, false, null),
            field("cancellation_policy","Cancellation Policy", FieldType.LONG_TEXT, false, false, null),
            // Media
            field("cover_image",       "Cover Image",       FieldType.IMAGE,       false, false, null),
            field("gallery",           "Gallery",           FieldType.MEDIA_GALLERY,false, false, null),
            field("video_url",         "Video URL",         FieldType.URL,         false, false, null),
            // Venue specific
            field("capacity",          "Capacity",          FieldType.NUMBER,      false, true,  null),
            field("area_sqft",         "Area (sq ft)",      FieldType.NUMBER,      false, true,  null),
            field("venue_type",        "Venue Type",        FieldType.ENUM,        false, true,
                List.of("BANQUET_HALL","OUTDOOR","ROOFTOP","RESORT","HOTEL","FARMHOUSE","BEACH")),
            field("parking_slots",     "Parking Slots",     FieldType.NUMBER,      false, true,  null),
            field("has_catering",      "In-house Catering", FieldType.BOOLEAN,     false, true,  null),
            field("has_dj",            "DJ Facility",       FieldType.BOOLEAN,     false, false, null),
            field("decoration",        "Decoration",        FieldType.BOOLEAN,     false, false, null),
            field("ac_available",      "AC Available",      FieldType.BOOLEAN,     false, true,  null),
            // PROJECTOR/KITCHEN/ELEVATOR were discrete booleans on the decommissioned
            // partyhall-service. They are amenities like any other, so they belong in this list
            // rather than as three more BOOLEAN fields. Adding enum values is a SAFE schema change.
            field("amenities",         "Amenities",         FieldType.MULTI_SELECT,false, true,
                List.of("WIFI","GENERATOR","VALET","SWIMMING_POOL","GYM","BAR","STAGE",
                        "PROJECTOR","KITCHEN","ELEVATOR")),
            // Photographer specific
            field("experience_years",  "Experience (yrs)",  FieldType.NUMBER,      false, true,  null),
            field("specializations",   "Specializations",   FieldType.MULTI_SELECT,false, true,
                List.of("WEDDING","PORTRAIT","CORPORATE","FASHION","PRODUCT","MATERNITY","NEWBORN")),
            field("camera_brands",     "Camera Brands",     FieldType.MULTI_SELECT,false, false,
                List.of("CANON","NIKON","SONY","FUJIFILM","LEICA","OLYMPUS")),
            field("editing_software",  "Editing Software",  FieldType.MULTI_SELECT,false, false,
                List.of("LIGHTROOM","PHOTOSHOP","CAPTURE_ONE","DARKTABLE")),
            field("delivery_days",     "Delivery Days",     FieldType.NUMBER,      false, false, null),
            field("travel_allowed",    "Travel Allowed",    FieldType.BOOLEAN,     false, true,  null),
            field("travel_radius_km",  "Travel Radius (km)",FieldType.NUMBER,      false, false, null),
            // Caterer specific
            field("cuisine_types",     "Cuisine Types",     FieldType.MULTI_SELECT,false, true,
                List.of("NORTH_INDIAN","SOUTH_INDIAN","CHINESE","CONTINENTAL","MUGHLAI","RAJASTHANI","MEDITERRANEAN")),
            field("meal_types",        "Meal Types",        FieldType.MULTI_SELECT,false, true,
                List.of("VEG","NON_VEG","VEGAN","JAIN")),
            // Replaces the decommissioned catering-service's single `serviceType` reference.
            // Modelled as MULTI_SELECT because a caterer typically offers several styles, and to
            // match cuisine_types/meal_types in the same group.
            field("service_types",     "Service Styles",    FieldType.MULTI_SELECT,false, true,
                List.of("BUFFET","PLATED","LIVE_COUNTER","FAMILY_STYLE","BOXED_MEALS","FOOD_TRUCK")),
            field("min_guests",        "Min Guests",        FieldType.NUMBER,      false, true,  null),
            field("max_guests",        "Max Guests",        FieldType.NUMBER,      false, true,  null),
            field("provides_staff",    "Staff Included",    FieldType.BOOLEAN,     false, false, null),
            field("provides_cutlery",  "Cutlery Included",  FieldType.BOOLEAN,     false, false, null),
            // Decorator specific
            field("style_types",       "Style Types",       FieldType.MULTI_SELECT,false, true,
                List.of("FLORAL","MODERN","TRADITIONAL","ROYAL","BEACH","MINIMALIST","RUSTIC")),
            field("event_types_dec",   "Event Types",       FieldType.MULTI_SELECT,false, true,
                List.of("WEDDING","BIRTHDAY","CORPORATE","BABY_SHOWER","ANNIVERSARY")),
            field("price_per_sqft",    "Price/sqft",        FieldType.DECIMAL,     false, false, null),
            field("includes_flowers",  "Flowers Included",  FieldType.BOOLEAN,     false, false, null),
            field("includes_lights",   "Lights Included",   FieldType.BOOLEAN,     false, false, null),
            // Makeup artist specific
            field("makeup_specializations","Specializations",FieldType.MULTI_SELECT,false,true,
                List.of("BRIDAL","PARTY","AIRBRUSH","EDITORIAL","THEATRICAL")),
            field("home_service",      "Home Service",      FieldType.BOOLEAN,     false, true,  null),
            field("brands_used",       "Brands Used",       FieldType.MULTI_SELECT,false, false,
                List.of("MAC","HUDA","BOBBI_BROWN","KRYOLAN","NARS","CHARLOTTE_TILBURY")),
            // Event shared
            field("event_date",        "Event Date",        FieldType.DATE,        true,  true,  null),
            field("expected_guests",   "Expected Guests",   FieldType.NUMBER,      false, true,  null),
            field("budget_inr",        "Budget (INR)",      FieldType.DECIMAL,     false, false, null),
            field("venue_ref",         "Venue",             FieldType.ENTITY_REFERENCE,false,false,null),
            // Wedding event specific
            field("event_sub_types",   "Sub-Events",        FieldType.MULTI_SELECT,false, false,
                List.of("MEHENDI","HALDI","SANGEET","RECEPTION","PHERAS")),
            field("event_time",        "Event Time",        FieldType.TIME,        false, false, null),
            // Corporate event specific
            field("company_name",      "Company Name",      FieldType.TEXT,        false, true,  null),
            field("corporate_event_type","Event Type",      FieldType.ENUM,        false, true,
                List.of("CONFERENCE","SEMINAR","PRODUCT_LAUNCH","TEAM_OUTING","AWARDS_CEREMONY")),
            field("expected_attendees","Attendees",         FieldType.NUMBER,      false, true,  null),
            field("requires_recording","Recording Needed",  FieldType.BOOLEAN,     false, false, null),
            field("requires_streaming","Live Streaming",    FieldType.BOOLEAN,     false, false, null),
            // Vendor identity / KYC
            field("business_name",     "Business Name",     FieldType.TEXT,        true,  true,  null),
            field("business_type",     "Business Type",     FieldType.ENUM,        true,  true,
                List.of("SOLE_PROPRIETORSHIP","PARTNERSHIP","PRIVATE_LIMITED","LLP","PUBLIC_LIMITED")),
            field("gstin",             "GSTIN",             FieldType.TEXT,        false, false, null),
            // Not required at the definition level. ListingTypeService resolves a field's
            // requiredness as `field.isRequired() || entry.isRequired()`, so a definition marked
            // required cannot be relaxed by the tax_info group entry below — which is why making
            // only that entry optional left POST /api/v1/vendors/register still failing with
            // "pan_number: field is required". PAN is verified at the KYC step via
            // document-service, long after vendor-service creates the VENDOR record.
            field("pan_number",        "PAN Number",        FieldType.TEXT,        false, false, null),
            field("company_reg_no",    "Company Reg No.",   FieldType.TEXT,        false, false, null),
            // Vendor bank info
            field("account_number",    "Account Number",    FieldType.TEXT,        false, false, null),
            field("ifsc_code",         "IFSC Code",         FieldType.TEXT,        false, false, null),
            field("account_holder",    "Account Holder",    FieldType.TEXT,        false, false, null),
            field("bank_name",         "Bank Name",         FieldType.TEXT,        false, false, null),
            // Tax info
            field("tax_registration",  "Tax Registration",  FieldType.TEXT,        false, false, null),
            field("service_tax_no",    "Service Tax No.",   FieldType.TEXT,        false, false, null),
            // Event (birthday/wedding) shared — schedule, visibility, style
            field("start_datetime",    "Start",             FieldType.DATETIME,    true,  true,  null),
            field("end_datetime",      "End",               FieldType.DATETIME,    true,  false, null),
            field("timezone",          "Timezone",          FieldType.TEXT,        false, false, null),
            field("visibility",        "Visibility",        FieldType.ENUM,        false, false,
                List.of("PRIVATE","UNLISTED","PUBLIC")),
            field("is_virtual",        "Virtual Event",     FieldType.BOOLEAN,     false, false, null),
            field("virtual_meeting_provider","Meeting Platform",FieldType.ENUM,    false, false,
                List.of("ZOOM","GOOGLE_MEET","MICROSOFT_TEAMS","OTHER")),
            field("virtual_meeting_url","Meeting URL",      FieldType.URL,         false, false, null),
            field("post_approval_required","Posts Require Approval",FieldType.BOOLEAN,false,false,null),
            field("event_theme",       "Theme",             FieldType.TEXT,        false, false, null),
            field("dresscode",         "Dress Code",        FieldType.TEXT,        false, false, null),
            field("menu_preference",   "Menu Preference",   FieldType.LONG_TEXT,   false, false, null),
            field("rsvp_deadline",     "RSVP Deadline",     FieldType.DATE,        false, false, null),
            // Anniversary event specific
            field("anniversary_years", "Years Married",     FieldType.NUMBER,      false, true,  null),
            // Birthday event specific
            field("birthday_person_name","Birthday Person", FieldType.TEXT,        true,  true,  null),
            field("birthday_person_age","Age",              FieldType.NUMBER,      false, false, null),
            field("birthday_person_gender","Gender",        FieldType.ENUM,        false, false,
                List.of("MALE","FEMALE","NON_BINARY","PREFER_NOT_TO_SAY")),
            field("cake_preference",   "Cake Preference",   FieldType.TEXT,        false, false, null),
            // Matches event-nest's GiftPreference/PartyStyle enums exactly (source of truth for
            // the birthday-service data this is migrating from) — not a freely-chosen taxonomy.
            field("gift_preference",   "Gift Preference",   FieldType.ENUM,        false, false,
                List.of("ANY","WISH_LIST_ONLY","CASH_PREFERRED","NO_GIFTS","CHARITY_DONATION")),
            field("party_style",       "Party Style",       FieldType.ENUM,        false, false,
                List.of("INDOOR","OUTDOOR","RESTAURANT","HOME","VENUE")),
            field("guest_count_adults","Adult Guests",      FieldType.NUMBER,      false, false, null),
            field("guest_count_children","Child Guests",    FieldType.NUMBER,      false, false, null),
            field("surprise_mode",     "Surprise Mode",     FieldType.BOOLEAN,     false, false, null),
            // Wedding event party details (event_date/venue_ref/etc reused from wedding_details)
            field("bride_name",        "Bride's Name",      FieldType.TEXT,        false, true,  null),
            field("groom_name",        "Groom's Name",      FieldType.TEXT,        false, true,  null),
            // Matches event-nest's CeremonyType enum exactly, same rationale as gift_preference above.
            field("ceremony_type",     "Ceremony Type",     FieldType.ENUM,        false, false,
                List.of("CIVIL","RELIGIOUS","DESTINATION","CULTURAL","INTIMATE")),
            field("reception_style",   "Reception Style",   FieldType.TEXT,        false, false, null),
            field("wedding_website_url","Wedding Website",  FieldType.URL,         false, false, null),
            // Event social feed (posts/comments/reports) — replaces event-nest's posts-service
            field("post_content",       "Content",           FieldType.LONG_TEXT,   true,  false, null),
            // MEDIA_GALLERY, not JSON: a post's attachments are uploaded photos, so they get the
            // same pipeline a listing gallery does — scanning, dimension rules, derivatives,
            // ordering and per-request signing — with no media code in event-service at all.
            // Posts are EVENT_POST records, so record-service's gallery endpoints already serve
            // them. This previously held image-service UUIDs, which is what tied event posts to
            // a service the platform no longer needs.
            field("post_images",        "Images",            FieldType.MEDIA_GALLERY,false, false, null),
            field("post_pinned",        "Pinned",            FieldType.BOOLEAN,     false, false, null),
            field("post_locked",        "Comments Locked",   FieldType.BOOLEAN,     false, false, null),
            field("comment_content",    "Content",           FieldType.TEXT,        true,  false, null),
            // Matches event-nest's PostReportReason enum exactly.
            field("report_reason",      "Reason",            FieldType.ENUM,        true,  false,
                List.of("SPAM","INAPPROPRIATE","OFF_TOPIC","OTHER")),
            field("report_details",     "Details",           FieldType.LONG_TEXT,   false, false, null),
            field("reported_post_id",   "Reported Post",     FieldType.ENTITY_REFERENCE,true,false,null)
        );

        int seeded = 0;
        for (FieldSpec s : specs) {
            if (fieldRepo.findByNameAndTenantIdIsNull(s.name()).isEmpty()) {
                FieldDefinition def = new FieldDefinition();
                def.setName(s.name());
                def.setLabel(s.label());
                def.setFieldType(s.fieldType());
                def.setRequired(s.required());
                def.setSearchable(s.searchable());
                def.setFilterable(s.searchable());
                if (s.enumValues() != null) def.setEnumValues(s.enumValues());
                fieldRepo.save(def);
                seeded++;
            }
        }
        if (seeded > 0) log.info("Seeded {} platform field definitions", seeded);
    }

    // ── 1b. Array (repeating structure) field definitions ────────────────────
    // itemSchema entries use the keys "name" (String), "type" (FieldType name), "required"
    // (boolean, optional) — see MetadataClient.FieldSchemaDto.itemSchema / RecordValidator.

    private void seedArrayFields() {
        record ArraySpec(String name, String label, List<Map<String, Object>> itemSchema) {}

        List<ArraySpec> specs = List.of(
            new ArraySpec("wish_list", "Wish List", List.of(
                itemField("item", "TEXT", true),
                itemField("priority", "ENUM", false),
                itemField("purchased", "BOOLEAN", false),
                itemField("purchasedBy", "TEXT", false))),
            new ArraySpec("planning_tasks", "Planning Tasks", List.of(
                itemField("title", "TEXT", true),
                itemField("assignee", "TEXT", false),
                itemField("dueDate", "DATE", false),
                itemField("status", "ENUM", false))),
            new ArraySpec("budget_items", "Budget Items", List.of(
                itemField("category", "TEXT", false),
                itemField("description", "TEXT", false),
                itemField("estimatedCost", "DECIMAL", false),
                itemField("actualCost", "DECIMAL", false))),
            new ArraySpec("schedule_activities", "Schedule", List.of(
                itemField("time", "TIME", false),
                itemField("activity", "TEXT", true),
                itemField("durationMinutes", "NUMBER", false)))
        );

        int seeded = 0;
        for (ArraySpec s : specs) {
            if (fieldRepo.findByNameAndTenantIdIsNull(s.name()).isEmpty()) {
                FieldDefinition def = new FieldDefinition();
                def.setName(s.name());
                def.setLabel(s.label());
                def.setFieldType(FieldType.ARRAY_OF_OBJECTS);
                def.setRequired(false);
                def.setSearchable(false);
                def.setFilterable(false);
                def.setItemSchema(s.itemSchema());
                fieldRepo.save(def);
                seeded++;
            }
        }
        if (seeded > 0) log.info("Seeded {} array field definitions", seeded);
    }

    private Map<String, Object> itemField(String name, String type, boolean required) {
        return Map.of("name", name, "type", type, "required", required);
    }

    // ── 2. Field Groups ───────────────────────────────────────────────────────

    private void seedFieldGroups() {
        ensureFieldGroup("basic_details",       "Basic Details",
            List.of(fge("name", 0, true), fge("description", 1, false)));

        ensureFieldGroup("contact_details",     "Contact Details",
            List.of(fge("phone", 0, false), fge("email", 1, false), fge("website", 2, false)));

        ensureFieldGroup("address",             "Address",
            List.of(fge("address_line1",0,false), fge("address_line2",1,false), fge("city",2,false),
                    fge("state",3,false), fge("country",4,false), fge("postal_code",5,false),
                    fge("landmark",6,false),
                    fge("latitude",7,false), fge("longitude",8,false)));

        ensureFieldGroup("pricing",             "Pricing",
            List.of(fge("pricing_model",0,false), fge("price",1,false), fge("currency",2,false),
                    fge("tax_percent",3,false), fge("min_price",4,false), fge("max_price",5,false),
                    fge("cancellation_policy",6,false)));

        ensureFieldGroup("media",               "Media",
            List.of(fge("cover_image",0,false), fge("gallery",1,false), fge("video_url",2,false)));

        ensureFieldGroup("venue_details",       "Venue Details",
            List.of(fge("capacity",0,true), fge("area_sqft",1,false), fge("venue_type",2,false),
                    fge("parking_slots",3,false), fge("has_catering",4,false), fge("has_dj",5,false),
                    fge("decoration",6,false), fge("ac_available",7,false), fge("amenities",8,false)));

        ensureFieldGroup("photographer_profile","Photographer Profile",
            List.of(fge("experience_years",0,false), fge("specializations",1,false),
                    fge("camera_brands",2,false), fge("editing_software",3,false),
                    fge("delivery_days",4,false), fge("travel_allowed",5,false),
                    fge("travel_radius_km",6,false)));

        ensureFieldGroup("caterer_menu",        "Menu & Cuisine",
            List.of(fge("cuisine_types",0,false), fge("meal_types",1,false),
                    fge("service_types",2,false),
                    fge("min_guests",3,false), fge("max_guests",4,false),
                    fge("provides_staff",5,false), fge("provides_cutlery",6,false)));

        ensureFieldGroup("decorator_profile",   "Decorator Profile",
            List.of(fge("style_types",0,false), fge("event_types_dec",1,false),
                    fge("price_per_sqft",2,false), fge("includes_flowers",3,false),
                    fge("includes_lights",4,false)));

        ensureFieldGroup("makeup_profile",      "Makeup Profile",
            List.of(fge("makeup_specializations",0,false), fge("home_service",1,false),
                    fge("brands_used",2,false)));

        ensureFieldGroup("wedding_details",     "Wedding Details",
            List.of(fge("event_date",0,true), fge("event_time",1,false),
                    fge("expected_guests",2,false), fge("event_sub_types",3,false),
                    fge("budget_inr",4,false), fge("venue_ref",5,false)));

        ensureFieldGroup("corporate_details",   "Corporate Details",
            List.of(fge("company_name",0,false), fge("corporate_event_type",1,false),
                    fge("event_date",2,true), fge("expected_attendees",3,false),
                    fge("budget_inr",4,false), fge("venue_ref",5,false),
                    fge("requires_recording",6,false), fge("requires_streaming",7,false)));

        // pan_number is NOT required here. record-service validates every record against the
        // flattened section->field schema (MetadataClient reads f.required() off these group
        // entries, not off field_definition), so a required entry is enforced on *record
        // creation*. vendor-service creates the VENDOR record at registration time, before any
        // KYC data exists — it registers an empty KYC checklist and PAN is verified later via
        // document-service — so requiring it here made every POST /api/v1/vendors/register fail
        // with "pan_number: field is required". Enforce PAN at the KYC step, not at creation.
        ensureFieldGroup("tax_info",            "Tax Information",
            List.of(fge("gstin",0,false), fge("pan_number",1,false),
                    fge("tax_registration",2,false), fge("service_tax_no",3,false)));

        ensureFieldGroup("bank_info",           "Bank Details",
            List.of(fge("account_number",0,false), fge("ifsc_code",1,false),
                    fge("account_holder",2,false), fge("bank_name",3,false)));

        // Event (birthday/wedding) shared groups
        ensureFieldGroup("event_schedule",      "Schedule",
            List.of(fge("start_datetime",0,true), fge("end_datetime",1,true), fge("timezone",2,false)));

        // The two virtual_meeting_* fields are meaningless for an in-person event, so they only
        // appear once is_virtual is set. This group is shared by WEDDING_EVENT and BIRTHDAY_EVENT
        // and the rule applies in both, which is the intent — see ADR-19 on rule placement.
        ensureFieldGroup("event_visibility",    "Visibility",
            List.of(fge("visibility",0,false), fge("is_virtual",1,false),
                    fge("virtual_meeting_provider",2,false, visibleWhenTruthy("is_virtual")),
                    fge("virtual_meeting_url",3,false, visibleWhenTruthy("is_virtual")),
                    fge("post_approval_required",4,false)));

        ensureFieldGroup("event_style_preferences","Style & Preferences",
            List.of(fge("event_theme",0,false), fge("dresscode",1,false),
                    fge("menu_preference",2,false), fge("rsvp_deadline",3,false)));

        ensureFieldGroup("event_planning_tools","Planning Tools",
            List.of(fge("planning_tasks",0,false), fge("budget_items",1,false),
                    fge("schedule_activities",2,false)));

        ensureFieldGroup("wish_list",           "Wish List",
            List.of(fge("wish_list",0,false)));

        ensureFieldGroup("anniversary_details", "Anniversary Details",
            List.of(fge("anniversary_years",0,false)));

        ensureFieldGroup("birthday_details",    "Birthday Details",
            List.of(fge("birthday_person_name",0,true), fge("birthday_person_age",1,false),
                    fge("birthday_person_gender",2,false), fge("cake_preference",3,false),
                    fge("gift_preference",4,false), fge("party_style",5,false),
                    fge("guest_count_adults",6,false), fge("guest_count_children",7,false),
                    fge("surprise_mode",8,false)));

        ensureFieldGroup("wedding_party_details","Wedding Party",
            List.of(fge("bride_name",0,false), fge("groom_name",1,false),
                    fge("ceremony_type",2,false), fge("reception_style",3,false),
                    fge("wedding_website_url",4,false)));

        ensureFieldGroup("event_post_details",  "Post",
            List.of(fge("post_content",0,true), fge("post_images",1,false),
                    fge("post_pinned",2,false), fge("post_locked",3,false)));

        ensureFieldGroup("event_comment_details","Comment",
            List.of(fge("comment_content",0,true)));

        ensureFieldGroup("event_post_report_details","Report",
            List.of(fge("reported_post_id",0,true), fge("report_reason",1,true), fge("report_details",2,false)));
    }

    // ── 3. Listing Types ──────────────────────────────────────────────────────

    private void seedListingTypes() {
        ensureListingType("VENUE",          "Venue",          "A bookable event venue",
            List.of(
                sec("basic_details",     "Basic Details",    0),
                sec("venue_details",     "Venue Details",    1),
                sec("contact_details",   "Contact",          2),
                sec("address",           "Address",          3),
                sec("pricing",           "Pricing",          4),
                sec("media",             "Gallery & Media",  5)
            ), true, true);

        ensureListingType("PHOTOGRAPHER",   "Photographer",   "Professional photographer",
            List.of(
                sec("basic_details",         "Basic Details",  0),
                sec("photographer_profile",  "Profile",        1),
                sec("contact_details",       "Contact",        2),
                sec("address",               "Address",        3),
                sec("pricing",               "Pricing",        4),
                sec("media",                 "Portfolio",      5)
            ), true, true);

        ensureListingType("CATERER",        "Caterer",        "Catering and food service provider",
            List.of(
                sec("basic_details",   "Basic Details",  0),
                sec("caterer_menu",    "Menu & Cuisine", 1),
                sec("contact_details", "Contact",        2),
                sec("address",         "Address",        3),
                sec("pricing",         "Pricing",        4),
                sec("media",           "Gallery",        5)
            ), true, true);

        ensureListingType("DECORATOR",      "Decorator",      "Event decoration specialist",
            List.of(
                sec("basic_details",     "Basic Details",    0),
                sec("decorator_profile", "Decorator Profile",1),
                sec("contact_details",   "Contact",          2),
                sec("address",           "Address",          3),
                sec("pricing",           "Pricing",          4),
                sec("media",             "Portfolio",        5)
            ), true, true);

        ensureListingType("MAKEUP_ARTIST",  "Makeup Artist",  "Professional makeup artist",
            List.of(
                sec("basic_details",   "Basic Details", 0),
                sec("makeup_profile",  "Profile",       1),
                sec("contact_details", "Contact",       2),
                sec("address",         "Address",       3),
                sec("pricing",         "Pricing",       4),
                sec("media",           "Portfolio",     5)
            ), true, true);

        // Events are private, membership-gated planning objects, not discoverable marketplace
        // listings — publishable=false/consumerSearchable=false, matching VENDOR's pattern
        // rather than VENUE's. (WEDDING_EVENT/CORPORATE_EVENT were previously seeded (true,true);
        // ensureListingType() is a no-op once a row exists, so this only takes effect on a fresh
        // seed — see plan note on manual migration for already-seeded environments.)
        ensureListingType("WEDDING_EVENT",  "Wedding Event",  "End-to-end wedding event management",
            List.of(
                sec("basic_details",           "Event Overview",   0),
                sec("event_schedule",          "Schedule",         1),
                sec("wedding_party_details",   "Wedding Party",    2),
                sec("wedding_details",         "Event Details",    3),
                sec("event_visibility",        "Visibility",       4),
                sec("event_style_preferences", "Style",            5),
                sec("event_planning_tools",    "Planning Tools",   6),
                sec("contact_details",         "Contact",          7),
                sec("address",                 "Address",          8),
                sec("media",                   "Media",            9)
            ), false, false, ListingTypeKind.EVENT, "💍", "rose");

        ensureListingType("CORPORATE_EVENT","Corporate Event","Corporate and business event management",
            List.of(
                sec("basic_details",     "Event Overview",    0),
                sec("corporate_details", "Corporate Details", 1),
                sec("contact_details",   "Contact",           2),
                sec("media",             "Media",             3)
            ), false, false, ListingTypeKind.EVENT, "🏢", "sky");

        ensureListingType("BIRTHDAY_EVENT", "Birthday Event", "Birthday party planning and management",
            List.of(
                sec("basic_details",           "Event Overview",   0),
                sec("event_schedule",          "Schedule",         1),
                sec("birthday_details",        "Birthday Details", 2),
                sec("event_visibility",        "Visibility",       3),
                sec("event_style_preferences", "Style",            4),
                sec("wish_list",               "Wish List",        5),
                sec("event_planning_tools",    "Planning Tools",   6),
                sec("address",                 "Address",          7),
                sec("media",                   "Media",            8)
            ), false, false, ListingTypeKind.EVENT, "🎂", "amber");

        // Originally authored through the admin portal rather than seeded, which left it with only
        // its custom section — its records had no name or dates and rendered as "Untitled Event".
        // Seeded here so the type survives a database reset, leading with the basics every other
        // event type has.
        ensureListingType("ANNIVERSARY_EVENT", "Anniversary", "Wedding anniversary celebration",
            List.of(
                sec("basic_details",       "Event Overview",      0),
                sec("event_schedule",      "Schedule",            1),
                sec("anniversary_details", "Anniversary Details", 2)
            ), false, false, ListingTypeKind.EVENT, "💐", "rose");

        ensureListingType("EVENT_POST", "Event Post", "A post in an event's social feed",
            List.of(sec("event_post_details", "Post", 0)), false, false,
            ListingTypeKind.SOCIAL, null, null);

        ensureListingType("EVENT_COMMENT", "Event Comment", "A comment on an event post",
            List.of(sec("event_comment_details", "Comment", 0)), false, false,
            ListingTypeKind.SOCIAL, null, null);

        ensureListingType("EVENT_POST_REPORT", "Event Post Report", "A user report against an event post",
            List.of(sec("event_post_report_details", "Report", 0)), false, false,
            ListingTypeKind.SOCIAL, null, null);

        ensureListingType("VENDOR",         "Vendor",         "Vendor business profile and KYC information",
            List.of(
                sec("basic_details",   "Business Identity", 0),
                sec("contact_details", "Contact",           1),
                sec("address",         "Address",           2),
                sec("tax_info",        "Tax Information",   3),
                sec("bank_info",       "Bank Details",      4)
            ), false, false);
    }

    // ── 4. Tier Configurations ────────────────────────────────────────────────

    private void seedTierConfigurations() {
        record TierSpec(String name, double commission, Integer maxBookings, double boost,
                        int slaHours, int expiryDays, Map<String, Object> features) {}

        List<TierSpec> tiers = List.of(
            new TierSpec("NONE",     20.0, 3,    1.0, 72, 0,   Map.of()),
            new TierSpec("BASIC",    15.0, 10,   1.5, 48, 0,   Map.of(
                    "priority_support", false, "analytics", false, "featured_badge", false)),
            new TierSpec("ENHANCED", 12.0, 25,   1.8, 36, 365, Map.of(
                    "priority_support", true,  "analytics", false, "featured_badge", false)),
            new TierSpec("PREMIUM",  10.0, null, 2.0, 24, 180, Map.of(
                    "priority_support", true,  "analytics", true,  "featured_badge", true))
        );

        int seeded = 0;
        for (TierSpec s : tiers) {
            if (tierConfigRepo.findByTierNameAndListingTypeIsNull(s.name()).isEmpty()) {
                TierConfiguration tc = new TierConfiguration();
                tc.setTierName(s.name());
                tc.setCommissionRate(new BigDecimal(String.valueOf(s.commission())));
                tc.setMaxActiveBookings(s.maxBookings());
                tc.setSearchBoostFactor(new BigDecimal(String.valueOf(s.boost())));
                tc.setResponseSlaHours(s.slaHours());
                tc.setExpiryDays(s.expiryDays());
                tc.setFeatures(s.features());
                tierConfigRepo.save(tc);
                seeded++;
            }
        }
        if (seeded > 0) log.info("Seeded {} tier configuration(s)", seeded);
    }

    // ── 5. Document Requirements ──────────────────────────────────────────────

    private void seedDocumentRequirements() {
        record DocSpec(String code, String label, boolean required, List<String> requiredForTiers,
                       boolean expiryTracked, int order) {}

        List<DocSpec> vendorDocs = List.of(
            new DocSpec("GST_CERTIFICATE",       "GST Registration Certificate",   false,
                List.of("BASIC","ENHANCED","PREMIUM"), true,  0),
            new DocSpec("PAN_CARD",              "PAN Card",                       false,
                List.of("BASIC","ENHANCED","PREMIUM"), false, 1),
            new DocSpec("BANK_CANCELLED_CHEQUE", "Cancelled Cheque / Bank Letter", false,
                List.of("BASIC","ENHANCED","PREMIUM"), false, 2),
            new DocSpec("IDENTITY_PROOF",        "Owner Identity Proof",           true,
                List.of(),                             false, 3),
            new DocSpec("TRADE_LICENSE",         "Trade License",                  false,
                List.of("ENHANCED","PREMIUM"),         true,  4)
        );

        int seeded = 0;
        for (DocSpec s : vendorDocs) {
            if (docReqRepo.findByCodeAndTenantIdIsNull(s.code()).isEmpty()) {
                DocumentRequirement doc = new DocumentRequirement();
                doc.setListingType("VENDOR");
                doc.setCode(s.code());
                doc.setLabel(s.label());
                doc.setRequired(s.required());
                doc.setRequiredForTiers(s.requiredForTiers().isEmpty() ? null : s.requiredForTiers());
                doc.setExpiryTracked(s.expiryTracked());
                doc.setAllowedMimeTypes(List.of("application/pdf","image/jpeg","image/png"));
                doc.setMaxSizeMb(5);
                doc.setDisplayOrder(s.order());
                docReqRepo.save(doc);
                seeded++;
            }
        }

        // HR / employee documents — listingType=null (platform-wide, not tied to a vendor
        // listing type). Consumed by document-service's DocumentTypeRegistry catalog fetch.
        // Preserves what used to be seeded by metadata-service before its decommission.
        record HrDocSpec(String code, String label, boolean required) {}
        List<HrDocSpec> hrDocs = List.of(
            new HrDocSpec("RESUME",               "Resume / CV",                          true),
            new HrDocSpec("HR_IDENTITY_PROOF",    "Government-issued Identity Proof",     true),
            new HrDocSpec("PHOTOGRAPH",           "Passport-size Photograph",             true),
            new HrDocSpec("ACADEMIC_CERTIFICATE", "Academic Certificates / Mark Sheets",  false),
            new HrDocSpec("ADDRESS_PROOF",        "Address Proof",                        false),
            new HrDocSpec("OTHER",                "Additional Documents",                 false)
        );
        int order = 0;
        for (HrDocSpec s : hrDocs) {
            if (docReqRepo.findByCodeAndTenantIdIsNull(s.code()).isEmpty()) {
                DocumentRequirement doc = new DocumentRequirement();
                doc.setListingType(null);
                doc.setCode(s.code());
                doc.setLabel(s.label());
                doc.setRequired(s.required());
                doc.setExpiryTracked(false);
                doc.setAllowedMimeTypes(List.of("application/pdf","image/jpeg","image/png"));
                doc.setMaxSizeMb(5);
                doc.setDisplayOrder(order++);
                docReqRepo.save(doc);
                seeded++;
            }
        }

        if (seeded > 0) log.info("Seeded {} document requirement(s)", seeded);
    }

    // ── 6. Tier Eligibility Rules ─────────────────────────────────────────────

    private void seedTierEligibilityRules() {
        record RuleSpec(String listingType, String tier, String ruleType, String documentCode,
                        String displayName, int order) {}

        List<RuleSpec> rules = List.of(
            new RuleSpec("VENDOR", "BASIC",    "DOCUMENT_VERIFIED", "PAN_CARD",
                         "PAN Card verified",          0),
            new RuleSpec("VENDOR", "BASIC",    "DOCUMENT_VERIFIED", "BANK_CANCELLED_CHEQUE",
                         "Bank document verified",     1),
            new RuleSpec("VENDOR", "ENHANCED", "DOCUMENT_VERIFIED", "PAN_CARD",
                         "PAN Card verified",          0),
            new RuleSpec("VENDOR", "ENHANCED", "DOCUMENT_VERIFIED", "BANK_CANCELLED_CHEQUE",
                         "Bank document verified",     1),
            new RuleSpec("VENDOR", "ENHANCED", "DOCUMENT_VERIFIED", "GST_CERTIFICATE",
                         "GST Certificate verified",   2)
        );

        int seeded = 0;
        for (RuleSpec s : rules) {
            boolean exists = !tierRuleRepo
                    .findByListingTypeAndTierAndActiveTrueOrderByDisplayOrder(s.listingType(), s.tier())
                    .stream()
                    .filter(r -> s.ruleType().equals(r.getRuleType())
                              && s.documentCode().equals(r.getDocumentCode()))
                    .toList()
                    .isEmpty();
            if (!exists) {
                TierEligibilityRule rule = new TierEligibilityRule();
                rule.setListingType(s.listingType());
                rule.setTier(s.tier());
                rule.setRuleType(s.ruleType());
                rule.setDocumentCode(s.documentCode());
                rule.setDisplayName(s.displayName());
                rule.setDisplayOrder(s.order());
                rule.setForceOverridable(true);
                tierRuleRepo.save(rule);
                seeded++;
            }
        }
        if (seeded > 0) log.info("Seeded {} tier eligibility rule(s)", seeded);
    }

    // ── 7. Country Validation Configs ─────────────────────────────────────────

    private void seedCountryValidationConfigs() {
        if (countryRepo.findByCountryAndActiveTrue("IN").isPresent()) return;

        CountryValidationConfig india = new CountryValidationConfig();
        india.setCountry("IN");
        india.setCurrency("INR");
        india.setTaxLabel("GST");
        india.setDialCode("+91");
        india.setRules(Map.of(
            "pan",            Map.of("pattern", "[A-Z]{5}[0-9]{4}[A-Z]{1}",
                                    "label",   "PAN Number"),
            "gstin",          Map.of("pattern",
                                    "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$",
                                    "label",   "GSTIN"),
            "ifsc",           Map.of("pattern", "^[A-Z]{4}0[A-Z0-9]{6}$",
                                    "label",   "IFSC Code"),
            "phone",          Map.of("pattern", "^[6-9]\\d{9}$",
                                    "label",   "Mobile Number"),
            "pincode",        Map.of("pattern", "^[1-9][0-9]{5}$",
                                    "label",   "PIN Code"),
            "account_number", Map.of("pattern", "^[0-9]{9,18}$",
                                    "label",   "Bank Account Number")
        ));
        countryRepo.save(india);
        log.info("Seeded country validation config: IN");
    }

    // ── 8. Category Definitions ───────────────────────────────────────────────

    private void seedCategories() {
        CategoryDefinition wedding   = ensureCategory(null, null,
                "wedding-services",   "Wedding Services",  0);
        CategoryDefinition corporate = ensureCategory(null, null,
                "corporate-services", "Corporate Services", 1);

        CategoryDefinition wVenue  = ensureCategory(wedding,   "VENUE",
                "wedding-venue",          "Wedding Venue",      0);
        CategoryDefinition wPhoto  = ensureCategory(wedding,   "PHOTOGRAPHER",
                "wedding-photography",    "Wedding Photography", 1);
        CategoryDefinition wCater  = ensureCategory(wedding,   "CATERER",
                "wedding-catering",       "Wedding Catering",   2);
        CategoryDefinition wDecor  = ensureCategory(wedding,   "DECORATOR",
                "wedding-decoration",     "Wedding Decoration", 3);
        CategoryDefinition wMakeup = ensureCategory(wedding,   "MAKEUP_ARTIST",
                "wedding-makeup",         "Bridal Makeup",      4);

        ensureCategory(wPhoto, "PHOTOGRAPHER", "wedding-day-photography",  "Wedding Day",              0);
        ensureCategory(wPhoto, "PHOTOGRAPHER", "pre-wedding-photography",  "Pre-Wedding / Engagement", 1);
        ensureCategory(wPhoto, "PHOTOGRAPHER", "candid-photography",       "Candid Photography",       2);
        ensureCategory(wPhoto, "PHOTOGRAPHER", "wedding-videography",      "Videography",              3);

        ensureCategory(corporate, "VENUE",       "corporate-venue",       "Corporate Venue",    0);
        ensureCategory(corporate, "CATERER",     "corporate-catering",    "Corporate Catering", 1);
        ensureCategory(corporate, "PHOTOGRAPHER","corporate-photography", "Event Photography",  2);

        log.info("Seeded category definitions");
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private void ensureFieldGroup(String name, String label, List<FgeSpec> entrySpecs) {
        if (fieldGroupRepo.findByNameAndTenantIdIsNull(name).isPresent()) return;

        FieldGroup group = new FieldGroup();
        group.setName(name);
        group.setLabel(label);

        List<FieldGroupEntry> entries = new ArrayList<>();
        for (FgeSpec spec : entrySpecs) {
            fieldRepo.findByNameAndTenantIdIsNull(spec.fieldName()).ifPresent(fd -> {
                FieldGroupEntry entry = new FieldGroupEntry();
                entry.setFieldGroup(group);
                entry.setField(fd);
                entry.setDisplayOrder(spec.displayOrder());
                entry.setRequired(spec.required());
                entry.setVisibleWhen(spec.visibleWhen());
                entries.add(entry);
            });
        }
        group.setEntries(entries);
        fieldGroupRepo.save(group);
        log.debug("Seeded field group: {}", name);
    }

    private void ensureListingType(String name, String label, String description,
                                   List<SecSpec> sections, boolean publishable,
                                   boolean consumerSearchable) {
        ensureListingType(name, label, description, sections, publishable, consumerSearchable,
                ListingTypeKind.LISTING, null, null);
    }

    /**
     * Presentation-aware overload. {@code icon} and {@code color} are what clients render a type
     * with — events-ui reads them instead of keeping its own per-event-type lookup, so a new
     * admin-authored event type gets an icon and a colour with no frontend change. {@code color}
     * is a palette token ("rose", "amber"), not a CSS value; the client owns the actual shades.
     */
    private void ensureListingType(String name, String label, String description,
                                   List<SecSpec> sections, boolean publishable,
                                   boolean consumerSearchable, ListingTypeKind kind,
                                   String icon, String color) {
        if (listingTypeRepo.findByNameAndTenantIdIsNull(name).isPresent()) return;

        ListingTypeDefinition def = new ListingTypeDefinition();
        def.setName(name);
        def.setLabel(label);
        def.setDescription(description);
        def.setKind(kind);
        def.setIcon(icon);
        def.setColor(color);
        def.setPublishable(publishable);
        def.setConsumerSearchable(consumerSearchable);

        List<ListingTypeSection> secs = new ArrayList<>();
        for (SecSpec spec : sections) {
            fieldGroupRepo.findByNameAndTenantIdIsNull(spec.groupName()).ifPresent(fg -> {
                ListingTypeSection sec = new ListingTypeSection();
                sec.setListingType(def);
                sec.setFieldGroup(fg);
                sec.setLabel(spec.label());
                sec.setSectionKey(spec.groupName());
                sec.setDisplayOrder(spec.order());
                secs.add(sec);
            });
        }
        def.setSections(secs);
        listingTypeRepo.save(def);
        log.debug("Seeded listing type: {}", name);
    }

    private CategoryDefinition ensureCategory(CategoryDefinition parent, String listingType,
                                              String slug, String label, int order) {
        return categoryRepo.findBySlugAndTenantIdIsNull(slug).orElseGet(() -> {
            CategoryDefinition c = new CategoryDefinition();
            c.setParent(parent);
            c.setListingType(listingType);
            c.setSlug(slug);
            c.setLabel(label);
            c.setDisplayOrder(order);
            return categoryRepo.save(c);
        });
    }

    // ── Spec records ──────────────────────────────────────────────────────────

    private FieldSpec field(String name, String label, FieldType type, boolean required,
                            boolean searchable, List<String> enumValues) {
        return new FieldSpec(name, label, type, required, searchable, enumValues);
    }

    private FgeSpec fge(String fieldName, int displayOrder, boolean required) {
        return new FgeSpec(fieldName, displayOrder, required, null);
    }

    /** Field group entry carrying a conditional-visibility rule — see {@link #visibleWhenTruthy}. */
    private FgeSpec fge(String fieldName, int displayOrder, boolean required,
                        Map<String, Object> visibleWhen) {
        return new FgeSpec(fieldName, displayOrder, required, visibleWhen);
    }

    /** {@code {"all":[{"field":<field>,"op":"truthy"}]}} — the rule shape from ADR-18. Note the
     *  rule travels with the field group, so it applies in every listing type composing it. */
    private static Map<String, Object> visibleWhenTruthy(String field) {
        return Map.of("all", List.of(Map.of("field", field, "op", "truthy")));
    }

    private SecSpec sec(String groupName, String label, int order) {
        return new SecSpec(groupName, label, order);
    }

    private record FieldSpec(String name, String label, FieldType fieldType,
                              boolean required, boolean searchable, List<String> enumValues) {}

    private record FgeSpec(String fieldName, int displayOrder, boolean required,
                           Map<String, Object> visibleWhen) {}

    private record SecSpec(String groupName, String label, int order) {}

    // ── 9. Relationship Definitions (ported from metadata-service) ─────────────

    private void seedRelationshipDefinitions() {
        record RelDefSpec(String name, String label, String sourceType, String targetType,
                           String relType, boolean required, boolean cascadeDelete) {}

        List<RelDefSpec> specs = List.of(
            new RelDefSpec("EVENT_VENUE",          "Event Venue",          "WEDDING_EVENT", "VENUE",         "ONE_TO_ONE",   false, false),
            new RelDefSpec("EVENT_PHOTOGRAPHERS",  "Event Photographers",  "WEDDING_EVENT", "PHOTOGRAPHER",  "MANY_TO_MANY", false, false),
            new RelDefSpec("EVENT_CATERERS",       "Event Caterers",       "WEDDING_EVENT", "CATERER",       "MANY_TO_MANY", false, false),
            new RelDefSpec("EVENT_DECORATORS",     "Event Decorators",     "WEDDING_EVENT", "DECORATOR",     "MANY_TO_MANY", false, false),
            new RelDefSpec("EVENT_MAKEUP_ARTISTS", "Event Makeup Artists", "WEDDING_EVENT", "MAKEUP_ARTIST", "MANY_TO_MANY", false, false),

            new RelDefSpec("BIRTHDAY_EVENT_VENUE",          "Event Venue",          "BIRTHDAY_EVENT", "VENUE",         "ONE_TO_ONE",   false, false),
            new RelDefSpec("BIRTHDAY_EVENT_PHOTOGRAPHERS",  "Event Photographers",  "BIRTHDAY_EVENT", "PHOTOGRAPHER",  "MANY_TO_MANY", false, false),
            new RelDefSpec("BIRTHDAY_EVENT_CATERERS",       "Event Caterers",       "BIRTHDAY_EVENT", "CATERER",       "MANY_TO_MANY", false, false),
            new RelDefSpec("BIRTHDAY_EVENT_DECORATORS",     "Event Decorators",     "BIRTHDAY_EVENT", "DECORATOR",     "MANY_TO_MANY", false, false),
            new RelDefSpec("BIRTHDAY_EVENT_MAKEUP_ARTISTS", "Event Makeup Artists", "BIRTHDAY_EVENT", "MAKEUP_ARTIST", "MANY_TO_MANY", false, false)
        );

        int seeded = 0;
        for (RelDefSpec s : specs) {
            if (relDefRepo.findByNameAndTenantIdIsNull(s.name()).isEmpty()) {
                RelationshipDefinition def = new RelationshipDefinition();
                def.setName(s.name());
                def.setLabel(s.label());
                def.setSourceListingType(s.sourceType());
                def.setTargetListingType(s.targetType());
                def.setRelationshipType(s.relType());
                def.setRequired(s.required());
                def.setCascadeDelete(s.cascadeDelete());
                relDefRepo.save(def);
                seeded++;
            }
        }
        if (seeded > 0) log.info("Seeded {} relationship definition(s)", seeded);
    }

    // ── 10. Card presentation (client rendering config) ───────────────────────

    /**
     * Seeds each listing type's icon, colour and {@code config.cardPresentation} from
     * {@code resources/seed/card-presentation.json}.
     *
     * <p>Held as JSON rather than built as nested Java maps because that is the shape it is stored
     * and served in — a literal here would be a hand-transcription of the same document, and would
     * drift. See events-ui's {@code config/cardPresentation.ts} for what the keys mean.
     *
     * <p>Unlike the other seed steps this one updates existing rows, since a listing type is
     * created by an earlier step and its presentation is layered on. It still skips any type that
     * already has a cardPresentation, so an admin's edits are never overwritten on restart.
     */
    @SuppressWarnings("unchecked")
    private void seedCardPresentation() {
        Map<String, Map<String, Object>> byType;
        try (InputStream in = new ClassPathResource("seed/card-presentation.json").getInputStream()) {
            byType = new ObjectMapper().readValue(in, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Could not read seed/card-presentation.json — listing types will fall back to "
                    + "the client's built-in config: {}", e.getMessage());
            return;
        }

        int seeded = 0;
        for (var entry : byType.entrySet()) {
            var existing = listingTypeRepo.findByNameAndTenantIdIsNull(entry.getKey());
            if (existing.isEmpty()) continue;

            ListingTypeDefinition def = existing.get();
            if (def.getConfig() != null && def.getConfig().containsKey("cardPresentation")) continue;

            Map<String, Object> spec = entry.getValue();
            if (spec.get("icon") instanceof String icon) def.setIcon(icon);
            if (spec.get("color") instanceof String color) def.setColor(color);

            Map<String, Object> config = def.getConfig() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(def.getConfig());
            config.put("cardPresentation", spec.get("cardPresentation"));
            def.setConfig(config);

            listingTypeRepo.save(def);
            seeded++;
        }
        if (seeded > 0) log.info("Seeded card presentation for {} listing type(s)", seeded);
    }
}
