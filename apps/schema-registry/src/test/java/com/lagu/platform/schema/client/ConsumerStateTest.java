package com.lagu.platform.schema.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defect 6: publishing a schema is fire-and-forget, so "the publish failed" and "the publish worked
 * and a reader is still on the old version for a few more minutes" looked identical from the admin
 * screen. This is the classification that tells them apart, and the interesting cases are the two
 * that are easy to get backwards.
 */
class ConsumerStateTest {

    @Test
    void aConsumerHoldingThePublishedVersionIsInSync() {
        SchemaConsumerClient.ConsumerState state =
                SchemaConsumerClient.ConsumerState.of("record-service", 3, 3);

        assertThat(state.inSync()).isTrue();
        assertThat(state.cachedVersion()).isEqualTo(3);
    }

    @Test
    void aConsumerHoldingAnOlderVersionIsNot() {
        // The case the whole endpoint exists to make visible.
        SchemaConsumerClient.ConsumerState state =
                SchemaConsumerClient.ConsumerState.of("record-service", 2, 3);

        assertThat(state.inSync()).isFalse();
        assertThat(state.note()).contains("still using v2");
    }

    @Test
    void anEmptyCacheCountsAsInSync() {
        // Absence is not staleness. Nothing is held, so the next read fetches the current schema -
        // exactly the outcome a successful eviction produces. Reporting this as out of sync would
        // put a warning on the screen for the case that is working perfectly.
        SchemaConsumerClient.ConsumerState state =
                SchemaConsumerClient.ConsumerState.of("record-service", null, 3);

        assertThat(state.inSync()).isTrue();
        assertThat(state.note()).contains("nothing cached");
    }

    @Test
    void anUnreachableConsumerIsNotReportedAsHealthy() {
        // "I could not reach it" is not "it is fine". A green tick next to a service nobody has
        // heard from is the one answer worse than no answer.
        SchemaConsumerClient.ConsumerState state =
                SchemaConsumerClient.ConsumerState.unreachable("record-service", "ConnectException");

        assertThat(state.inSync()).isFalse();
        assertThat(state.cachedVersion()).isNull();
        assertThat(state.note()).contains("could not be reached");
    }

    @Test
    void aConsumerAheadOfUsIsNotFlagged() {
        // Possible during a rolling deploy, when one instance has published and another has not yet
        // seen its own write. Flagging it would send an admin chasing a problem that does not exist.
        SchemaConsumerClient.ConsumerState state =
                SchemaConsumerClient.ConsumerState.of("record-service", 4, 3);

        assertThat(state.inSync()).isTrue();
    }
}
