package com.jmopsagent.connector;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local-live")
public class NoOpDocumentationConnector implements DocumentationConnector {
    @Override
    public List<ConnectorEvidence> findRunbooks(String service, Environment environment, String problem, int limit) {
        ConnectorInputValidator.service(service);
        ConnectorInputValidator.boundedLimit(limit, 25);
        return List.of();
    }
}
