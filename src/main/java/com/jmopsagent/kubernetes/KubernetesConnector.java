package com.jmopsagent.kubernetes;

import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import java.util.List;

public interface KubernetesConnector {
    List<ConnectorEvidence> getWorkloadHealth(String service, Environment environment);
    List<ConnectorEvidence> getRecentPodEvents(String service, Environment environment, EvidenceQuery query);
    List<ConnectorEvidence> getRecentPodLogs(String service, Environment environment, EvidenceQuery query);
    List<ConnectorEvidence> getDeploymentMetadata(String service, Environment environment);
    List<ConnectorEvidence> getEffectiveConfiguration(String service, Environment environment);
    List<ConnectorEvidence> getNetworking(String service, Environment environment);
}
