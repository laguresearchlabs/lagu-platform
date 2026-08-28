package com.lagu.platform.search.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both bodies are sent to OpenSearch and both failures are swallowed by design — search-service
 * refusing to start because a retention policy would not install is worse than the policy being
 * absent. That makes a malformed body invisible except as one warning line, so what a test can
 * usefully check is that these say what they are meant to say.
 *
 * <p>The mapping in particular is worth pinning: an index mapping cannot be changed after the fact,
 * so if this ships wrong, every document written until someone notices has to be reindexed.
 */
class AnalyticsIndexInitializerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AnalyticsIndexInitializer initializer =
            new AnalyticsIndexInitializer(null, "platform", 180);

    private JsonNode parse(String body) throws Exception {
        return JSON.readTree(body);
    }

    @Test
    void theIndexTemplateIsValidJson() throws Exception {
        assertThat(parse(initializer.indexTemplateBody())).isNotNull();
    }

    @Test
    void everyIdAndEnumIsAKeyword() throws Exception {
        // The reason the template exists. Dynamic mapping would make these `text` with a `.keyword`
        // sub-field: it analyses UUIDs for full-text search, which is wasted index, and forces every
        // aggregation to spell out `eventType.keyword`.
        JsonNode props = parse(initializer.indexTemplateBody())
                .path("template").path("mappings").path("properties");

        for (String field : new String[]{"eventType", "subjectId", "tenantId", "objectType",
                                         "viewerUserId", "sessionId", "source"}) {
            assertThat(props.path(field).path("type").asText())
                    .as("%s must be a keyword", field).isEqualTo("keyword");
        }
    }

    @Test
    void theTimestampIsADateSoRangeQueriesWork() throws Exception {
        // Monthly indices are only useful if you can query a time range across them.
        JsonNode occurredAt = parse(initializer.indexTemplateBody())
                .path("template").path("mappings").path("properties").path("occurredAt");

        assertThat(occurredAt.path("type").asText()).isEqualTo("date");
        assertThat(occurredAt.path("format").asText()).contains("strict_date_time");
    }

    @Test
    void theTemplateAttachesTheRetentionPolicy() throws Exception {
        // Otherwise each new month's index is created outside the policy and never expires, which
        // is exactly the unbounded growth the policy exists to stop.
        JsonNode settings = parse(initializer.indexTemplateBody())
                .path("template").path("settings");

        assertThat(settings.path("opendistro.index_state_management.policy_id").asText())
                .isEqualTo("platform-analytics-retention");
    }

    @Test
    void theTemplateOnlyClaimsTheAnalyticsIndices() throws Exception {
        // A pattern that matched more would attach a delete-after-180-days policy to indices that
        // must never be deleted on a timer — the consumer indexes back marketplace search.
        JsonNode patterns = parse(initializer.indexTemplateBody()).path("index_patterns");

        assertThat(patterns).hasSize(1);
        assertThat(patterns.get(0).asText()).isEqualTo("platform-analytics-*");
    }

    @Test
    void theRetentionPolicyIsValidJsonAndDeletesOnSchedule() throws Exception {
        JsonNode policy = parse(initializer.retentionPolicyBody()).path("policy");

        assertThat(policy.path("default_state").asText()).isEqualTo("hot");

        JsonNode transition = policy.path("states").get(0).path("transitions").get(0);
        assertThat(transition.path("state_name").asText()).isEqualTo("delete");
        assertThat(transition.path("conditions").path("min_index_age").asText()).isEqualTo("180d");

        JsonNode deleteState = policy.path("states").get(1);
        assertThat(deleteState.path("name").asText()).isEqualTo("delete");
        assertThat(deleteState.path("actions").get(0).has("delete")).isTrue();
    }

    @Test
    void theConfiguredRetentionReachesBothPlaces() throws Exception {
        // The number appears in the transition condition and in the human-readable description;
        // a change that updated only one would be quietly misleading.
        AnalyticsIndexInitializer shortLived = new AnalyticsIndexInitializer(null, "platform", 30);
        JsonNode policy = parse(shortLived.retentionPolicyBody()).path("policy");

        assertThat(policy.path("states").get(0).path("transitions").get(0)
                .path("conditions").path("min_index_age").asText()).isEqualTo("30d");
        assertThat(policy.path("description").asText()).contains("30 days");
    }

    @Test
    void theIndexPrefixIsHonouredEverywhere() throws Exception {
        // A stack running with a non-default prefix must not have the template silently claim
        // nothing, nor the policy target another deployment's indices.
        AnalyticsIndexInitializer prefixed = new AnalyticsIndexInitializer(null, "staging", 180);

        assertThat(parse(prefixed.indexTemplateBody()).path("index_patterns").get(0).asText())
                .isEqualTo("staging-analytics-*");
        assertThat(parse(prefixed.retentionPolicyBody()).path("policy")
                .path("ism_template").get(0).path("index_patterns").get(0).asText())
                .isEqualTo("staging-analytics-*");
    }
}
