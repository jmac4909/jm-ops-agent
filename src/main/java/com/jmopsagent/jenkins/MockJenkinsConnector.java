package com.jmopsagent.jenkins;

import static com.jmopsagent.connector.mock.MockFixtures.DEPLOYED_SHA;
import static com.jmopsagent.connector.mock.MockFixtures.DEPLOYMENT_TIME;
import static com.jmopsagent.connector.mock.MockFixtures.scenario;

import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.DeploymentInfo;
import com.jmopsagent.connector.Environment;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local-mock")
public class MockJenkinsConnector implements JenkinsConnector {
    @Override
    public Optional<DeploymentInfo> getLatestDeployment(String service, Environment environment) {
        String safeService = ConnectorInputValidator.service(service);
        if (scenario(safeService).equals("deployment-failure")) {
            return Optional.of(new DeploymentInfo(safeService, environment, safeService + "-test-deploy", 88,
                    "FAILURE", DEPLOYMENT_TIME, "deadbeef1234567890", mockUrl(safeService, 88),
                    List.of("Upgrade Spring Boot dependency"), List.of("Deploy to TEST"),
                    List.of("Deployment verification failed: workload did not reach Ready state"),
                    Map.of("mock", "true", "deployed", "false")));
        }
        List<String> changes = scenario(safeService).equals("bad-config")
                ? List.of("Changed TEST database parameter mapping", "Updated deployment image")
                : List.of("Routine dependency update");
        return Optional.of(new DeploymentInfo(safeService, environment, safeService + "-test-deploy", 42,
                "SUCCESS", DEPLOYMENT_TIME, DEPLOYED_SHA, mockUrl(safeService, 42), changes, List.of(), List.of(),
                Map.of("mock", "true", "deployed", "true")));
    }

    @Override
    public List<DeploymentInfo> getLastBuilds(String service, Environment environment, int limit) {
        ConnectorInputValidator.boundedLimit(limit, 25);
        DeploymentInfo latest = getLatestDeployment(service, environment).orElseThrow();
        return IntStream.range(0, limit).mapToObj(offset -> offset == 0 ? latest : new DeploymentInfo(
                latest.service(), latest.environment(), latest.jobName(), latest.buildNumber() - offset, "SUCCESS",
                latest.timestamp().minus(offset, ChronoUnit.DAYS), "previous" + offset, mockUrl(latest.service(),
                latest.buildNumber() - offset), List.of("Previous successful change"), List.of(), List.of(),
                Map.of("mock", "true", "deployed", "true"))).toList();
    }

    private static URI mockUrl(String service, long number) {
        return URI.create("https://example.invalid/mock/jenkins/" + service + "/" + number);
    }
}
