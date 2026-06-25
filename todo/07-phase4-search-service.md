# Phase 4 — Search Service

## Responsibility

Replace the Phase 1 PostgreSQL JSONB query approach with OpenSearch for:
- Full-text search across record fields
- Faceted filtering (city, category, price range)
- Sorted, paginated result sets
- Auto-suggest / typeahead

The search-service does not own records — it maintains a derived index. PostgreSQL remains
the system of truth. Search-service builds and queries the OpenSearch index.

---

## Architecture

```
record-service  →  Kafka (platform.record.events)  →  search-service  →  OpenSearch
metadata-service →  Kafka (platform.metadata.changed) → search-service (reindex mappings)

Client query → gateway → search-service → OpenSearch → response
```

---

## OpenSearch Index Design

One index per object type per org:

```
platform-{orgId}-{objectType}
e.g.
  platform-abc123-venue
  platform-abc123-photographer
  platform-abc123-event
```

This allows per-object-type field mappings and avoids mapping conflicts.

Index template applied on first record of a new object type (created dynamically).

### Index Mapping Template

When search-service processes the first `CREATED` event for `objectType=VENUE`:

1. Call metadata-service: `GET /object-types/VENUE/schema`
2. Generate OpenSearch mapping from schema:

```json
{
  "mappings": {
    "properties": {
      "recordId":    { "type": "keyword" },
      "orgId":       { "type": "keyword" },
      "objectType":  { "type": "keyword" },
      "status":      { "type": "keyword" },
      "createdAt":   { "type": "date" },
      "updatedAt":   { "type": "date" },
      "data": {
        "properties": {
          "name":     { "type": "text", "fields": { "keyword": { "type": "keyword" } } },
          "capacity": { "type": "integer" },
          "price":    { "type": "double" },
          "city":     { "type": "keyword" },
          "status":   { "type": "keyword" }
        }
      }
    }
  }
}
```

### Mapping Rules (metadata → OpenSearch type)

| AttributeType   | OpenSearch mapping                              |
|-----------------|-------------------------------------------------|
| TEXT            | `text` with `.keyword` sub-field                |
| LONG_TEXT       | `text` only (no keyword — too long to sort)     |
| NUMBER          | `integer`                                       |
| DECIMAL         | `double`                                        |
| BOOLEAN         | `boolean`                                       |
| DATE            | `date` format: `yyyy-MM-dd`                     |
| DATETIME        | `date` format: `strict_date_time`               |
| EMAIL, PHONE, URL | `keyword`                                     |
| ENUM            | `keyword`                                       |
| MULTI_SELECT    | `keyword` (array)                               |
| CURRENCY        | `double`                                        |
| GEOLOCATION     | `geo_point`                                     |

---

## Kafka Consumer

Consumes `platform.record.events` and `platform.metadata.changed`.

```java
@KafkaListener(topics = PlatformTopics.RECORD_EVENTS, groupId = "search-service")
public void handleRecordEvent(RecordEvent event) {
    switch (event.getEventType()) {
        case "CREATED", "UPDATED" -> indexRecord(event);
        case "STATUS_CHANGED"     -> updateStatus(event);
        case "DELETED"            -> deleteFromIndex(event.getRecordId());
    }
}

@KafkaListener(topics = PlatformTopics.METADATA_CHANGED, groupId = "search-service")
public void handleMetadataChange(MetadataChangedEvent event) {
    if ("OBJECT_TYPE_UPDATED".equals(event.getEventType())) {
        reindexObjectType(event.getObjectType(), event.getOrgId());
    }
}
```

---

## OpenSearch Document Structure

```json
{
  "recordId":   "uuid",
  "orgId":      "uuid",
  "objectType": "VENUE",
  "status":     "ACTIVE",
  "createdAt":  "2026-01-15T10:30:00Z",
  "updatedAt":  "2026-03-01T08:00:00Z",
  "data": {
    "name":        "Grand Palace Hall",
    "capacity":    500,
    "price":       75000,
    "currency":    "INR",
    "city":        "Mumbai",
    "parking":     true,
    "phone":       "+91-9876543210"
  }
}
```

---

## REST API (search-service)

Base path: `/api/v1/search` — routed via gateway at `/platform/search/**`

### Universal Search

