package com.jmopsagent.connector;

import java.util.List;

/** Advisory only: live operational evidence takes precedence over documentation. */
public interface DocumentationConnector {
    List<ConnectorEvidence> findRunbooks(String service, Environment environment, String problem, int limit);
}
