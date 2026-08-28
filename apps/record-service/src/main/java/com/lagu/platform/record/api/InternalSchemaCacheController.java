package com.lagu.platform.record.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.record.client.MetadataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports which schema version this service is actually validating against.
 *
 * <p>Defect 6: publishing a schema is fire-and-forget. schema-registry writes a
 * {@code SchemaPublishedEvent} to its outbox, Kafka relays it, and each consumer evicts its local
 * cache — but nothing tells the person who pressed Publish whether any of that happened. When the
 * eviction is missed, validation keeps using the previous version for up to the cache TTL (ten
 * minutes), and an admin who publishes and immediately tests sees the old schema and reasonably
 * concludes the publish failed. It did not; they are looking at a stale reader.
 *
 * <p>The cached snapshot already carries its version, so the acknowledgement costs nothing to
 * produce: schema-registry asks each consumer what it currently holds and compares. That turns an
 * invisible propagation window into something the publish screen can show.
 *
 * <p>Deliberately reads the cache without going through {@link MetadataClient}. A call that
 * populated the cache on a miss would fetch the current schema as a side effect and then report
 * itself in sync — an answer that is always yes is worse than no answer at all.
 */
@Slf4j
@RestController
@RequestMapping("/internal/schema-cache")
@RequiredArgsConstructor
public class InternalSchemaCacheController {

    private final CacheManager cacheManager;

    @GetMapping("/{objectType}")
    public ResponseEntity<ApiResponse<CachedSchemaVersion>> cachedVersion(@PathVariable String objectType) {
        Cache cache = cacheManager.getCache(MetadataClient.SCHEMA_CACHE);
        MetadataClient.ObjectTypeSchemaDto cached = cache == null
                ? null
                : cache.get(objectType, MetadataClient.ObjectTypeSchemaDto.class);

        // Absent is not stale. Nothing is held, so the next validation fetches the current schema —
        // which is the same outcome a successful eviction produces.
        Integer version = cached == null ? null : cached.version();
        return ResponseEntity.ok(ApiResponse.ok(new CachedSchemaVersion(objectType, version)));
    }

    public record CachedSchemaVersion(String objectType, Integer version) {}
}
