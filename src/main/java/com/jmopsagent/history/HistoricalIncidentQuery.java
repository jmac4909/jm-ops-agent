package com.jmopsagent.history;

import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.domain.RootCauseCategory;

public record HistoricalIncidentQuery(
        String service,
        DeploymentEnvironment environment,
        RootCauseCategory category,
        String errorSignature,
        String context
) {
    public static HistoricalIncidentQuery forService(String service, DeploymentEnvironment environment,
                                                     String errorSignature) {
        return new HistoricalIncidentQuery(service, environment, null, errorSignature, null);
    }
}
