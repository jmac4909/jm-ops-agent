package com.jmopsagent.connector.mock;

import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceSource;
import com.jmopsagent.connector.EvidenceType;
import java.net.URI;
import java.time.Instant;
import java.util.Map;

public final class MockFixtures {
    public static final String TRACKING_ID = "DEMO-TRACE-001";
    public static final String FAILING_SERVICE = "catalog-service";
    public static final Instant REQUEST_TIME = Instant.parse("2026-01-15T10:40:00Z");
    public static final Instant DEPLOYMENT_TIME = Instant.parse("2026-01-15T10:37:00Z");
    public static final String DEPLOYED_SHA = "b7c0ffee42d00d1234567890abcdef1234567890";

    private MockFixtures() {
    }

    public static ConnectorEvidence evidence(String id, EvidenceSource source, EvidenceType type, Instant timestamp,
            String service, Environment environment, String summary, String content, Map<String, String> metadata,
            double reliability) {
        return new ConnectorEvidence(id, source, type, timestamp, service, environment, summary, content,
                URI.create("https://example.invalid/mock/" + id), metadata, reliability);
    }

    public static String scenario(String service) {
        if (service == null) return "unknown";
        return switch (service.toLowerCase()) {
            case FAILING_SERVICE, "bad-config-service" -> "bad-config";
            case "readiness-failure-service" -> "readiness-failure";
            case "deployment-failure-service" -> "deployment-failure";
            case "downstream-500-service" -> "downstream-500";
            case "database-error-service" -> "database-error";
            case "healthy-service", "edge-gateway", "identity-service" -> "healthy";
            default -> "healthy";
        };
    }
}
