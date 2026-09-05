package com.jmopsagent.sanitization;

import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.domain.EvidenceReliability;
import com.jmopsagent.domain.EvidenceSource;
import com.jmopsagent.domain.EvidenceType;

import java.time.Instant;
import java.util.Objects;

/** Ephemeral, untrusted connector output. Never persist this type. */
public record EvidenceDraft(
        EvidenceSource sourceSystem,
        EvidenceType evidenceType,
        Instant occurredAt,
        String service,
        DeploymentEnvironment environment,
        String summary,
        String content,
        String sourceUrl,
        String metadataJson,
        EvidenceReliability reliability
) {
    public EvidenceDraft {
        Objects.requireNonNull(sourceSystem, "sourceSystem");
        Objects.requireNonNull(evidenceType, "evidenceType");
        reliability = reliability == null ? EvidenceReliability.UNKNOWN : reliability;
    }
}
