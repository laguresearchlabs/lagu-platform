package com.lagu.platform.common.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** The configured table name is interpolated into SQL — anything but a bare identifier must be refused at startup. */
class OutboxStoreTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void acceptsBareIdentifiers() {
        assertThatCode(() -> new OutboxStore(jdbc, "record_outbox")).doesNotThrowAnyException();
        assertThatCode(() -> new OutboxStore(jdbc, "_outbox2")).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnythingElse() {
        for (String bad : new String[]{"", "records.outbox", "outbox; DROP TABLE x", "out box", "outbox--"}) {
            assertThatThrownBy(() -> new OutboxStore(jdbc, bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("platform.outbox.table");
        }
    }
}
