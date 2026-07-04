package com.lagu.platform.common.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Plain-JDBC access to a service's outbox table. JDBC rather than a JPA entity so that
 * services adopt the outbox with two properties (platform.outbox.enabled + .table) instead
 * of widening their @EntityScan/@EnableJpaRepositories to a second package — and so the
 * table name can stay service-specific (record_outbox, workflow_outbox, …). Statements run
 * on the transaction-bound connection, so inserts commit atomically with the caller's JPA
 * changes and the relay's SKIP LOCKED claims hold until its transaction ends.
 */
@Repository
@ConditionalOnProperty(name = "platform.outbox.enabled", havingValue = "true")
public class OutboxStore {

    /** Table name is interpolated into SQL — restrict to a bare identifier (schema comes
     *  from the connection's search_path, like every other table in the service). */
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final JdbcTemplate jdbc;
    private final String table;

    public OutboxStore(JdbcTemplate jdbc, @Value("${platform.outbox.table}") String table) {
        if (!SQL_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalStateException("platform.outbox.table is not a valid SQL identifier: " + table);
        }
        this.jdbc = jdbc;
        this.table = table;
    }

    public void insert(String topic, String eventKey, String payloadType, String payload) {
        jdbc.update("INSERT INTO " + table + " (topic, event_key, payload_type, payload) VALUES (?, ?, ?, ?)",
                topic, eventKey, payloadType, payload);
    }

    /**
     * Claims the oldest unpublished events for relaying. FOR UPDATE SKIP LOCKED lets
     * multiple service replicas relay concurrently without double-sending the same row
     * (each replica skips rows another has claimed in-flight).
     */
    public List<OutboxRow> claimUnpublishedBatch(int batchSize) {
        return jdbc.query("SELECT id, topic, event_key, payload_type, payload, attempts FROM " + table
                        + " WHERE published_at IS NULL ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, rowNum) -> new OutboxRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("event_key"),
                        rs.getString("payload_type"),
                        rs.getString("payload"),
                        rs.getInt("attempts")),
                batchSize);
    }

    public void markPublished(UUID id) {
        jdbc.update("UPDATE " + table + " SET published_at = now() WHERE id = ?", id);
    }

    /** Parks a row that can never succeed: counted and marked published so it stops
     *  blocking the stream, but kept until cleanup for forensics. */
    public void park(UUID id) {
        jdbc.update("UPDATE " + table + " SET attempts = attempts + 1, published_at = now() WHERE id = ?", id);
    }

    public void recordFailure(UUID id) {
        jdbc.update("UPDATE " + table + " SET attempts = attempts + 1 WHERE id = ?", id);
    }

    public int deletePublishedBefore(OffsetDateTime cutoff) {
        return jdbc.update("DELETE FROM " + table + " WHERE published_at IS NOT NULL AND published_at < ?", cutoff);
    }
}
