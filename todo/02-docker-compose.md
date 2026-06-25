# Docker Compose — Infrastructure Definition

## Strategy

Use Docker Compose profiles so developers can start only what they need.

| Profile         | What starts                                                        |
|-----------------|--------------------------------------------------------------------|
| `infra`         | PostgreSQL, Redis, Kafka, Zookeeper                                |
| `search`        | infra + OpenSearch                                                 |
| `observability` | infra + OTel Collector, Prometheus, Grafana                        |
| `apps`          | infra + all lagu-platform services                                 |
| `full`          | everything                                                         |

Common local workflow: `docker compose --profile infra up -d` then run services from IDE.

---

## Port Assignments

| Service            | Host Port | Container Port | Notes                        |
|--------------------|-----------|----------------|------------------------------|
| PostgreSQL         | 5435      | 5432           | Avoid clash with IAM (5434)  |
| Redis              | 6380      | 6379           | Avoid clash with IAM (6379)  |
| Kafka              | 9092      | 9092           |                              |
| Kafka UI (Kafdrop) | 9000      | 9000           | Dev convenience              |
| OpenSearch         | 9200      | 9200           |                              |
| OpenSearch Dashboards | 5601   | 5601           | Dev convenience              |
| Prometheus         | 9091      | 9090           | Avoid clash with vendor (9090)|
| Grafana            | 3001      | 3000           |                              |
| metadata-service   | 8100      | 8080           |                              |
| record-service     | 8101      | 8080           |                              |
| workflow-service   | 8102      | 8080           |                              |
| search-service     | 8103      | 8080           |                              |
| automation-service | 8104      | 8080           |                              |

---

## docker-compose.yml

