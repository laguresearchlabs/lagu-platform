package com.lagu.platform.metadata.config;

import com.lagu.platform.metadata.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class MetadataSeeder implements ApplicationRunner {

    private final AttributeDefinitionRepository      attrRepo;
    private final EntityDefinitionRepository         entityRepo;
    private final ObjectTypeDefinitionRepository     objectTypeRepo;
    private final RelationshipDefinitionRepository   relDefRepo;
    private final DocumentTypeDefinitionRepository   docTypeRepo;
    private final TierConfigurationRepository        tierRepo;
    private final CountryValidationConfigRepository  countryRepo;
    private final CategoryDefinitionRepository       categoryRepo;

    @Value("${platform.seeder.enabled:true}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        log.info("Running MetadataSeeder...");
        seedAttributes();
        seedEntities();
        seedObjectTypes();
        seedRelationshipDefinitions();
        seedDocumentTypes();
        seedTierConfigurations();
        seedCountryValidationConfigs();
        seedCategories();
        log.info("MetadataSeeder complete");
    }

    // ── 1. Platform-level attributes ─────────────────────────────────────────

    private void seedAttributes() {
        List<AttrSpec> specs = List.of(
            // Shared basics
            attr("name",              "Name",             AttributeType.TEXT,         true,  true,  null),
            attr("description",       "Description",      AttributeType.LONG_TEXT,    false, false, null),
            // Contact
            attr("phone",             "Phone",            AttributeType.PHONE,        false, false, null),
            attr("email",             "Email",            AttributeType.EMAIL,        false, true,  null),
            attr("website",           "Website",          AttributeType.URL,          false, false, null),
            // Address
            attr("address_line1",     "Address Line 1",   AttributeType.TEXT,         false, false, null),
            attr("address_line2",     "Address Line 2",   AttributeType.TEXT,         false, false, null),
            attr("city",              "City",             AttributeType.TEXT,         false, true,  null),
            attr("state",             "State",            AttributeType.TEXT,         false, true,  null),
            attr("country",           "Country",          AttributeType.TEXT,         false, true,  null),
            attr("postal_code",       "Postal Code",      AttributeType.TEXT,         false, false, null),
            attr("latitude",          "Latitude",         AttributeType.DECIMAL,      false, false, null),
            attr("longitude",         "Longitude",        AttributeType.DECIMAL,      false, false, null),
            // Pricing
            attr("pricing_model",     "Pricing Model",    AttributeType.ENUM,         false, true,
                List.of("FIXED","PER_HOUR","PER_DAY","PER_PERSON","PER_PLATE","PACKAGE","CUSTOM_QUOTE")),
            attr("price",             "Price",            AttributeType.DECIMAL,      false, true,  null),
            attr("currency",          "Currency",         AttributeType.ENUM,         false, false,
                List.of("INR","USD","EUR","GBP")),
            attr("tax_percent",       "Tax %",            AttributeType.DECIMAL,      false, false, null),
            attr("min_price",         "Min Price",        AttributeType.DECIMAL,      false, false, null),
            attr("max_price",         "Max Price",        AttributeType.DECIMAL,      false, false, null),
            // Media
            attr("cover_image",       "Cover Image",      AttributeType.IMAGE,        false, false, null),
            attr("gallery",           "Gallery",          AttributeType.MULTI_SELECT, false, false, null),
            attr("video_url",         "Video URL",        AttributeType.URL,          false, false, null),
            // Venue specific
            attr("capacity",          "Capacity",         AttributeType.NUMBER,       false, true,  null),
            attr("venue_type",        "Venue Type",       AttributeType.ENUM,         false, true,
                List.of("BANQUET_HALL","OUTDOOR","ROOFTOP","RESORT","HOTEL","FARMHOUSE","BEACH")),
            attr("parking_slots",     "Parking Slots",    AttributeType.NUMBER,       false, true,  null),
            attr("has_catering",      "In-house Catering",AttributeType.BOOLEAN,      false, true,  null),
            attr("has_dj",            "DJ Facility",      AttributeType.BOOLEAN,      false, false, null),
            attr("decoration",        "Decoration",       AttributeType.BOOLEAN,      false, false, null),
            attr("ac_available",      "AC Available",     AttributeType.BOOLEAN,      false, true,  null),
            attr("amenities",         "Amenities",        AttributeType.MULTI_SELECT, false, true,
                List.of("WIFI","GENERATOR","VALET","SWIMMING_POOL","GYM","BAR","STAGE")),
            // Photographer specific
            attr("experience_years",  "Experience (yrs)", AttributeType.NUMBER,       false, true,  null),
            attr("specializations",   "Specializations",  AttributeType.MULTI_SELECT, false, true,
                List.of("WEDDING","PORTRAIT","CORPORATE","FASHION","PRODUCT","MATERNITY","NEWBORN")),
            attr("camera_brands",     "Camera Brands",    AttributeType.MULTI_SELECT, false, false,
                List.of("CANON","NIKON","SONY","FUJIFILM","LEICA","OLYMPUS")),
            attr("editing_software",  "Editing Software", AttributeType.MULTI_SELECT, false, false,
                List.of("LIGHTROOM","PHOTOSHOP","CAPTURE_ONE","DARKTABLE")),
            attr("delivery_days",     "Delivery Days",    AttributeType.NUMBER,       false, false, null),
            attr("travel_allowed",    "Travel Allowed",   AttributeType.BOOLEAN,      false, true,  null),
            attr("travel_radius_km",  "Travel Radius (km)",AttributeType.NUMBER,      false, false, null),
            // Caterer specific
            attr("cuisine_types",     "Cuisine Types",    AttributeType.MULTI_SELECT, false, true,
                List.of("NORTH_INDIAN","SOUTH_INDIAN","CHINESE","CONTINENTAL","MUGHLAI","RAJASTHANI","MEDITERRANEAN")),
            attr("meal_types",        "Meal Types",       AttributeType.MULTI_SELECT, false, true,
                List.of("VEG","NON_VEG","VEGAN","JAIN")),
            attr("min_guests",        "Min Guests",       AttributeType.NUMBER,       false, true,  null),
            attr("max_guests",        "Max Guests",       AttributeType.NUMBER,       false, true,  null),
            attr("provides_staff",    "Staff Included",   AttributeType.BOOLEAN,      false, false, null),
            attr("provides_cutlery",  "Cutlery Included", AttributeType.BOOLEAN,      false, false, null),
            // Decorator specific
            attr("style_types",       "Style Types",      AttributeType.MULTI_SELECT, false, true,
                List.of("FLORAL","MODERN","TRADITIONAL","ROYAL","BEACH","MINIMALIST","RUSTIC")),
            attr("event_types_dec",   "Event Types",      AttributeType.MULTI_SELECT, false, true,
                List.of("WEDDING","BIRTHDAY","CORPORATE","BABY_SHOWER","ANNIVERSARY")),
            attr("price_per_sqft",    "Price/sqft",       AttributeType.DECIMAL,      false, false, null),
            attr("includes_flowers",  "Flowers Included", AttributeType.BOOLEAN,      false, false, null),
            attr("includes_lights",   "Lights Included",  AttributeType.BOOLEAN,      false, false, null),
            // Makeup artist specific
            attr("makeup_specializations","Specializations",AttributeType.MULTI_SELECT,false, true,
                List.of("BRIDAL","PARTY","AIRBRUSH","EDITORIAL","THEATRICAL")),
            attr("home_service",      "Home Service",     AttributeType.BOOLEAN,      false, true,  null),
            attr("brands_used",       "Brands Used",      AttributeType.MULTI_SELECT, false, false,
                List.of("MAC","HUDA","BOBBI_BROWN","KRYOLAN","NARS","CHARLOTTE_TILBURY")),
            // Event shared
            attr("event_date",        "Event Date",       AttributeType.DATE,         true,  true,  null),
            attr("expected_guests",   "Expected Guests",  AttributeType.NUMBER,       false, true,  null),
            attr("budget_inr",        "Budget (INR)",     AttributeType.DECIMAL,      false, false, null),
            attr("venue_ref",         "Venue",            AttributeType.ENTITY_REFERENCE,false,false,null),
            // Wedding event specific
            attr("event_sub_types",   "Sub-Events",       AttributeType.MULTI_SELECT, false, false,
                List.of("MEHENDI","HALDI","SANGEET","RECEPTION","PHERAS")),
            attr("event_time",        "Event Time",       AttributeType.TIME,         false, false, null),
            // Corporate event specific
            attr("company_name",      "Company Name",     AttributeType.TEXT,         false, true,  null),
            attr("corporate_event_type","Event Type",     AttributeType.ENUM,         false, true,
                List.of("CONFERENCE","SEMINAR","PRODUCT_LAUNCH","TEAM_OUTING","AWARDS_CEREMONY")),
            attr("expected_attendees","Attendees",        AttributeType.NUMBER,       false, true,  null),
            attr("requires_recording","Recording Needed", AttributeType.BOOLEAN,      false, false, null),
            attr("requires_streaming","Live Streaming",   AttributeType.BOOLEAN,      false, false, null),
            // General
            attr("is_active",         "Active",           AttributeType.BOOLEAN,      false, true,  null),
            // Vendor identity / KYC
            attr("business_name",     "Business Name",    AttributeType.TEXT,         true,  true,  null),
            attr("business_type",     "Business Type",    AttributeType.ENUM,         true,  true,
                List.of("SOLE_PROPRIETORSHIP","PARTNERSHIP","PRIVATE_LIMITED","LLP","PUBLIC_LIMITED")),
            attr("gstin",             "GSTIN",            AttributeType.TEXT,         false, false, null),
            attr("pan_number",        "PAN Number",       AttributeType.TEXT,         true,  false, null),
            attr("company_reg_no",    "Company Reg No.",  AttributeType.TEXT,         false, false, null),
            // Vendor bank info
            attr("account_number",    "Account Number",   AttributeType.TEXT,         false, false, null),
            attr("ifsc_code",         "IFSC Code",        AttributeType.TEXT,         false, false, null),
            attr("account_holder",    "Account Holder",   AttributeType.TEXT,         false, false, null),
            attr("bank_name",         "Bank Name",        AttributeType.TEXT,         false, false, null),
            // Vendor tax info
            attr("tax_registration",  "Tax Registration", AttributeType.TEXT,         false, false, null),
            attr("service_tax_no",    "Service Tax No.",  AttributeType.TEXT,         false, false, null)
        );

        int seeded = 0;
        for (AttrSpec s : specs) {
            if (attrRepo.findByNameAndOrgIdIsNull(s.name()).isEmpty()) {
                AttributeDefinition def = new AttributeDefinition();
                def.setName(s.name());
                def.setLabel(s.label());
                def.setAttributeType(s.type());
                def.setRequired(s.required());
                def.setSearchable(s.searchable());
                def.setFilterable(s.searchable());
                if (s.enumValues() != null) def.setEnumValues(s.enumValues());
                attrRepo.save(def);
                seeded++;
            }
        }
        if (seeded > 0) log.info("Seeded {} platform attributes", seeded);
    }

    // ── 2. Shared entities ────────────────────────────────────────────────────

    private void seedEntities() {
        // basic_details — used as first section for every object type
        EntityDefinition basics = ensureEntity("basic_details", "Basic Details",
            List.of(ea("name", 0, true), ea("description", 1, false)));

        ensureEntity("contact_details", "Contact Details",
            List.of(ea("phone", 0, false), ea("email", 1, false), ea("website", 2, false)));

        ensureEntity("address", "Address",
            List.of(ea("address_line1",0,false), ea("address_line2",1,false), ea("city",2,false),
                    ea("state",3,false), ea("country",4,false), ea("postal_code",5,false),
                    ea("latitude",6,false), ea("longitude",7,false)));

        ensureEntity("pricing", "Pricing",
            List.of(ea("pricing_model",0,false), ea("price",1,false), ea("currency",2,false),
                    ea("tax_percent",3,false), ea("min_price",4,false), ea("max_price",5,false)));

        ensureEntity("media", "Media",
            List.of(ea("cover_image",0,false), ea("gallery",1,false), ea("video_url",2,false)));

        // Venue extras
        ensureEntity("venue_details", "Venue Details",
            List.of(ea("capacity",0,true), ea("venue_type",1,false), ea("parking_slots",2,false),
                    ea("has_catering",3,false), ea("has_dj",4,false), ea("decoration",5,false),
                    ea("ac_available",6,false), ea("amenities",7,false)));

        // Photographer extras
        ensureEntity("photographer_profile", "Photographer Profile",
            List.of(ea("experience_years",0,false), ea("specializations",1,false),
                    ea("camera_brands",2,false), ea("editing_software",3,false),
                    ea("delivery_days",4,false), ea("travel_allowed",5,false),
                    ea("travel_radius_km",6,false)));

        // Caterer extras
        ensureEntity("caterer_menu", "Menu & Cuisine",
            List.of(ea("cuisine_types",0,false), ea("meal_types",1,false),
                    ea("min_guests",2,false), ea("max_guests",3,false),
                    ea("provides_staff",4,false), ea("provides_cutlery",5,false)));

        // Decorator extras
        ensureEntity("decorator_profile", "Decorator Profile",
            List.of(ea("style_types",0,false), ea("event_types_dec",1,false),
                    ea("price_per_sqft",2,false), ea("includes_flowers",3,false),
                    ea("includes_lights",4,false)));

        // Makeup artist extras
        ensureEntity("makeup_profile", "Makeup Profile",
            List.of(ea("makeup_specializations",0,false), ea("home_service",1,false),
                    ea("brands_used",2,false)));

        // Wedding event
        ensureEntity("wedding_details", "Wedding Details",
            List.of(ea("event_date",0,true), ea("event_time",1,false),
                    ea("expected_guests",2,false), ea("event_sub_types",3,false),
                    ea("budget_inr",4,false), ea("venue_ref",5,false)));

        // Corporate event
        ensureEntity("corporate_details", "Corporate Details",
            List.of(ea("company_name",0,false), ea("corporate_event_type",1,false),
                    ea("event_date",2,true), ea("expected_attendees",3,false),
                    ea("budget_inr",4,false), ea("venue_ref",5,false),
                    ea("requires_recording",6,false), ea("requires_streaming",7,false)));
    }

    // ── 3. Object types ───────────────────────────────────────────────────────

    private void seedObjectTypes() {
        ensureObjectType("VENUE", "Venue", "A bookable event venue",
            List.of(
                sec("basic_details",    "Basic Details",   0),
                sec("venue_details",    "Venue Details",   1),
                sec("contact_details",  "Contact",         2),
                sec("address",          "Address",         3),
                sec("pricing",          "Pricing",         4),
                sec("media",            "Gallery & Media", 5)
            ));

        ensureObjectType("PHOTOGRAPHER", "Photographer", "Professional photographer",
            List.of(
                sec("basic_details",        "Basic Details",    0),
                sec("photographer_profile", "Profile",          1),
                sec("contact_details",      "Contact",          2),
                sec("pricing",              "Pricing",          3),
                sec("media",                "Portfolio",        4)
            ));

        ensureObjectType("CATERER", "Caterer", "Catering and food service provider",
            List.of(
                sec("basic_details",    "Basic Details",   0),
                sec("caterer_menu",     "Menu & Cuisine",  1),
                sec("contact_details",  "Contact",         2),
                sec("address",          "Address",         3),
                sec("pricing",          "Pricing",         4),
                sec("media",            "Gallery",         5)
            ));

        ensureObjectType("DECORATOR", "Decorator", "Event decoration specialist",
            List.of(
                sec("basic_details",     "Basic Details",    0),
                sec("decorator_profile", "Decorator Profile",1),
                sec("contact_details",   "Contact",          2),
                sec("pricing",           "Pricing",          3),
                sec("media",             "Portfolio",        4)
            ));

        ensureObjectType("MAKEUP_ARTIST", "Makeup Artist", "Professional makeup artist",
            List.of(
                sec("basic_details",  "Basic Details", 0),
                sec("makeup_profile", "Profile",       1),
                sec("contact_details","Contact",       2),
                sec("pricing",        "Pricing",       3),
                sec("media",          "Portfolio",     4)
            ));

        ensureObjectType("WEDDING_EVENT", "Wedding Event", "End-to-end wedding event management",
            List.of(
                sec("basic_details",   "Event Overview", 0),
                sec("wedding_details", "Event Details",  1),
                sec("contact_details", "Contact",        2),
                sec("media",           "Media",          3)
            ));

        ensureObjectType("CORPORATE_EVENT", "Corporate Event", "Corporate and business event management",
            List.of(
                sec("basic_details",    "Event Overview",    0),
                sec("corporate_details","Corporate Details", 1),
                sec("contact_details",  "Contact",           2),
                sec("media",            "Media",             3)
            ));

        // VENDOR represents an org-level vendor business profile (KYC, bank, tax).
        // Listing-level service details live in VENUE/PHOTOGRAPHER/CATERER/DECORATOR/MAKEUP_ARTIST records.
        ensureObjectType("VENDOR", "Vendor", "Vendor business profile and KYC information",
            List.of(
                sec("basic_details",   "Business Identity", 0),
                sec("contact_details", "Contact",           1),
                sec("address",         "Address",           2),
                sec("tax_info",        "Tax Information",   3),
                sec("bank_info",       "Bank Details",      4)
            ));
    }

    // ── 4. Relationship definitions ───────────────────────────────────────────

    private void seedRelationshipDefinitions() {
        List<RelDefSpec> specs = List.of(
            relDef("EVENT_VENUE",          "Event Venue",          "WEDDING_EVENT", "VENUE",         "ONE_TO_ONE",   false, false),
            relDef("EVENT_PHOTOGRAPHERS",  "Event Photographers",  "WEDDING_EVENT", "PHOTOGRAPHER",  "MANY_TO_MANY", false, false),
            relDef("EVENT_CATERERS",       "Event Caterers",       "WEDDING_EVENT", "CATERER",       "MANY_TO_MANY", false, false),
            relDef("EVENT_DECORATORS",     "Event Decorators",     "WEDDING_EVENT", "DECORATOR",     "MANY_TO_MANY", false, false),
            relDef("EVENT_MAKEUP_ARTISTS", "Event Makeup Artists", "WEDDING_EVENT", "MAKEUP_ARTIST", "MANY_TO_MANY", false, false)
        );

        int seeded = 0;
        for (RelDefSpec s : specs) {
            if (relDefRepo.findByNameAndOrgIdIsNull(s.name()).isEmpty()) {
                RelationshipDefinition def = new RelationshipDefinition();
                def.setName(s.name());
                def.setLabel(s.label());
                def.setSourceObjectType(s.sourceType());
                def.setTargetObjectType(s.targetType());
                def.setRelationshipType(s.relType());
                def.setRequired(s.required());
                def.setCascadeDelete(s.cascadeDelete());
                relDefRepo.save(def);
                seeded++;
            }
        }
        if (seeded > 0) log.info("Seeded {} relationship definitions", seeded);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private EntityDefinition ensureEntity(String name, String label, List<EaSpec> attrSpecs) {
        return entityRepo.findByNameAndOrgIdIsNull(name).orElseGet(() -> {
            EntityDefinition entity = new EntityDefinition();
            entity.setName(name);
            entity.setLabel(label);
            entity.setActive(true);

            List<EntityAttribute> attrs = new ArrayList<>();
            for (EaSpec spec : attrSpecs) {
                attrRepo.findByNameAndOrgIdIsNull(spec.attrName()).ifPresent(attrDef -> {
                    EntityAttribute ea = new EntityAttribute();
                    ea.setEntity(entity);
                    ea.setAttribute(attrDef);
                    ea.setDisplayOrder(spec.order());
                    ea.setRequired(spec.required());
                    attrs.add(ea);
                });
            }
            entity.setAttributes(attrs);

            EntityDefinition saved = entityRepo.save(entity);
            log.debug("Seeded entity: {}", name);
            return saved;
        });
    }

    private void ensureObjectType(String name, String label, String description, List<SecSpec> sections) {
        if (objectTypeRepo.findByNameAndOrgIdIsNull(name).isPresent()) return;

        ObjectTypeDefinition ot = new ObjectTypeDefinition();
        ot.setName(name);
        ot.setLabel(label);
        ot.setDescription(description);
        ot.setPublishable(true);
        ot.setActive(true);

        List<ObjectTypeSection> secs = new ArrayList<>();
        for (SecSpec spec : sections) {
            entityRepo.findByNameAndOrgIdIsNull(spec.entityName()).ifPresent(entity -> {
                ObjectTypeSection sec = new ObjectTypeSection();
                sec.setObjectType(ot);
                sec.setEntity(entity);
                sec.setLabel(spec.label());
                sec.setDisplayOrder(spec.order());
                secs.add(sec);
            });
        }
        ot.setSections(secs);
        objectTypeRepo.save(ot);
        log.debug("Seeded object type: {}", name);
    }

    private AttrSpec attr(String name, String label, AttributeType type,
                          boolean required, boolean searchable, List<String> enumValues) {
        return new AttrSpec(name, label, type, required, searchable, enumValues);
    }

    private EaSpec ea(String attrName, int order, boolean required) {
        return new EaSpec(attrName, order, required);
    }

    private SecSpec sec(String entityName, String label, int order) {
        return new SecSpec(entityName, label, order);
    }

    private RelDefSpec relDef(String name, String label, String sourceType, String targetType,
                               String relType, boolean required, boolean cascadeDelete) {
        return new RelDefSpec(name, label, sourceType, targetType, relType, required, cascadeDelete);
    }

    private record AttrSpec(String name, String label, AttributeType type,
                             boolean required, boolean searchable, List<String> enumValues) {}

    private record EaSpec(String attrName, int order, boolean required) {}

    private record SecSpec(String entityName, String label, int order) {}

    private record RelDefSpec(String name, String label, String sourceType, String targetType,
                               String relType, boolean required, boolean cascadeDelete) {}

    // ── 5. Document Types ─────────────────────────────────────────────────────

    private void seedDocumentTypes() {
        // Vendor business documents
        List<DocTypeSpec> vendorDocs = List.of(
            docType("GST_CERTIFICATE",       "GST Registration Certificate",      "VENDOR", true,  true,  0),
            docType("PAN_CARD",              "PAN Card",                          "VENDOR", true,  false, 1),
            docType("COMPANY_REGISTRATION",  "Company Registration Certificate",  "VENDOR", false, false, 2),
            docType("TRADE_LICENSE",         "Trade License",                     "VENDOR", false, true,  3),
            docType("FSSAI_LICENSE",         "FSSAI License (Caterers only)",     "VENDOR", false, true,  4),
            docType("BANK_CANCELLED_CHEQUE", "Cancelled Cheque / Bank Letter",    "VENDOR", true,  false, 5),
            docType("IDENTITY_PROOF",        "Owner Identity Proof",              "VENDOR", true,  false, 6)
        );
        // HR / employee documents (preserve existing document-service behaviour)
        List<DocTypeSpec> hrDocs = List.of(
            docType("RESUME",               "Resume / CV",                        null,     true,  false, 0),
            docType("HR_IDENTITY_PROOF",    "Government-issued Identity Proof",   null,     true,  false, 1),
            docType("PHOTOGRAPH",           "Passport-size Photograph",           null,     true,  false, 2),
            docType("ACADEMIC_CERTIFICATE", "Academic Certificates / Mark Sheets",null,     false, false, 3),
            docType("ADDRESS_PROOF",        "Address Proof",                      null,     false, false, 4),
            docType("OTHER",                "Additional Documents",               null,     false, false, 5)
        );

        int seeded = 0;
        for (DocTypeSpec s : vendorDocs) {
            if (docTypeRepo.findByCodeAndOrgIdIsNull(s.code()).isEmpty()) {
                seeded += saveDocType(s);
            }
        }
        for (DocTypeSpec s : hrDocs) {
            if (docTypeRepo.findByCodeAndOrgIdIsNull(s.code()).isEmpty()) {
                seeded += saveDocType(s);
            }
        }
        if (seeded > 0) log.info("Seeded {} document type(s)", seeded);
    }

    private int saveDocType(DocTypeSpec s) {
        DocumentTypeDefinition d = new DocumentTypeDefinition();
        d.setCode(s.code());
        d.setLabel(s.label());
        d.setObjectType(s.objectType());
        d.setRequired(s.required());
        d.setExpiryTracked(s.expiryTracked());
        d.setAllowedMimeTypes(List.of("application/pdf", "image/jpeg", "image/png"));
        d.setMaxSizeMb(5);
        d.setDisplayOrder(s.order());
        docTypeRepo.save(d);
        return 1;
    }

    private DocTypeSpec docType(String code, String label, String objectType,
                                boolean required, boolean expiryTracked, int order) {
        return new DocTypeSpec(code, label, objectType, required, expiryTracked, order);
    }

    private record DocTypeSpec(String code, String label, String objectType,
                                boolean required, boolean expiryTracked, int order) {}

    // ── 6. Tier Configurations ────────────────────────────────────────────────

    private void seedTierConfigurations() {
        record TierSpec(String name, double commission, Integer maxBookings,
                        double boost, int slaHours, Map<String, Object> features) {}

        List<TierSpec> tiers = List.of(
            new TierSpec("NONE",    20.0, 3,    1.0, 72, Map.of()),
            new TierSpec("BASIC",   15.0, 10,   1.5, 48, Map.of(
                    "priority_support", false, "analytics", false, "featured_badge", false)),
            new TierSpec("PREMIUM", 10.0, null, 2.0, 24, Map.of(
                    "priority_support", true,  "analytics", true,  "featured_badge", true))
        );

        int seeded = 0;
        for (TierSpec s : tiers) {
            if (tierRepo.findByTierNameAndObjectTypeIsNull(s.name()).isEmpty()) {
                TierConfiguration t = new TierConfiguration();
                t.setTierName(s.name());
                t.setCommissionRate(new java.math.BigDecimal(String.valueOf(s.commission())));
                t.setMaxActiveBookings(s.maxBookings());
                t.setSearchBoostFactor(new java.math.BigDecimal(String.valueOf(s.boost())));
                t.setResponseSlaHours(s.slaHours());
                t.setFeatures(s.features());
                tierRepo.save(t);
                seeded++;
            }
        }
        if (seeded > 0) log.info("Seeded {} tier configuration(s)", seeded);
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
            "pan",   Map.of("pattern", "[A-Z]{5}[0-9]{4}[A-Z]{1}", "label", "PAN Number"),
            "gstin", Map.of("pattern",
                    "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$",
                    "label", "GSTIN"),
            "ifsc",  Map.of("pattern", "^[A-Z]{4}0[A-Z0-9]{6}$", "label", "IFSC Code"),
            "phone", Map.of("pattern", "^[6-9]\\d{9}$", "label", "Mobile Number"),
            "pincode", Map.of("pattern", "^[1-9][0-9]{5}$", "label", "PIN Code"),
            "account_number", Map.of("pattern", "^[0-9]{9,18}$", "label", "Bank Account Number")
        ));
        countryRepo.save(india);
        log.info("Seeded country validation config: IN");
    }

    // ── 8. Category Definitions ───────────────────────────────────────────────

    private void seedCategories() {
        // Root categories
        CategoryDefinition wedding  = ensureCategory(null, null, "wedding-services",
                "Wedding Services", null, 0);
        CategoryDefinition corporate = ensureCategory(null, null, "corporate-services",
                "Corporate Services", null, 1);

        // Wedding > service types
        CategoryDefinition wVenue   = ensureCategory(wedding, "VENUE",        "wedding-venue",
                "Wedding Venue", null, 0);
        CategoryDefinition wPhoto   = ensureCategory(wedding, "PHOTOGRAPHER", "wedding-photography",
                "Wedding Photography", null, 1);
        CategoryDefinition wCater   = ensureCategory(wedding, "CATERER",      "wedding-catering",
                "Wedding Catering", null, 2);
        CategoryDefinition wDecor   = ensureCategory(wedding, "DECORATOR",    "wedding-decoration",
                "Wedding Decoration", null, 3);
        CategoryDefinition wMakeup  = ensureCategory(wedding, "MAKEUP_ARTIST","wedding-makeup",
                "Bridal Makeup", null, 4);

        // Wedding Photography sub-categories
        ensureCategory(wPhoto, "PHOTOGRAPHER", "wedding-day-photography",
                "Wedding Day", null, 0);
        ensureCategory(wPhoto, "PHOTOGRAPHER", "pre-wedding-photography",
                "Pre-Wedding / Engagement", null, 1);
        ensureCategory(wPhoto, "PHOTOGRAPHER", "candid-photography",
                "Candid Photography", null, 2);
        ensureCategory(wPhoto, "PHOTOGRAPHER", "wedding-videography",
                "Videography", null, 3);

        // Corporate > service types
        ensureCategory(corporate, "VENUE",       "corporate-venue",
                "Corporate Venue", null, 0);
        ensureCategory(corporate, "CATERER",     "corporate-catering",
                "Corporate Catering", null, 1);
        ensureCategory(corporate, "PHOTOGRAPHER","corporate-photography",
                "Event Photography", null, 2);

        log.info("Seeded category definitions");
    }

    private CategoryDefinition ensureCategory(CategoryDefinition parent, String objectType,
                                              String slug, String label, String iconUrl,
                                              int order) {
        return categoryRepo.findBySlugAndOrgIdIsNull(slug).orElseGet(() -> {
            CategoryDefinition c = new CategoryDefinition();
            c.setParent(parent);
            c.setObjectType(objectType);
            c.setSlug(slug);
            c.setLabel(label);
            c.setIconUrl(iconUrl);
            c.setDisplayOrder(order);
            return categoryRepo.save(c);
        });
    }
}
