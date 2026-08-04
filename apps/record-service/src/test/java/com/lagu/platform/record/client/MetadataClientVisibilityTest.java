package com.lagu.platform.record.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.common.visibility.VisibilityRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Contract test for the one hop nothing else covers: schema-registry's JSON arriving over HTTP and
 * being deserialized by {@link MetadataClient}.
 *
 * <p>Two things are only exercised here. First, {@code visibleWhen} surviving deserialization into
 * MetadataClient's own wire records — schema-registry's DTOs and these are separate declarations
 * that only agree by convention. Second, {@code combineRules}: sections are flattened away at this
 * boundary, so a section's rule has to be folded into each of its fields or it is silently lost,
 * and a section-level rule would stop being enforced server-side.
 *
 * <p>The payload below mirrors {@code ListingTypeSchemaDto}. If that record changes shape, this
 * JSON must change with it — that coupling is the point of the test.
 */
class MetadataClientVisibilityTest {

    private static final String SCHEMA_JSON = """
        {
          "success": true,
          "data": {
            "listingType": "WEDDING_EVENT",
            "version": 4,
            "sections": [
              {
                "sectionKey": "event_visibility",
                "label": "Visibility",
                "displayOrder": 0,
                "visibleWhen": null,
                "fields": [
                  {
                    "key": "is_virtual", "label": "Is Virtual", "fieldType": "BOOLEAN",
                    "required": false, "promoted": false, "searchable": false, "filterable": false,
                    "facetable": false, "rangeFilterable": false, "arrayManageable": false,
                    "enumValues": null, "itemSchema": null, "validationRules": null,
                    "visibleWhen": null
                  },
                  {
                    "key": "virtual_meeting_url", "label": "Meeting URL", "fieldType": "URL",
                    "required": false, "promoted": false, "searchable": false, "filterable": false,
                    "facetable": false, "rangeFilterable": false, "arrayManageable": false,
                    "enumValues": null, "itemSchema": null, "validationRules": null,
                    "visibleWhen": {"all": [{"field": "is_virtual", "op": "truthy"}]}
                  }
                ]
              },
              {
                "sectionKey": "outdoor",
                "label": "Outdoor Details",
                "displayOrder": 1,
                "visibleWhen": {"all": [{"field": "venue_type", "op": "eq", "value": "OUTDOOR"}]},
                "fields": [
                  {
                    "key": "rain_plan", "label": "Rain Plan", "fieldType": "TEXT",
                    "required": true, "promoted": false, "searchable": false, "filterable": false,
                    "facetable": false, "rangeFilterable": false, "arrayManageable": false,
                    "enumValues": null, "itemSchema": null, "validationRules": null,
                    "visibleWhen": null
                  },
                  {
                    "key": "marquee_size", "label": "Marquee Size", "fieldType": "TEXT",
                    "required": false, "promoted": false, "searchable": false, "filterable": false,
                    "facetable": false, "rangeFilterable": false, "arrayManageable": false,
                    "enumValues": null, "itemSchema": null, "validationRules": null,
                    "visibleWhen": {"all": [{"field": "needs_marquee", "op": "truthy"}]}
                  }
                ]
              }
            ]
          }
        }
        """;

    private MetadataClient metadataClient;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://schema-registry");
        server = MockRestServiceServer.bindTo(builder).build();
        // Constructed directly rather than through Spring, so @Cacheable does not apply and each
        // test gets a fresh fetch.
        metadataClient = new MetadataClient(builder.build(), new ObjectMapper());

        server.expect(requestTo("http://schema-registry/api/v1/listing-types/WEDDING_EVENT/schema"))
                .andRespond(withSuccess(SCHEMA_JSON, MediaType.APPLICATION_JSON));
    }

    private MetadataClient.FieldSchemaDto field(String key) {
        return metadataClient.getSchema("WEDDING_EVENT").fields().stream()
                .filter(f -> f.name().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("field '" + key + "' missing after flattening"));
    }

    @Test
    void aFieldRuleSurvivesDeserialization() {
        var rule = field("virtual_meeting_url").visibleWhen();

        assertThat(rule).isNotNull();
        assertThat(VisibilityRules.isVisible(rule, Map.of("is_virtual", true))).isTrue();
        assertThat(VisibilityRules.isVisible(rule, Map.of("is_virtual", false))).isFalse();
    }

    @Test
    void aSectionRuleIsInheritedByItsFields() {
        // rain_plan has no rule of its own; without folding, its section's rule would be lost and
        // the field would be treated as always required.
        var rule = field("rain_plan").visibleWhen();

        assertThat(rule).isNotNull();
        assertThat(VisibilityRules.isVisible(rule, Map.of("venue_type", "OUTDOOR"))).isTrue();
        assertThat(VisibilityRules.isVisible(rule, Map.of("venue_type", "INDOOR"))).isFalse();
    }

    @Test
    void sectionAndFieldRulesAreCombinedWithAnd() {
        var rule = field("marquee_size").visibleWhen();

        assertThat(VisibilityRules.isVisible(rule,
                Map.of("venue_type", "OUTDOOR", "needs_marquee", true))).isTrue();
        // Either half being false hides the field.
        assertThat(VisibilityRules.isVisible(rule,
                Map.of("venue_type", "INDOOR", "needs_marquee", true))).isFalse();
        assertThat(VisibilityRules.isVisible(rule,
                Map.of("venue_type", "OUTDOOR", "needs_marquee", false))).isFalse();
    }

    @Test
    void fieldsWithNoRuleAnywhereStayUnconditional() {
        assertThat(field("is_virtual").visibleWhen()).isNull();
    }

    @Test
    void theSchemaVersionIsCarriedThroughForRecordStamping() {
        // What RecordService stamps onto a record and re-stamps on every write (ADR-11).
        assertThat(metadataClient.getSchema("WEDDING_EVENT").version()).isEqualTo(4);
    }
}
