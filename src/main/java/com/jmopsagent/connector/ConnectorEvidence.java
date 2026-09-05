package com.jmopsagent.connector;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/** Connector-owned DTO. Content is sanitized by the application before persistence or AI use. */
public record ConnectorEvidence(
        String externalId,
        EvidenceSource source,
        EvidenceType type,
        Instant timestamp,
        String service,
        Environment environment,
        String summary,
        String content,
        URI sourceUrl,
        Map<String, String> metadata,
        double reliability) {

    public ConnectorEvidence {
        if (source == null || type == null || environment == null) {
            throw new IllegalArgumentException("source, type and environment are required");
        }
        summary = summary == null ? "" : summary;
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        reliability = Math.max(0.0, Math.min(1.0, reliability));
    }
}
