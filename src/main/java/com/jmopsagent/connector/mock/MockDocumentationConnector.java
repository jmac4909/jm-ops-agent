package com.jmopsagent.connector.mock;

import static com.jmopsagent.connector.mock.MockFixtures.REQUEST_TIME;
import static com.jmopsagent.connector.mock.MockFixtures.evidence;

import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.DocumentationConnector;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceSource;
import com.jmopsagent.connector.EvidenceType;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local-mock")
public class MockDocumentationConnector implements DocumentationConnector {
    @Override
    public List<ConnectorEvidence> findRunbooks(String service, Environment environment, String problem, int limit) {
        String safeService = ConnectorInputValidator.service(service);
        ConnectorInputValidator.boundedLimit(limit, 25);
        return List.of(evidence("runbook-" + safeService, EvidenceSource.RUNBOOK, EvidenceType.RUNBOOK, REQUEST_TIME,
                safeService, environment, "Advisory service recovery runbook",
                "Check health, deployment, parameter references, and dependency errors. This mock runbook may be stale.",
                Map.of("authoritative", "false", "mock", "true"), 0.55));
    }
}
