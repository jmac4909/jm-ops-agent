package com.jmopsagent.claude;

import java.util.List;
import java.util.UUID;

public record ClaudeReasoningRequest(
        UUID investigationId,
        String investigationType,
        String service,
        String environment,
        String trackingId,
        String problem,
        int iteration,
        List<ReasoningEvidence> evidence,
        List<String> relevantHistory,
        String sessionId,
        boolean limitApproaching) {

    public ClaudeReasoningRequest {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        relevantHistory = relevantHistory == null ? List.of() : List.copyOf(relevantHistory);
    }
}
