package com.jmopsagent.jenkins;

import com.jmopsagent.connector.DeploymentInfo;
import com.jmopsagent.connector.Environment;
import java.util.List;
import java.util.Optional;

public interface JenkinsConnector {
    Optional<DeploymentInfo> getLatestDeployment(String service, Environment environment);
    List<DeploymentInfo> getLastBuilds(String service, Environment environment, int limit);
}
