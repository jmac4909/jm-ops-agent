package com.jmopsagent.claude;

import com.jmopsagent.domain.RootCauseCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicClaudeCodeClientTest {
    private final DeterministicClaudeCodeClient client = new DeterministicClaudeCodeClient();

    @Test
    void positiveReadinessWordingDoesNotBecomeRuntimeFailure() {
        ClaudeInvocationResult result = client.analyze(request(List.of(evidence("live-health", "KUBERNETES",
                "WORKLOAD_HEALTH", "Readiness checks passing", "ready=true; all replicas available"))));

        assertThat(result.decision().status()).isEqualTo(ReasoningStatus.NEEDS_MORE_EVIDENCE);
        assertThat(result.decision().rootCauseCategory()).isEqualTo(RootCauseCategory.UNKNOWN);
    }

    @Test
    void advisoryHistoryAndRunbookDoNotBecomeCurrentConfigurationProofOrCitations() {
        ClaudeInvocationResult result = client.analyze(request(List.of(
                evidence("live-health", "KUBERNETES", "WORKLOAD_HEALTH", "Workload is healthy", "ready=true"),
                evidence("historical", "HISTORICAL_INCIDENT", "HISTORICAL_MATCH",
                        "Prior parameter mismatch", "Could not resolve parameter /old/db-url"),
                evidence("runbook", "RUNBOOK", "RUNBOOK_EXCERPT", "Parameter runbook",
                        "A missing parameter can cause configuration failures"))));

        assertThat(result.decision().status()).isEqualTo(ReasoningStatus.NEEDS_MORE_EVIDENCE);
        assertThat(result.decision().rootCauseCategory()).isEqualTo(RootCauseCategory.UNKNOWN);
        assertThat(result.decision().hypotheses()).allSatisfy(hypothesis -> assertThat(hypothesis.evidenceIds())
                .doesNotContain("historical", "runbook"));
    }

    private ClaudeReasoningRequest request(List<ReasoningEvidence> evidence) {
        return new ClaudeReasoningRequest(UUID.randomUUID(), "SERVICE_TRIAGE", "sample-service", "TEST",
                null, "Investigate an unconfirmed symptom", 1, evidence, List.of(), null, false);
    }

    private ReasoningEvidence evidence(String id, String source, String type, String summary, String content) {
        return new ReasoningEvidence(id, source, type, Instant.now().toString(), summary, content, "HIGH");
    }
}
