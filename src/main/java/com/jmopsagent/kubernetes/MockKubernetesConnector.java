package com.jmopsagent.kubernetes;

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
public class MockKubernetesConnector implements KubernetesConnector {
    @Override
    public List<ConnectorEvidence> getWorkloadHealth(String service, Environment environment) {
        String safeService = ConnectorInputValidator.service(service);
        String scenario = scenario(safeService);
        if (scenario.equals("bad-config") || scenario.equals("readiness-failure")) {
            return List.of(
                    item("k8s-deployment-health-" + safeService, EvidenceType.WORKLOAD_HEALTH, safeService, environment,
                            "Deployment has unavailable replicas", "desiredReplicas=2 availableReplicas=1 readyReplicas=1 unavailableReplicas=1",
                            Map.of("desired", "2", "ready", "1", "available", "1", "status", "DEGRADED")),
                    item("k8s-pod-health-" + safeService, EvidenceType.WORKLOAD_HEALTH, safeService, environment,
                            "Newest pod is not ready", "pod=" + safeService + "-7f8c9 ready=false restartCount=3 reason=ReadinessProbeFailed",
                            Map.of("ready", "false", "restartCount", "3", "status", "DEGRADED")));
        }
        return List.of(item("k8s-health-" + safeService, EvidenceType.WORKLOAD_HEALTH, safeService, environment,
                "Workload is healthy", "desiredReplicas=2 availableReplicas=2 readyReplicas=2 restartCount=0",
                Map.of("desired", "2", "ready", "2", "status", "HEALTHY")));
    }

    @Override
    public List<ConnectorEvidence> getRecentPodEvents(String service, Environment environment, EvidenceQuery query) {
        String safeService = ConnectorInputValidator.service(service);
        if (scenario(safeService).equals("bad-config") || scenario(safeService).equals("readiness-failure")) {
            return List.of(item("k8s-event-" + safeService, EvidenceType.POD_EVENT, safeService, environment,
                    "Readiness probe failed repeatedly", "Warning Unhealthy readiness probe returned HTTP 503 (x9)",
                    Map.of("severity", "WARNING", "frequency", "9")));
        }
        return List.of(item("k8s-event-" + safeService, EvidenceType.POD_EVENT, safeService, environment,
                "No warning events", "Normal Started containers are healthy", Map.of("severity", "NORMAL")));
    }

    @Override
    public List<ConnectorEvidence> getRecentPodLogs(String service, Environment environment, EvidenceQuery query) {
        String safeService = ConnectorInputValidator.service(service);
        String content = switch (scenario(safeService)) {
            case "bad-config" -> "ERROR ParameterResolutionException: Could not resolve parameter /catalog/db-ur\n"
                    + "ERROR Application readiness state changed to REFUSING_TRAFFIC";
            case "readiness-failure" -> "WARN Readiness probe returned DOWN while dependency initialization is pending";
            case "downstream-500" -> "ERROR inventory-api returned HTTP 500 for /availability";
            case "database-error" -> "ERROR PSQLException: Connection refused while acquiring database connection";
            default -> "INFO Application ready; no ERROR events in selected window";
        };
        if (content.length() > query.maxContentCharacters()) content = content.substring(0, query.maxContentCharacters());
        return List.of(item("k8s-logs-" + safeService, EvidenceType.APPLICATION_LOG, safeService, environment,
                "Representative recent application logs", content, Map.of("bounded", "true", "sampleCount", "2")));
    }

    @Override
    public List<ConnectorEvidence> getDeploymentMetadata(String service, Environment environment) {
        String safeService = ConnectorInputValidator.service(service);
        return List.of(item("k8s-deployment-" + safeService, EvidenceType.DEPLOYMENT, safeService, environment,
                "Deployment revision 42 is active", "image=registry.invalid/" + safeService + ":b7c0ffee revision=42 replicas=2",
                Map.of("revision", "42", "imageTag", "b7c0ffee")));
    }

    @Override
    public List<ConnectorEvidence> getEffectiveConfiguration(String service, Environment environment) {
        String safeService = ConnectorInputValidator.service(service);
        if (scenario(safeService).equals("bad-config")) {
            return List.of(item("k8s-config-" + safeService, EvidenceType.CONFIGURATION, safeService, environment,
                    "Database parameter reference differs from the expected path",
                    "DB_URL_PARAMETER=/catalog/db-ur; values and secret material omitted",
                    Map.of("redactedAtSource", "true", "configurationKey", "DB_URL_PARAMETER")));
        }
        return List.of(item("k8s-config-" + safeService, EvidenceType.CONFIGURATION, safeService, environment,
                "Non-secret configuration metadata is consistent", "configuration keys present; values and secret material omitted",
                Map.of("redactedAtSource", "true")));
    }

    @Override
    public List<ConnectorEvidence> getNetworking(String service, Environment environment) {
        String safeService = ConnectorInputValidator.service(service);
        return List.of(item("k8s-network-" + safeService, EvidenceType.NETWORK, safeService, environment,
                "Service endpoints resolved", "service endpoints=2 ingress configured=true",
                Map.of("endpointCount", "2", "ingress", "true")));
    }

    private static ConnectorEvidence item(String id, EvidenceType type, String service, Environment environment,
            String summary, String content, Map<String, String> metadata) {
        return evidence(id, EvidenceSource.KUBERNETES, type, REQUEST_TIME, service, environment, summary, content,
                metadata, 0.95);
    }
}
