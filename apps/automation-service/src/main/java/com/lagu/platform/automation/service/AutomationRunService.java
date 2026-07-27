package com.lagu.platform.automation.service;

import com.lagu.platform.automation.domain.AutomationRun;
import com.lagu.platform.automation.domain.AutomationRunRepository;
import com.lagu.platform.automation.domain.TriggerDefinition;
import com.lagu.platform.automation.model.AutomationEventContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Split out of AutomationExecutor because {@code createRun}/{@code complete} were previously
 * {@code @Transactional protected} methods called via {@code this.} from within the same class —
 * self-invocation bypasses Spring's proxy-based AOP entirely, so neither method's
 * {@code @Transactional} ever actually took effect. Calling through a real, separately-injected
 * bean (this one) is what makes the annotation do anything.
 */
@Service
@RequiredArgsConstructor
public class AutomationRunService {

    private final AutomationRunRepository runRepository;

    @Transactional
    public AutomationRun createRun(TriggerDefinition trigger, AutomationEventContext ctx) {
        AutomationRun run = new AutomationRun();
        run.setTrigger(trigger);
        run.setTenantId(ctx.getTenantId());
        run.setRecordId(ctx.getRecordId());
        run.setEventType(ctx.getEventType());
        run.setStatus("RUNNING");
        return runRepository.save(run);
    }

    @Transactional
    public void complete(AutomationRun run, String status) {
        run.setStatus(status);
        run.setCompletedAt(Instant.now());
        runRepository.save(run);
    }

    @Transactional
    public void completeWithError(AutomationRun run, String errorMessage) {
        run.setErrorMessage(errorMessage);
        complete(run, "FAILED");
    }
}
