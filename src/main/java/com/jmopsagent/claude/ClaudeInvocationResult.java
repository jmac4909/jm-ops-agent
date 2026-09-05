package com.jmopsagent.claude;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public record ClaudeInvocationResult(
        String sessionId,
        ReasoningDecision decision,
        Instant startedAt,
        Instant completedAt,
        Duration duration,
        Integer numberOfTurns,
        BigDecimal totalCostUsd,
        Map<String, Object> usage,
        String error,
        boolean mock) {

    public ClaudeInvocationResult {
        usage = usage == null ? Map.of() : Map.copyOf(usage);
    }

    public boolean successful() {
        return error == null && decision != null;
    }
}
