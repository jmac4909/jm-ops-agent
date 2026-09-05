package com.jmopsagent.history;

import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.domain.RootCauseCategory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record HistoricalIncidentMatch(
        UUID investigationId,
        double score,
        boolean confirmed,
        String service,
        DeploymentEnvironment environment,
        RootCauseCategory category,
        String diagnosis,
        String actualRootCause,
        String successfulRemediation,
        Instant completedAt,
        List<String> matchedTerms
) {
    public HistoricalIncidentMatch {
        score = Math.max(0, Math.min(1, score));
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
    }

    /** Safe prompt/UI representation; historical evidence remains advisory, not authoritative. */
    public String asAdvisorySummary() {
        String outcome = actualRootCause == null || actualRootCause.isBlank() ? diagnosis : actualRootCause;
        return "Historical incident " + investigationId + " (similarity=" + String.format("%.2f", score)
                + ", confirmed=" + confirmed + "): " + outcome;
    }
}
