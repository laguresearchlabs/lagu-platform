package com.lagu.platform.listing.client;

import com.lagu.platform.listing.client.SchemaRegistryClient.ListingTypeFlags;
import com.lagu.platform.listing.client.SchemaRegistryClient.ListingTypeLookupException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Guards the distinction between "not publishable" and "could not find out".
 *
 * <p>Both fail closed — nothing publishes without an affirmative yes, because this gates whether
 * a record's data becomes a public, cross-org snapshot. What differs is what happens next. Every
 * failure used to collapse into {@code (false, false)}, so a transient schema-registry outage
 * made an approved listing silently never reach consumers: one WARN, no retry, and no way for
 * any caller to tell it apart from a listing type that was deliberately not publishable. It cost
 * a long debugging session to trace a bare "0 results" in consumer search back to this.
 */
class SchemaRegistryClientTest {

    private static final String URL = "http://schema-registry/api/v1/listing-types/VENUE";

    private record Fixture(SchemaRegistryClient client, MockRestServiceServer server) {}

    private Fixture fixture(java.util.function.Consumer<MockRestServiceServer> stub) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        stub.accept(server);
        return new Fixture(new SchemaRegistryClient(builder, "test-secret"), server);
    }

    private static String envelope(boolean publishable, boolean consumerSearchable) {
        return """
               {"success":true,"data":{"name":"VENUE","publishable":%s,"consumerSearchable":%s}}
               """.formatted(publishable, consumerSearchable);
    }

    @Test
    void returnsTheFlagsSchemaRegistryReports() {
        var f = fixture(s -> s.expect(requestTo(URL))
                .andRespond(withSuccess(envelope(true, true), MediaType.APPLICATION_JSON)));

        assertThat(f.client().getFlags("VENUE")).isEqualTo(new ListingTypeFlags(true, true));
        f.server().verify();
    }

    /** A definitive no. The caller should skip, permanently — this is not an error. */
    @Test
    void aTypeMarkedNotPublishableIsReportedAsSuch() {
        var f = fixture(s -> s.expect(requestTo(URL))
                .andRespond(withSuccess(envelope(false, true), MediaType.APPLICATION_JSON)));

        assertThat(f.client().getFlags("VENUE")).isEqualTo(new ListingTypeFlags(false, true));
    }

    /** Also a definitive no: schema-registry answered, and there is no such listing type. */
    @Test
    void anUnknownListingTypeIsNotPublishableRatherThanAnError() {
        var f = fixture(s -> s.expect(requestTo(URL))
                .andRespond(MockRestResponseCreators.withResourceNotFound()));

        assertThat(f.client().getFlags("VENUE")).isEqualTo(new ListingTypeFlags(false, false));
    }

    /**
     * The case that mattered. An unreachable registry is not an answer, so it must not be
     * reported as one — the Kafka consumers retry and dead-letter on the exception, and a manual
     * publish surfaces an error to the admin instead of quietly doing nothing.
     */
    @Test
    void anUnreachableRegistryThrowsRatherThanReportingNotPublishable() {
        var f = fixture(s -> s.expect(requestTo(URL))
                .andRespond(MockRestResponseCreators.withServerError()));

        assertThatThrownBy(() -> f.client().getFlags("VENUE"))
                .isInstanceOf(ListingTypeLookupException.class)
                .hasMessageContaining("Could not determine listing type flags for VENUE");
    }

    @Test
    void aServiceUnavailableRegistryAlsoThrows() {
        var f = fixture(s -> s.expect(requestTo(URL))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.SERVICE_UNAVAILABLE)));

        assertThatThrownBy(() -> f.client().getFlags("VENUE"))
                .isInstanceOf(ListingTypeLookupException.class);
    }

    /**
     * A 2xx whose body is not the expected envelope means the contract moved. Reading that as
     * "not publishable" would silently stop every listing on the platform from publishing, so it
     * is loud instead.
     */
    @Test
    void anUnrecognisedResponseShapeThrows() {
        var f = fixture(s -> s.expect(requestTo(URL))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON)));

        assertThatThrownBy(() -> f.client().getFlags("VENUE"))
                .isInstanceOf(ListingTypeLookupException.class);
    }
}
