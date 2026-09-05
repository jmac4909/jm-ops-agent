package com.jmopsagent.sanitization;

/** Sanitizes evidence before it is persisted or sent to a reasoning engine. */
public interface EvidenceSanitizer {
    SanitizationResult sanitize(String untrustedContent);

    /** Applies additional body suppression suitable for application/log events. */
    default SanitizationResult sanitizeLogContent(String untrustedContent) {
        return sanitize(untrustedContent);
    }
}
