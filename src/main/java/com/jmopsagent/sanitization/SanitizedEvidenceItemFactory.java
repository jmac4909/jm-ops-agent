package com.jmopsagent.sanitization;

import com.jmopsagent.domain.EvidenceItem;
import com.jmopsagent.connector.ConnectorInputValidator;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** The supported raw connector-output to persisted-evidence boundary. */
@Component
public class SanitizedEvidenceItemFactory {

    private final EvidenceSanitizer sanitizer;

    public SanitizedEvidenceItemFactory(EvidenceSanitizer sanitizer) {
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
    }

    public EvidenceItem create(EvidenceDraft draft) {
        Objects.requireNonNull(draft, "draft");
        boolean logLike = isLogLike(draft);
        return EvidenceItem.createSanitized(
                draft.sourceSystem(),
                draft.evidenceType(),
                draft.occurredAt(),
                validatedServiceIdentifier(draft.service()),
                draft.environment(),
                logLike ? sanitizer.sanitizeLogContent(draft.summary()) : sanitizer.sanitize(draft.summary()),
                logLike ? sanitizer.sanitizeLogContent(draft.content()) : sanitizer.sanitize(draft.content()),
                sanitizer.sanitize(draft.sourceUrl()),
                sanitizer.sanitize(draft.metadataJson()),
                draft.reliability());
    }

    private String validatedServiceIdentifier(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ConnectorInputValidator.service(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean isLogLike(EvidenceDraft draft) {
        return switch (draft.evidenceType()) {
            case TRACKING_EVENT, CALL_CHAIN, ERROR_LOG, LOG_PATTERN, POD_LOG,
                    RECENT_BUSINESS_CALLS, DEPENDENCY_STATUS -> true;
            default -> false;
        };
    }
}
