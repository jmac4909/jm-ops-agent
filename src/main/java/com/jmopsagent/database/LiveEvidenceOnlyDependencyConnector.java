package com.jmopsagent.database;

import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.EvidenceSource;
import com.jmopsagent.connector.EvidenceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** MVP adapter: reports only previously surfaced dependency indicators; it never connects to a dependency. */
@Component
@Profile("local-live")
public class LiveEvidenceOnlyDependencyConnector implements DependencyConnector {
    @Override
    public boolean supports(DependencyType type) {
        return type != null;
    }

    @Override
    public List<ConnectorEvidence> inspect(String service, Environment environment, DependencyType type,
            EvidenceQuery query) {
        String safeService = ConnectorInputValidator.service(service);
        if (type == null) throw new IllegalArgumentException("Dependency type is required");
        Instant now = Instant.now();
        return List.of(new ConnectorEvidence("dependency-advisory-" + now.toEpochMilli(), EvidenceSource.DATABASE,
                EvidenceType.DEPENDENCY_HEALTH, now, safeService, environment,
                "Direct dependency adapter is not configured",
                "Use bounded runtime and configuration evidence for this dependency branch; no live dependency call was made.",
                null, Map.of("dependencyType", type.name(), "mode", "EVIDENCE_ONLY", "readOnly", "true"), 0.5));
    }
}
