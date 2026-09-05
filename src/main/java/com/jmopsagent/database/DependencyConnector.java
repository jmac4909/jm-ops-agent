package com.jmopsagent.database;

import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import java.util.List;

/** A narrow extension point invoked only when existing evidence points to a dependency. */
public interface DependencyConnector {
    boolean supports(DependencyType type);
    List<ConnectorEvidence> inspect(String service, Environment environment, DependencyType type, EvidenceQuery query);
}
