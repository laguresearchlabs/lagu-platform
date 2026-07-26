package com.lagu.platform.automation.service;

import com.lagu.platform.automation.domain.ActionDefinition;
import com.lagu.platform.automation.domain.AutomationRun;
import com.lagu.platform.automation.domain.TriggerDefinition;
import com.lagu.platform.automation.model.AutomationEventContext;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * execute() used to be {@code @Async}, which let PlatformEventConsumer ack the Kafka offset
 * before the automation had even started running — a failure was never retried and never
 * reached the DLT. These tests pin the fix: execute() runs synchronously and rethrows on
 * failure, so the caller (the Kafka listener) sees the exception before acking.
 */
class AutomationExecutorTest {

    private final ActionExecutor       actionExecutor = mock(ActionExecutor.class);
    private final AutomationRunService runService     = mock(AutomationRunService.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

    private final AutomationExecutor executor =
            new AutomationExecutor(actionExecutor, runService, kafkaTemplate);

    private static TriggerDefinition trigger(List<ActionDefinition> actions) {
        TriggerDefinition t = new TriggerDefinition();
        t.setId(UUID.randomUUID());
        t.setName("test-trigger");
        t.setActions(actions);
        return t;
    }

    private static ActionDefinition action(boolean active, boolean continueOnFailure) {
        ActionDefinition a = new ActionDefinition();
        a.setId(UUID.randomUUID());
        a.setActive(active);
        a.setContinueOnFailure(continueOnFailure);
        a.setActionType("SEND_NOTIFICATION");
        return a;
    }

    private static AutomationEventContext ctx() {
        return AutomationEventContext.builder()
                .eventType("RECORD_CREATED").orgId(UUID.randomUUID()).recordId(UUID.randomUUID())
                .build();
    }

    @Test
    void noActionsCompletesSuccessfullyWithoutRunningAnyAction() {
        AutomationRun run = new AutomationRun();
        when(runService.createRun(any(), any())).thenReturn(run);

        executor.execute(trigger(List.of()), ctx());

        verify(runService).complete(run, "SUCCESS");
        verifyNoInteractions(actionExecutor);
    }

    @Test
    void allActionsSucceedCompletesSuccessfully() {
        AutomationRun run = new AutomationRun();
        when(runService.createRun(any(), any())).thenReturn(run);
        ActionDefinition a1 = action(true, false);
        when(actionExecutor.execute(eq(a1), any())).thenReturn(true);

        executor.execute(trigger(List.of(a1)), ctx());

        verify(runService).complete(run, "SUCCESS");
    }

    @Test
    void failingActionWithoutContinueOnFailureStopsEarlyAndMarksFailed() {
        AutomationRun run = new AutomationRun();
        when(runService.createRun(any(), any())).thenReturn(run);
        ActionDefinition failing = action(true, false);
        ActionDefinition never   = action(true, false);
        when(actionExecutor.execute(eq(failing), any())).thenReturn(false);

        executor.execute(trigger(List.of(failing, never)), ctx());

        verify(runService).complete(run, "FAILED");
        verify(actionExecutor, never()).execute(eq(never), any());
    }

    @Test
    void failingActionWithContinueOnFailureRunsRemainingActions() {
        AutomationRun run = new AutomationRun();
        when(runService.createRun(any(), any())).thenReturn(run);
        ActionDefinition failing = action(true, true);
        ActionDefinition next    = action(true, false);
        when(actionExecutor.execute(eq(failing), any())).thenReturn(false);
        when(actionExecutor.execute(eq(next), any())).thenReturn(true);

        executor.execute(trigger(List.of(failing, next)), ctx());

        verify(actionExecutor).execute(eq(next), any());
        verify(runService).complete(run, "FAILED"); // overall still failed
    }

    @Test
    void inactiveActionsAreSkipped() {
        AutomationRun run = new AutomationRun();
        when(runService.createRun(any(), any())).thenReturn(run);
        ActionDefinition inactive = action(false, false);

        executor.execute(trigger(List.of(inactive)), ctx());

        verify(actionExecutor, never()).execute(eq(inactive), any());
        verify(runService).complete(run, "SUCCESS");
    }

    @Test
    void exceptionDuringActionExecutionMarksFailedAndRethrows() {
        // The critical regression: this must propagate to the caller (the Kafka listener) so
        // its error handler can retry/DLT the message, rather than being silently swallowed the
        // way an @Async method's uncaught exception would be.
        AutomationRun run = new AutomationRun();
        when(runService.createRun(any(), any())).thenReturn(run);
        ActionDefinition a = action(true, false);
        RuntimeException boom = new RuntimeException("downstream service unavailable");
        when(actionExecutor.execute(eq(a), any())).thenThrow(boom);

        assertThatThrownBy(() -> executor.execute(trigger(List.of(a)), ctx()))
                .isSameAs(boom);

        verify(runService).completeWithError(run, "downstream service unavailable");
    }
}
