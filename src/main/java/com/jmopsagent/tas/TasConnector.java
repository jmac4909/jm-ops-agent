package com.jmopsagent.tas;

import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import java.util.List;

public interface TasConnector {
    List<ConnectorEvidence> getApplicationStatus(String service, Environment environment);
    List<ConnectorEvidence> getRecentLogs(String service, Environment environment, EvidenceQuery query);
    List<ConnectorEvidence> getEnvironmentMetadata(String service, Environment environment);
    List<ConnectorEvidence> getRoutes(String service, Environment environment);
    List<ConnectorEvidence> getInstances(String service, Environment environment);
}