```yaml
services:

  # ── PostgreSQL ─────────────────────────────────────────────────────────────
  postgres:
    image: postgres:16-alpine
    container_name: platform-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-platformdb}
      POSTGRES_USER: ${POSTGRES_USER:-postgres}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-postgres}
    ports:
      - "${POSTGRES_PORT:-5435}:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./infra/postgres/init.sql:/docker-entrypoint-initdb.d/init.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-postgres}"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 20s

  # ── Redis ──────────────────────────────────────────────────────────────────
  redis:
    image: redis:7-alpine
    container_name: platform-redis
    restart: unless-stopped
    command: ["redis-server", "--save", "60", "1", "--loglevel", "warning"]
    ports:
      - "${REDIS_PORT:-6380}:6379"
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  # ── Kafka + Zookeeper ──────────────────────────────────────────────────────
  zookeeper:
    image: bitnami/zookeeper:3.9
    container_name: platform-zookeeper
    restart: unless-stopped
    environment:
      ALLOW_ANONYMOUS_LOGIN: "yes"
    volumes:
      - zookeeper-data:/bitnami/zookeeper

  kafka:
    image: bitnami/kafka:3.7
    container_name: platform-kafka
    restart: unless-stopped
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_CFG_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CFG_AUTO_CREATE_TOPICS_ENABLE: "true"
      ALLOW_PLAINTEXT_LISTENER: "yes"
    volumes:
      - kafka-data:/bitnami/kafka
    healthcheck:
      test: ["CMD-SHELL", "kafka-topics.sh --bootstrap-server localhost:9092 --list"]
      interval: 15s
      timeout: 10s
      retries: 5
      start_period: 30s

  kafdrop:
    image: obsidiandynamics/kafdrop:4.0.2
    container_name: platform-kafdrop
    restart: unless-stopped
    profiles: [infra, apps, full]
    ports:
      - "9000:9000"
    environment:
      KAFKA_BROKERCONNECT: kafka:9092
    depends_on:
      kafka:
        condition: service_healthy

  # ── OpenSearch ─────────────────────────────────────────────────────────────
  opensearch:
    image: opensearchproject/opensearch:2.19.0
    container_name: platform-opensearch
    profiles: [search, full]
    restart: unless-stopped
    environment:
      discovery.type: single-node
      DISABLE_SECURITY_PLUGIN: "true"
      OPENSEARCH_JAVA_OPTS: "-Xms512m -Xmx512m"
    ports:
      - "9200:9200"
    volumes:
      - opensearch-data:/usr/share/opensearch/data
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:9200/_cluster/health | grep -q '\"status\":\"green\"\\|\"status\":\"yellow\"'"]
      interval: 15s
      timeout: 10s
      retries: 10

  opensearch-dashboards:
    image: opensearchproject/opensearch-dashboards:2.19.0
    container_name: platform-opensearch-dashboards
    profiles: [search, full]
    restart: unless-stopped
    ports:
      - "5601:5601"
    environment:
      OPENSEARCH_HOSTS: '["http://opensearch:9200"]'
      DISABLE_SECURITY_DASHBOARDS_PLUGIN: "true"
    depends_on:
      opensearch:
        condition: service_healthy

  # ── Observability ──────────────────────────────────────────────────────────
  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.127.0
    container_name: platform-otel-collector
    profiles: [observability, full]
    command: ["--config=/etc/otelcol/config.yaml"]
    volumes:
      - ./infra/otel/config.yaml:/etc/otelcol/config.yaml:ro
    ports:
      - "4317:4317"
      - "4318:4318"
    depends_on:
      - prometheus

  prometheus:
    image: prom/prometheus:v3.4.1
    container_name: platform-prometheus
    profiles: [observability, full]
    ports:
      - "9091:9090"
    volumes:
      - ./infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus-data:/prometheus

  grafana:
    image: grafana/grafana:11.5.0
    container_name: platform-grafana
    profiles: [observability, full]
    ports:
      - "3001:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
    volumes:
      - grafana-data:/var/lib/grafana
      - ./infra/grafana/dashboards:/var/lib/grafana/dashboards:ro
      - ./infra/grafana/provisioning:/etc/grafana/provisioning:ro

  # ── Applications ───────────────────────────────────────────────────────────
  metadata-service:
    build:
      context: .
      dockerfile: apps/metadata-service/Dockerfile
    container_name: platform-metadata
    profiles: [apps, full]
    ports:
      - "8100:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/platformdb
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER:-postgres}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:-postgres}
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: ${EUREKA_URL:-http://host.docker.internal:8761/eureka}
      OTEL_SERVICE_NAME: metadata-service
      OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
    depends_on:
      postgres:
        condition: service_healthy
      kafka:
        condition: service_healthy

  record-service:
    build:
      context: .
      dockerfile: apps/record-service/Dockerfile
    container_name: platform-record
    profiles: [apps, full]
    ports:
      - "8101:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/platformdb
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER:-postgres}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:-postgres}
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: ${EUREKA_URL:-http://host.docker.internal:8761/eureka}
      METADATA_SERVICE_URL: http://metadata-service:8080
      OTEL_SERVICE_NAME: record-service
      OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
    depends_on:
      postgres:
        condition: service_healthy
      kafka:
        condition: service_healthy
      metadata-service:
        condition: service_started

  workflow-service:
    build:
      context: .
      dockerfile: apps/workflow-service/Dockerfile
    container_name: platform-workflow
    profiles: [apps, full]
    ports:
      - "8102:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/platformdb
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER:-postgres}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:-postgres}
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: ${EUREKA_URL:-http://host.docker.internal:8761/eureka}
      OTEL_SERVICE_NAME: workflow-service
      OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
    depends_on:
      postgres:
        condition: service_healthy
      kafka:
        condition: service_healthy

volumes:
  postgres-data:
  redis-data:
  zookeeper-data:
  kafka-data:
  opensearch-data:
  prometheus-data:
  grafana-data:
```

---

## infra/postgres/init.sql

Create separate schemas per service so each service's Flyway migrations are isolated:

```sql
CREATE SCHEMA IF NOT EXISTS metadata;
CREATE SCHEMA IF NOT EXISTS records;
CREATE SCHEMA IF NOT EXISTS workflow;
CREATE SCHEMA IF NOT EXISTS search_idx;
CREATE SCHEMA IF NOT EXISTS automation;
```

Each service sets `spring.flyway.schemas=<schema>` and `spring.jpa.properties.hibernate.default_schema=<schema>`.

---

## .env.example

```
POSTGRES_DB=platformdb
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
POSTGRES_PORT=5435
REDIS_PORT=6380
EUREKA_URL=http://host.docker.internal:8761/eureka
```

---

## Common Usage Commands

```bash
# Start only infrastructure (dev mode — run services from IDE)
docker compose --profile infra up -d

# Start infra + OpenSearch (for Phase 4 search dev)
docker compose --profile infra --profile search up -d

# Start everything including apps
docker compose --profile full up -d

# Build and restart a single service
docker compose --profile apps up -d --build metadata-service

# Tail logs
docker compose logs -f metadata-service record-service

# Full teardown including volumes
docker compose --profile full down -v
```
