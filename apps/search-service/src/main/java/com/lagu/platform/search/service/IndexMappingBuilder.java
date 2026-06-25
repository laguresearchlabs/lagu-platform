package com.lagu.platform.search.service;

import com.lagu.platform.search.client.MetadataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch._types.mapping.*;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexMappingBuilder {

    private final MetadataClient    metadataClient;
    private final OpenSearchClient  osClient;

    @Value("${opensearch.index-prefix:platform}")
    private String indexPrefix;

    public String indexName(String orgId, String objectType) {
        return indexPrefix + "-" + orgId.toLowerCase() + "-" + objectType.toLowerCase();
    }

    /**
     * Creates the OpenSearch index for the given org/objectType if it does not already exist.
     * Mapping is derived from the metadata-service schema.
     */
    public void ensureIndex(String orgId, String objectType) {
        String name = indexName(orgId, objectType);
        try {
            boolean exists = osClient.indices().exists(r -> r.index(name)).value();
            if (exists) return;

            List<Map<String, Object>> fields = metadataClient.getSchema(objectType);
            Map<String, Property> dataProperties = buildDataProperties(fields);

            TypeMapping mapping = TypeMapping.of(m -> m.properties(allProperties(dataProperties)));

            osClient.indices().create(CreateIndexRequest.of(r -> r
                    .index(name)
                    .settings(IndexSettings.of(s -> s.numberOfShards("1").numberOfReplicas("0")))
                    .mappings(mapping)
            ));
            log.info("Created OpenSearch index: {}", name);
        } catch (IOException e) {
            log.error("Failed to ensure index {}: {}", name, e.getMessage(), e);
        }
    }

    // ── mapping rules (AttributeType → OpenSearch property) ──────────────────

    private Map<String, Property> allProperties(Map<String, Property> dataProps) {
        Map<String, Property> props = new HashMap<>();
        props.put("recordId",   Property.of(p -> p.keyword(k -> k)));
        props.put("orgId",      Property.of(p -> p.keyword(k -> k)));
        props.put("objectType", Property.of(p -> p.keyword(k -> k)));
        props.put("status",     Property.of(p -> p.keyword(k -> k)));
        props.put("createdAt",  Property.of(p -> p.date(d -> d.format("strict_date_time||epoch_millis"))));
        props.put("updatedAt",  Property.of(p -> p.date(d -> d.format("strict_date_time||epoch_millis"))));
        // Nest data fields as an object
        props.put("data", Property.of(p -> p.object(o -> o.properties(dataProps))));
        return props;
    }

    private Map<String, Property> buildDataProperties(List<Map<String, Object>> fields) {
        Map<String, Property> props = new HashMap<>();
        for (Map<String, Object> field : fields) {
            String name = (String) field.get("name");
            String type = (String) field.get("type");
            if (name == null || type == null) continue;
            props.put(name, toProperty(type));
        }
        return props;
    }

    private Property toProperty(String attributeType) {
        return switch (attributeType) {
            case "TEXT" -> Property.of(p -> p.text(t -> t
                    .fields("keyword", kf -> kf.keyword(k -> k.ignoreAbove(256)))));
            case "LONG_TEXT" -> Property.of(p -> p.text(t -> t));
            case "NUMBER"    -> Property.of(p -> p.integer(i -> i));
            case "DECIMAL", "CURRENCY" -> Property.of(p -> p.double_(d -> d));
            case "BOOLEAN"   -> Property.of(p -> p.boolean_(b -> b));
            case "DATE"      -> Property.of(p -> p.date(d -> d.format("yyyy-MM-dd")));
            case "DATETIME"  -> Property.of(p -> p.date(d -> d.format("strict_date_time||epoch_millis")));
            case "GEOLOCATION" -> Property.of(p -> p.geoPoint(g -> g));
            // Everything else is a keyword (enum, phone, email, url, etc.)
            default -> Property.of(p -> p.keyword(k -> k.ignoreAbove(512)));
        };
    }
}
