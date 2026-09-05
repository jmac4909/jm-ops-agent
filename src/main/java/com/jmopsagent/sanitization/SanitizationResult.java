package com.jmopsagent.sanitization;

import java.util.List;

public record SanitizationResult(
        String sanitizedContent,
        boolean redactionApplied,
        int redactionCount,
        List<String> redactionCategories,
        boolean truncated
) {
    public SanitizationResult {
        sanitizedContent = sanitizedContent == null ? "" : sanitizedContent;
        redactionCategories = redactionCategories == null ? List.of() : List.copyOf(redactionCategories);
        if (redactionCount < 0) {
            throw new IllegalArgumentException("redactionCount cannot be negative");
        }
    }
}
