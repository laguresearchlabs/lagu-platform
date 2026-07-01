package com.lagu.platform.schema.config;

import com.lagu.platform.schema.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    @Value("${platform.seeder.enabled:true}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        log.info("Running SchemaRegistrySeeder...");
        seedFields();
        seedFieldGroups();
        seedListingTypes();
        seedTierConfigurations();
        seedDocumentRequirements();
        seedTierEligibilityRules();
        seedCountryValidationConfigs();
        seedCategories();
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
            // Media
            field("cover_image",       "Cover Image",       FieldType.IMAGE,       false, false, null),
            field("gallery",           "Gallery",           FieldType.MULTI_SELECT,false, false, null),
            field("video_url",         "Video URL",         FieldType.URL,         false, false, null),
            // Venue specific
            field("capacity",          "Capacity",          FieldType.NUMBER,      false, true,  null),
            field("venue_type",        "Venue Type",        FieldType.ENUM,        false, true,
                List.of("BANQUET_HALL","OUTDOOR","ROOFTOP","RESORT","HOTEL","FARMHOUSE","BEACH")),
            field("parking_slots",     "Parking Slots",     FieldType.NUMBER,      false, true,  null),
            field("has_catering",      "In-house Catering", FieldType.BOOLEAN,     false, true,  null),
            field("has_dj",            "DJ Facility",       FieldType.BOOLEAN,     false, false, null),
            field("decoration",        "Decoration",        FieldType.BOOLEAN,     false, false, null),
            field("ac_available",      "AC Available",      FieldType.BOOLEAN,     false, true,  null),
            field("amenities",         "Amenities",         FieldType.MULTI_SELECT,false, true,
                List.of("WIFI","GENERATOR","VALET","SWIMMING_POOL","GYM","BAR","STAGE")),
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
            field("pan_number",        "PAN Number",        FieldType.TEXT,        true,  false, null),
            field("company_reg_no",    "Company Reg No.",   FieldType.TEXT,        false, false, null),
            // Vendor bank info
            field("account_number",    "Account Number",    FieldType.TEXT,        false, false, null),
            field("ifsc_code",         "IFSC Code",         FieldType.TEXT,        false, false, null),
            field("account_holder",    "Account Holder",    FieldType.TEXT,        false, false, null),
            field("bank_name",         "Bank Name",         FieldType.TEXT,        false, false, null),
            // Tax info
            field("tax_registration",  "Tax Registration",  FieldType.TEXT,        false, false, null),
            field("service_tax_no",    "Service Tax No.",   FieldType.TEXT,        false, false, null)
        );

        int seeded = 0;
        for (FieldSpec s : specs) {
            if (fieldRepo.findByNameAndOrgIdIsNull(s.name()).isEmpty()) {
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

    // ── 2. Field Groups ───────────────────────────────────────────────────────

    private void seedFieldGroups() {
        ensureFieldGroup("basic_details",       "Basic Details",
            List.of(fge("name", 0, true), fge("description", 1, false)));

        ensureFieldGroup("contact_details",     "Contact Details",
            List.of(fge("phone", 0, false), fge("email", 1, false), fge("website", 2, false)));

        ensureFieldGroup("address",             "Address",
            List.of(fge("address_line1",0,false), fge("address_line2",1,false), fge("city",2,false),
                    fge("state",3,false), fge("country",4,false), fge("postal_code",5,false),
                    fge("latitude",6,false), fge("longitude",7,false)));

        ensureFieldGroup("pricing",             "Pricing",
            List.of(fge("pricing_model",0,false), fge("price",1,false), fge("currency",2,false),
                    fge("tax_percent",3,false), fge("min_price",4,false), fge("max_price",5,false)));

        ensureFieldGroup("media",               "Media",
            List.of(fge("cover_image",0,false), fge("gallery",1,false), fge("video_url",2,false)));

        ensureFieldGroup("venue_details",       "Venue Details",
            List.of(fge("capacity",0,true), fge("venue_type",1,false), fge("parking_slots",2,false),
                    fge("has_catering",3,false), fge("has_dj",4,false), fge("decoration",5,false),
                    fge("ac_available",6,false), fge("amenities",7,false)));

        ensureFieldGroup("photographer_profile","Photographer Profile",
            List.of(fge("experience_years",0,false), fge("specializations",1,false),
                    fge("camera_brands",2,false), fge("editing_software",3,false),
                    fge("delivery_days",4,false), fge("travel_allowed",5,false),
                    fge("travel_radius_km",6,false)));

        ensureFieldGroup("caterer_menu",        "Menu & Cuisine",
            List.of(fge("cuisine_types",0,false), fge("meal_types",1,false),
                    fge("min_guests",2,false), fge("max_guests",3,false),
                    fge("provides_staff",4,false), fge("provides_cutlery",5,false)));

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

        ensureFieldGroup("tax_info",            "Tax Information",
            List.of(fge("gstin",0,false), fge("pan_number",1,true),
                    fge("tax_registration",2,false), fge("service_tax_no",3,false)));

        ensureFieldGroup("bank_info",           "Bank Details",
            List.of(fge("account_number",0,false), fge("ifsc_code",1,false),
                    fge("account_holder",2,false), fge("bank_name",3,false)));
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
                sec("pricing",               "Pricing",        3),
                sec("media",                 "Portfolio",      4)
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
                sec("pricing",           "Pricing",          3),
                sec("media",             "Portfolio",        4)
            ), true, true);

        ensureListingType("MAKEUP_ARTIST",  "Makeup Artist",  "Professional makeup artist",
            List.of(
                sec("basic_details",   "Basic Details", 0),
                sec("makeup_profile",  "Profile",       1),
                sec("contact_details", "Contact",       2),
                sec("pricing",         "Pricing",       3),
                sec("media",           "Portfolio",     4)
            ), true, true);

        ensureListingType("WEDDING_EVENT",  "Wedding Event",  "End-to-end wedding event management",
            List.of(
                sec("basic_details",   "Event Overview", 0),
                sec("wedding_details", "Event Details",  1),
                sec("contact_details", "Contact",        2),
                sec("media",           "Media",          3)
            ), true, true);

        ensureListingType("CORPORATE_EVENT","Corporate Event","Corporate and business event management",
            List.of(
                sec("basic_details",     "Event Overview",    0),
                sec("corporate_details", "Corporate Details", 1),
                sec("contact_details",   "Contact",           2),
                sec("media",             "Media",             3)
            ), true, true);

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
            if (docReqRepo.findByCodeAndOrgIdIsNull(s.code()).isEmpty()) {
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
        if (fieldGroupRepo.findByNameAndOrgIdIsNull(name).isPresent()) return;

        FieldGroup group = new FieldGroup();
        group.setName(name);
        group.setLabel(label);

        List<FieldGroupEntry> entries = new ArrayList<>();
        for (FgeSpec spec : entrySpecs) {
            fieldRepo.findByNameAndOrgIdIsNull(spec.fieldName()).ifPresent(fd -> {
                FieldGroupEntry entry = new FieldGroupEntry();
                entry.setFieldGroup(group);
                entry.setField(fd);
                entry.setDisplayOrder(spec.displayOrder());
                entry.setRequired(spec.required());
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
        if (listingTypeRepo.findByNameAndOrgIdIsNull(name).isPresent()) return;

        ListingTypeDefinition def = new ListingTypeDefinition();
        def.setName(name);
        def.setLabel(label);
        def.setDescription(description);
        def.setPublishable(publishable);
        def.setConsumerSearchable(consumerSearchable);

        List<ListingTypeSection> secs = new ArrayList<>();
        for (SecSpec spec : sections) {
            fieldGroupRepo.findByNameAndOrgIdIsNull(spec.groupName()).ifPresent(fg -> {
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
        return categoryRepo.findBySlugAndOrgIdIsNull(slug).orElseGet(() -> {
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
        return new FgeSpec(fieldName, displayOrder, required);
    }

    private SecSpec sec(String groupName, String label, int order) {
        return new SecSpec(groupName, label, order);
    }

    private record FieldSpec(String name, String label, FieldType fieldType,
                              boolean required, boolean searchable, List<String> enumValues) {}

    private record FgeSpec(String fieldName, int displayOrder, boolean required) {}

    private record SecSpec(String groupName, String label, int order) {}
}
