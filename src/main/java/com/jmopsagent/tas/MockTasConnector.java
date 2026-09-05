package com.jmopsagent.tas;

import static com.jmopsagent.connector.mock.MockFixtures.REQUEST_TIME;
import static com.jmopsagent.connector.mock.MockFixtures.evidence;

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
public class MockTasConnector implements TasConnector {
    @Override
    public List<ConnectorEvidence> getApplicationStatus(String service, Environment environment) {
        return singleton(service, environment, EvidenceType.WORKLOAD_HEALTH, "TAS application is started",
                "requestedState=started instances=2/2");
    }

    @Override
    public List<ConnectorEvidence> getRecentLogs(String service, Environment environment, EvidenceQuery query) {
        return singleton(service, environment, EvidenceType.APPLICATION_LOG, "No TAS errors in selected window",
                "Representative bounded sample: application healthy");
    }

    @Override
    public List<ConnectorEvidence> getEnvironmentMetadata(String service, Environment environment) {
        return singleton(service, environment, EvidenceType.CONFIGURATION, "TAS environment keys collected",
                "SPRING_PROFILES_ACTIVE=[REDACTED_AT_SOURCE]; binding values omitted");
    }

    @Override
    public List<ConnectorEvidence> getRoutes(String service, Environment environment) {
        return singleton(service, environment, EvidenceType.NETWORK, "TAS route is mapped", "routeCount=1");
    }

    @Override
    public List<ConnectorEvidence> getInstances(String service, Environment environment) {
        return singleton(service, environment, EvidenceType.WORKLOAD_HEALTH, "All TAS instances are running",
                "instances=2 running=2 crashed=0");
    }

    private static List<ConnectorEvidence> singleton(String service, Environment environment, EvidenceType type,
            String summary, String content) {
        String safeService = ConnectorInputValidator.service(service);
        return List.of(evidence("tas-" + type.name().toLowerCase() + "-" + safeService, EvidenceSource.TAS, type,
                REQUEST_TIME, safeService, environment, summary, content,
                Map.of("mock", "true", "redactedAtSource", "true"), 0.9));
    }
}
