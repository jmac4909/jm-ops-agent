package com.jmopsagent.claude;

import java.util.List;
import java.util.UUID;

public record ClaudeFollowUpRequest(
        UUID investigationId,
        String question,
        String sessionId,
        String priorDiagnosis,
        List<ReasoningEvidence> evidence,
        boolean targetedEvidenceCollected,
        String targetedEvidenceContext) {

    public ClaudeFollowUpRequest {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
