package com.lagu.platform.listing.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Guards the ApiResponse unwrap.
 *
 * Returning the raw envelope here produced a bug that no unit test could have caught downstream:
 * {@code envelope.get("data")} is the *record*, and the record has its own {@code data}, so every
 * published snapshot was stored double-nested. Consumers read {@code data.name} and found nothing
 * — every listing in events-ui rendered as "Unnamed Service" with no image — while
 * {@code verificationTier}, a record field absent from the envelope, silently defaulted to NONE
 * so search boost never applied.
 */
class RecordServiceClientTest {

    private static final UUID RECORD_ID = UUID.fromString("63a45207-b568-4319-b818-caf03d74b83c");
    private static final UUID TENANT_ID = UUID.fromString("74055dfe-c574-48a3-b617-36622661a092");

    private record Fixture(RecordServiceClient client, MockRestServiceServer server) {}

    private Fixture fixtureReturning(String body) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://record-service/api/v1/records/" + RECORD_ID))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        return new Fixture(new RecordServiceClient(builder, "test-secret"), server);
    }

    @Test
    void unwrapsTheEnvelopeSoDataIsTheRecordsFieldMap() {
        Fixture f = fixtureReturning("""
                {"success":true,"message":"ok","data":{
                   "id":"63a45207-b568-4319-b818-caf03d74b83c",
                   "objectType":"VENUE",
                   "status":"PUBLISHED",
                   "verificationTier":"ENHANCED",
                   "data":{"name":"Browser Pass Banquet Hall","capacity":275}
                }}""");

        Map<String, Object> record = f.client().getRecord(RECORD_ID, TENANT_ID);

        // The record itself, not the envelope.
        assertThat(record).containsEntry("objectType", "VENUE");

        // verificationTier lives on the record — off the envelope it would vanish and default.
        assertThat(record).containsEntry("verificationTier", "ENHANCED");

        // One level of `data`, holding the fields a consumer actually renders.
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) record.get("data");
        assertThat(fields)
                .containsEntry("name", "Browser Pass Banquet Hall")
                .containsEntry("capacity", 275);
        assertThat(fields).doesNotContainKey("data");

        f.server().verify();
    }

    @Test
    void failsLoudlyWhenTheEnvelopeShapeDrifts() {
        Fixture f = fixtureReturning("""
                {"success":true,"message":"ok","data":"not-an-object"}""");

        // Degrading to a half-populated snapshot is what made the original bug invisible; shape
        // drift has to surface so the consumer's retry/DLT path handles it.
        assertThatThrownBy(() -> f.client().getRecord(RECORD_ID, TENANT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected response shape");
    }

    @Test
    void failsLoudlyWhenTheEnvelopeHasNoData() {
        Fixture f = fixtureReturning("""
                {"success":false,"message":"nope"}""");

        assertThatThrownBy(() -> f.client().getRecord(RECORD_ID, TENANT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected response shape");
    }
}