```
POST /search
{
  "objectType": "VENUE",
  "query": "grand hall mumbai",
  "filters": {
    "status": "ACTIVE",
    "data.capacity": { "gte": 200 },
    "data.city": "Mumbai",
    "data.price": { "gte": 10000, "lte": 100000 }
  },
  "sort": [
    { "field": "data.capacity", "order": "desc" }
  ],
  "facets": ["data.city", "data.status"],
  "page": 0,
  "size": 20
}
```

Response:

```json
{
  "total": 47,
  "page": 0,
  "size": 20,
  "results": [
    {
      "recordId": "uuid",
      "objectType": "VENUE",
      "status": "ACTIVE",
      "data": { "name": "Grand Palace", "capacity": 500, "city": "Mumbai" },
      "score": 1.85
    }
  ],
  "facets": {
    "data.city": [
      { "value": "Mumbai", "count": 23 },
      { "value": "Pune",   "count": 12 }
    ],
    "data.status": [
      { "value": "ACTIVE", "count": 40 }
    ]
  }
}
```

### Typeahead / Suggest

```
GET /search/suggest?objectType=VENUE&field=data.name&prefix=gran
```

Returns:
```json
["Grand Palace Hall", "Grandiose Banquet", "Grand Crown Convention"]
```

### Reindex (admin)

```
POST /admin/reindex/{objectType}        Full reindex from PostgreSQL
POST /admin/reindex/{objectType}/{id}   Reindex single record
GET  /admin/index-status/{objectType}   Index doc count, last updated
```

---

## Reindex from PostgreSQL

When admin triggers reindex or when mapping changes:

```java
@Service
public class ReindexService {

    public void reindex(String objectType, UUID orgId) {
        // Paginate through records in PostgreSQL
        int page = 0, size = 500;
        Page<Record> records;
        do {
            records = recordRepository.findByOrgIdAndObjectType(
                orgId, objectType, PageRequest.of(page++, size));
            List<SearchDocument> docs = records.map(this::toDocument).toList();
            openSearchClient.bulkIndex(indexName(orgId, objectType), docs);
        } while (records.hasNext());
    }
}
```

---

## OpenSearch Client Config

Use the official OpenSearch Java client:

```kotlin
// build.gradle.kts additions for search-service
implementation("org.opensearch.client:opensearch-java:2.19.0")
```

```java
@Configuration
public class OpenSearchConfig {
    @Value("${opensearch.host:localhost}")
    private String host;

    @Value("${opensearch.port:9200}")
    private int port;

    @Bean
    public OpenSearchClient openSearchClient() {
        RestClient restClient = RestClient.builder(
            new HttpHost(host, port, "http")).build();
        OpenSearchTransport transport = new RestClientTransport(
            restClient, new JacksonJsonpMapper());
        return new OpenSearchClient(transport);
    }
}
```

---

## Phase 4 Migration: Switching record-service Queries

After search-service is stable:

1. Add `X-Use-Search: true` header option to record-service `GET /records`
2. When header present, delegate to search-service instead of JSONB query
3. After validation period, make search-service the default
4. Keep JSONB query as fallback for complex queries that OpenSearch doesn't serve

---

## Application Config

```yaml
spring:
  application:
    name: search-service
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: search-service
      auto-offset-reset: earliest

opensearch:
  host: localhost
  port: 9200
  index-prefix: platform

platform:
  metadata-service:
    url: http://metadata-service
  record-service:
    url: http://record-service
```

---

## Implementation Checklist

- [ ] Create `apps/search-service` module
- [ ] Add OpenSearch Java client dependency
- [ ] Implement `OpenSearchConfig`
- [ ] Implement `IndexMappingBuilder` (metadata schema → OS mapping)
- [ ] Implement `SearchDocumentIndexer` (Kafka consumer → index)
- [ ] Implement `SearchQueryBuilder` (request DTO → OS query DSL)
- [ ] Implement `SearchService` (execute query, map results, facets)
- [ ] Implement `SuggestService` (prefix search)
- [ ] Implement `ReindexService` (admin-triggered full reindex)
- [ ] Implement REST API controllers
- [ ] Handle index-not-found gracefully (return empty, trigger create)
- [ ] Write integration tests with Testcontainers (OpenSearch)
- [ ] Update docker-compose with OpenSearch + Dashboards (search profile)
