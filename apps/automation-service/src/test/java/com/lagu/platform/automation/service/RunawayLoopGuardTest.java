package com.lagu.platform.automation.service;

import com.lagu.platform.automation.domain.AutomationRunRepository;
import com.lagu.platform.automation.domain.TriggerDefinition;
import com.lagu.platform.automation.model.AutomationEventContext;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Now shared by both PlatformEventConsumer and EscalationScheduler — the latter previously
 * called AutomationExecutor.execute() directly for every timed-out approval with no guard at
 * all, unlike the Kafka-driven path.
 */
class RunawayLoopGuardTest {

    private final AutomationRunRepository runRepository = mock(AutomationRunRepository.class);
    private final RunawayLoopGuard guard = new RunawayLoopGuard(runRepository);

    private static TriggerDefinition trigger() {
        TriggerDefinition t = new TriggerDefinition();
        t.setId(UUID.randomUUID());
        return t;
    }

    @Test
    void belowThresholdIsNotRunaway() {
        TriggerDefinition t = trigger();
        AutomationEventContext ctx = AutomationEventContext.builder().recordId(UUID.randomUUID()).build();
        when(runRepository.countRecentRuns(eq(t.getId()), any(), any())).thenReturn(4L);

        assertThat(guard.isRunawayLoop(t, ctx)).isFalse();
    }

    @Test
    void atOrAboveThresholdIsRunaway() {
        TriggerDefinition t = trigger();
        AutomationEventContext ctx = AutomationEventContext.builder().recordId(UUID.randomUUID()).build();
        when(runRepository.countRecentRuns(eq(t.getId()), any(), any())).thenReturn(5L);

        assertThat(guard.isRunawayLoop(t, ctx)).isTrue();
    }

    @Test
    void nullRecordIdIsNeverRunaway() {
        TriggerDefinition t = trigger();
        AutomationEventContext ctx = AutomationEventContext.builder().recordId(null).build();

        assertThat(guard.isRunawayLoop(t, ctx)).isFalse();
    }
}
