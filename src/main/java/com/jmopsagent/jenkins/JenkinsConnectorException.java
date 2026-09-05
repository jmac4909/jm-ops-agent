package com.jmopsagent.jenkins;

/**
 * A sanitized connector failure. Messages deliberately omit endpoint URLs, credentials, response
 * bodies, job paths, and low-level exception messages.
 */
public final class JenkinsConnectorException extends RuntimeException {
    private final JenkinsFailureKind kind;

    JenkinsConnectorException(JenkinsFailureKind kind, String safeMessage) {
        super(safeMessage);
        this.kind = kind;
    }

    JenkinsConnectorException(JenkinsFailureKind kind, String safeMessage, Throwable ignoredCause) {
        // The low-level cause can contain an endpoint, response body, or encoded credentials.
        // Classification is retained, but unsafe transport details do not escape this boundary.
        super(safeMessage);
        this.kind = kind;
    }

    public JenkinsFailureKind kind() {
        return kind;
    }
}
