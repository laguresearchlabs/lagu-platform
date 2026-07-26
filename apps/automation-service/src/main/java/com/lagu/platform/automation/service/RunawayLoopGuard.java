package com.lagu.platform.automation.service;

import com.lagu.platform.automation.domain.AutomationRunRepository;
import com.lagu.platform.automation.domain.TriggerDefinition;
import com.lagu.platform.automation.model.AutomationEventContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Extracted out of PlatformEventConsumer so EscalationScheduler can share it — EscalationScheduler
 * previously called AutomationExecutor.execute() directly for every timed-out approval on every
 * scheduler tick with no guard at all, bypassing this check entirely. An approval left pending for
 * hours would generate one escalation notification per tick per replica, unbounded.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunawayLoopGuard {

    private static final int MAX_RUNS_PER_WINDOW = 5;
    private static final Duration LOOP_WINDOW = Duration.ofSeconds(60);

    private final AutomationRunRepository runRepository;

    public boolean isRunawayLoop(TriggerDefinition trigger, AutomationEventContext ctx) {
        if (ctx.getRecordId() == null) return false;
        long recentRuns = runRepository.countRecentRuns(
                trigger.getId(), ctx.getRecordId(), Instant.now().minus(LOOP_WINDOW));
        boolean runaway = recentRuns >= MAX_RUNS_PER_WINDOW;
        if (runaway) {
            log.error("Automation loop guard tripped: trigger {} fired more than {} times in {}s " +
                    "for record {} — skipping this run", trigger.getId(), MAX_RUNS_PER_WINDOW,
                    LOOP_WINDOW.toSeconds(), ctx.getRecordId());
        }
        return runaway;
    }
}
