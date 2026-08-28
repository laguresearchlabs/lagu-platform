package com.lagu.platform.search.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.opensearch.client.Request;
import org.opensearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Installs the mapping and retention rules for the analytics indices.
 *
 * <p>Both exist because {@code AnalyticsEventConsumer} writes to a new index each month and lets
 * OpenSearch infer everything, which produces two problems the first document quietly locks in.
 *
 * <p><b>Mapping.</b> Dynamic mapping types every string as {@code text} with a {@code .keyword}
 * sub-field: it analyses UUIDs for full-text search, which is wasted index, and it forces every
 * aggregation to say {@code eventType.keyword}. Analytics reads are almost entirely aggregations,
 * so the ids and enums want to be {@code keyword} outright. A mapping cannot be changed after the
 * fact — the index has to be reindexed — which is why this runs before the first event rather than
 * being left to fix later.
 *
 * <p><b>Retention.</b> Nothing deleted these. Every other store on the platform is bounded, by
 * retention on the Kafka topics or by the size of the working set in Postgres; the analytics
 * indices take one document per listing view, from signed-out visitors, and grow forever. The
 * monthly naming exists so expiry is dropping an index rather than a delete-by-query across
 * everything, but that only helps if something actually drops them.
 *
 * <p>Runs on startup and is idempotent — both endpoints are upserts, and the ISM policy is only
 * written when it is absent, so an operator who tunes it in the dashboard does not have it
 * overwritten on the next pod restart. Failures are logged, never thrown: search-service indexing
 * documents with a suboptimal mapping is far better than search-service refusing to start.
 */
@Slf4j
@Component
public class AnalyticsIndexInitializer {

    private static final String POLICY_ID = "platform-analytics-retention";
    private static final String TEMPLATE_ID = "platform-analytics";

    private final RestClient client;
    private final String indexPrefix;
    private final int retentionDays;

    public AnalyticsIndexInitializer(RestClient openSearchRestClient,
                                     @Value("${opensearch.index-prefix:platform}") String indexPrefix,
                                     @Value("${opensearch.analytics.retention-days:180}") int retentionDays) {
        this.client = openSearchRestClient;
        this.indexPrefix = indexPrefix;
        this.retentionDays = retentionDays;
    }

    private String indexPattern() {
        return indexPrefix + "-analytics-*";
    }

    @EventListener(ApplicationReadyEvent.class)
    public void install() {
        ensureRetentionPolicy();
        ensureIndexTemplate();
    }

    /**
     * The template also attaches the ISM policy to every index it creates, so a month that rolls
     * over at 3am gets its retention without anyone doing anything.
     */
    String indexTemplateBody() {
        return """
                {
                  "index_patterns": ["%s"],
                  "template": {
                    "settings": {
                      "number_of_shards": 1,
                      "number_of_replicas": 1,
                      "plugins.index_state_management.rollover_skip": true,
                      "opendistro.index_state_management.policy_id": "%s"
                    },
                    "mappings": {
                      "properties": {
                        "eventType":    { "type": "keyword" },
                        "subjectId":    { "type": "keyword" },
                        "tenantId":     { "type": "keyword" },
                        "objectType":   { "type": "keyword" },
                        "viewerUserId": { "type": "keyword" },
                        "sessionId":    { "type": "keyword" },
                        "source":       { "type": "keyword" },
                        "occurredAt":   { "type": "date", "format": "strict_date_time||epoch_millis" }
                      }
                    }
                  }
                }
                """.formatted(indexPattern(), POLICY_ID);
    }

    private void ensureIndexTemplate() {
        try {
            Request request = new Request("PUT", "/_index_template/" + TEMPLATE_ID);
            request.setEntity(new NStringEntity(indexTemplateBody(), ContentType.APPLICATION_JSON));
            client.performRequest(request);
            log.info("Installed analytics index template for {} (retention {} days)",
                    indexPattern(), retentionDays);
        } catch (Exception e) {
            // A missing template means new indices fall back to dynamic mapping — worse, not fatal.
            log.warn("Could not install the analytics index template: {}", e.toString());
        }
    }

    private void ensureRetentionPolicy() {
        try {
            client.performRequest(new Request("GET", "/_plugins/_ism/policies/" + POLICY_ID));
            log.debug("Analytics retention policy already present; leaving it alone");
            return;
        } catch (Exception notFound) {
            // Absent, or the plugin is unavailable. Either way, try to write it.
        }

        try {
            Request request = new Request("PUT", "/_plugins/_ism/policies/" + POLICY_ID);
            request.setEntity(new NStringEntity(retentionPolicyBody(), ContentType.APPLICATION_JSON));
            client.performRequest(request);
            log.info("Installed analytics retention policy: delete after {} days", retentionDays);
        } catch (Exception e) {
            // Without this the indices grow without bound. Loud enough to notice, not fatal —
            // OpenSearch may simply not have the ISM plugin, as in some local images.
            log.warn("Could not install the analytics retention policy — analytics indices will "
                    + "grow unbounded until one is applied: {}", e.toString());
        }
    }

    String retentionPolicyBody() {
        return """
                {
                  "policy": {
                    "description": "Deletes platform analytics indices %d days after creation. They hold one document per listing or quote view and nothing reads them back after reporting.",
                    "default_state": "hot",
                    "states": [
                      {
                        "name": "hot",
                        "actions": [],
                        "transitions": [
                          { "state_name": "delete", "conditions": { "min_index_age": "%dd" } }
                        ]
                      },
                      {
                        "name": "delete",
                        "actions": [{ "delete": {} }],
                        "transitions": []
                      }
                    ],
                    "ism_template": [
                      { "index_patterns": ["%s"], "priority": 100 }
                    ]
                  }
                }
                """.formatted(retentionDays, retentionDays, indexPattern());
    }
}
