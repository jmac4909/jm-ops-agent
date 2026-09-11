package com.jmopsagent.jenkins;

import com.jmopsagent.connector.DeploymentInfo;
import com.jmopsagent.connector.Environment;
import java.util.List;
import java.util.Optional;

public interface JenkinsConnector {
    Optional<DeploymentInfo> getLatestDeployment(String service, Environment environment);
    List<DeploymentInfo> getLastBuilds(String service, Environment environment, int limit);
    /** Bounded retained build metadata, with no console/stage fan-out. Empty when unsupported. */
    default List<DeploymentInfo> getDeploymentHistory(String service, Environment environment) {
        return List.of();
    }
    /** Seek retained metadata near a historical failure; never substitute the current latest revision. */
    default List<DeploymentInfo> getDeploymentHistory(String service, Environment environment, java.time.Instant anchor) {
        return getDeploymentHistory(service, environment);
    }
}
