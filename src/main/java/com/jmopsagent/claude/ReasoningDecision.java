package com.jmopsagent.claude;

import com.jmopsagent.domain.RootCauseCategory;

import java.util.List;

public record ReasoningDecision(
        ReasoningStatus status,
        String summary,
        List<Hypothesis> hypotheses,
        List<NextEvidenceRequest> nextEvidenceRequests,
        RootCauseCategory rootCauseCategory,
        List<String> recommendedActions) {

    public ReasoningDecision {
        hypotheses = hypotheses == null ? List.of() : List.copyOf(hypotheses);
        nextEvidenceRequests = nextEvidenceRequests == null ? List.of() : List.copyOf(nextEvidenceRequests);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
    }
}
