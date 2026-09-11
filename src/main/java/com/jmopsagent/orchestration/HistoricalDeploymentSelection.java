package com.jmopsagent.orchestration;

import com.jmopsagent.connector.DeploymentInfo;
import com.jmopsagent.domain.IncidentWindow;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Jenkins completion provides a historical candidate, never a runtime attestation. */
final class HistoricalDeploymentSelection {
    private HistoricalDeploymentSelection() {}

    static Optional<Instant> completedAt(DeploymentInfo build) {
        try {
            return Optional.of(Instant.parse(build.metadata().getOrDefault("completedAt", "")));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    static Optional<DeploymentInfo> atOrBefore(List<DeploymentInfo> builds, Instant anchor) {
        return builds.stream().filter(build -> "SUCCESS".equalsIgnoreCase(build.result()))
                .filter(build -> !"false".equalsIgnoreCase(build.metadata().get("deployed")))
                .filter(build -> completedAt(build).map(time -> !time.isAfter(anchor)).orElse(false))
                .max(Comparator.comparing(build -> completedAt(build).orElseThrow()));
    }

    static String phase(Instant time, IncidentWindow window) {
        if (time.isBefore(window.start())) return "Before incident";
        if (time.isBefore(window.end())) return "During incident";
        return "After incident (possible recovery)";
    }
}
