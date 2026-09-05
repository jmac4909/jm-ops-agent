package com.jmopsagent.claude;

import com.jmopsagent.domain.EvidenceItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Builds a second, prompt-specific bound over already-sanitized persisted evidence. */
@Component
public class BoundedReasoningEvidenceMapper {
    private static final int MAX_TOTAL_CHARACTERS = 500_000;
    private static final int MAX_ITEM_CONTENT_CHARACTERS = 20_000;
    private static final String TRUNCATED = "\n[PROMPT SAMPLE TRUNCATED]";

    public List<ReasoningEvidence> map(List<EvidenceItem> items) {
        List<ReasoningEvidence> result = new ArrayList<>();
        int remaining = MAX_TOTAL_CHARACTERS;
        for (EvidenceItem item : items) {
            if (remaining < 100) break;
            String rawSummary = item.getSummary() == null ? "" : item.getSummary();
            String summary = rawSummary.substring(0, Math.min(rawSummary.length(), Math.min(2_000, remaining)));
            remaining -= summary.length();
            String rawContent = item.getSanitizedContent() == null ? "" : item.getSanitizedContent();
            int contentLimit = Math.min(MAX_ITEM_CONTENT_CHARACTERS, remaining);
            String content;
            if (rawContent.length() <= contentLimit) {
                content = rawContent;
            } else if (contentLimit > TRUNCATED.length()) {
                content = rawContent.substring(0, contentLimit - TRUNCATED.length()) + TRUNCATED;
            } else {
                content = rawContent.substring(0, contentLimit);
            }
            remaining -= content.length();
            result.add(new ReasoningEvidence(item.getId().toString(), item.getSourceSystem().name(),
                    item.getEvidenceType().name(), item.getOccurredAt() == null ? null : item.getOccurredAt().toString(),
                    summary, content, item.getReliability().name()));
        }
        return List.copyOf(result);
    }
}
