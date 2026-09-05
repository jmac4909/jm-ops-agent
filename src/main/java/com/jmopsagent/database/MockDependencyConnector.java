package com.jmopsagent.database;

import static com.jmopsagent.connector.mock.MockFixtures.REQUEST_TIME;
import static com.jmopsagent.connector.mock.MockFixtures.evidence;
import static com.jmopsagent.connector.mock.MockFixtures.scenario;

import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.EvidenceSource;
import com.jmopsagent.connector.EvidenceType;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local-mock")
public class MockDependencyConnector implements DependencyConnector {
    @Override
    public boolean supports(DependencyType type) {
        return type != null;
    }

    @Override
    public List<ConnectorEvidence> inspect(String service, Environment environment, DependencyType type,
            EvidenceQuery query) {
        String safeService = ConnectorInputValidator.service(service);
        if (type == null) throw new IllegalArgumentException("Dependency type is required");
        String state;
        String summary;
        if (type == DependencyType.POSTGRESQL && (scenario(safeService).equals("database-error")
                || scenario(safeService).equals("bad-config"))) {
            state = scenario(safeService).equals("bad-config")
                    ? "Connection was not attempted because the configured parameter path could not be resolved"
                    : "Application logs show connection refused; no database query was executed by JM Ops Agent";
            summary = "Database-related operational evidence indicates a failure";
        } else if (type == DependencyType.DOWNSTREAM_API && scenario(safeService).equals("downstream-500")) {
            state = "Application evidence reports inventory-api returned HTTP 500";
            summary = "Downstream API evidence indicates a failure";
        } else {
            state = "No dependency failure indicator was found in the available application evidence";
            summary = "No dependency failure evidence";
        }
        return List.of(evidence("dependency-" + type.name().toLowerCase() + "-" + safeService,
                EvidenceSource.DATABASE, EvidenceType.DEPENDENCY_HEALTH, REQUEST_TIME, safeService, environment,
                summary, state, Map.of("dependencyType", type.name(), "mode", "EVIDENCE_ONLY", "readOnly", "true"), 0.75));
    }
}
