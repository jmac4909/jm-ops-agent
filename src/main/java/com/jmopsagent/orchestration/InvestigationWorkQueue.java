package com.jmopsagent.orchestration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InvestigationWorkQueue {
    private final TaskExecutor executor;
    private final InvestigationOrchestrator orchestrator;
    private final InvestigationStateService state;

    public InvestigationWorkQueue(@Qualifier("investigationTaskExecutor") TaskExecutor executor,
                                  InvestigationOrchestrator orchestrator,
                                  InvestigationStateService state) {
        this.executor = executor;
        this.orchestrator = orchestrator;
        this.state = state;
    }

    public void submit(UUID investigationId) {
        try {
            executor.execute(() -> orchestrator.investigate(investigationId));
        } catch (TaskRejectedException ex) {
            state.fail(investigationId, "Investigation could not be queued because the local worker queue is full");
        }
    }

    public void submitCodeInvestigation(UUID investigationId) {
        state.beginCodeInvestigation(investigationId);
        try {
            executor.execute(() -> orchestrator.investigateCode(investigationId));
        } catch (TaskRejectedException ex) {
            state.completeCodeInvestigationWithLimitation(investigationId,
                    "The optional code investigation could not be queued because the local worker queue is full");
        }
    }
}
