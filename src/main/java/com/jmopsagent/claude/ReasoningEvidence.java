package com.jmopsagent.claude;

public record ReasoningEvidence(
        String id,
        String source,
        String type,
        String observedAt,
        String summary,
        String content,
        String reliability) {
}
