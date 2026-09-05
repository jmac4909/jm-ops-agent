package com.jmopsagent.connector;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DeploymentInfo(
        String service,
        Environment environment,
        String jobName,
        long buildNumber,
        String result,
        Instant timestamp,
        String commitSha,
        URI sourceUrl,
        List<String> changes,
        List<String> failedStages,
        List<String> consoleErrors,
        Map<String, String> metadata) {
    public DeploymentInfo {
        changes = changes == null ? List.of() : List.copyOf(changes);
        failedStages = failedStages == null ? List.of() : List.copyOf(failedStages);
        consoleErrors = consoleErrors == null ? List.of() : List.copyOf(consoleErrors);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
