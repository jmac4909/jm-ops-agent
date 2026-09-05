package com.jmopsagent.registry;

import com.jmopsagent.connector.DeploymentInfo;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.jenkins.JenkinsConnector;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JenkinsServiceRegistryDiscoveryHookTest {

    @Test
    void projectsOnlyTheExactJobAndValidatedController() {
        JenkinsConnector connector = mock(JenkinsConnector.class);
        when(connector.getLatestDeployment("sample-service", Environment.TEST))
                .thenReturn(Optional.of(deployment("sample-service", Environment.TEST,
                        "folder/sample-service/deploy", Map.of(
                                "controller", "reader-one",
                                "unrelated", "must-not-be-persisted"))));
        JenkinsServiceRegistryDiscoveryHook hook = new JenkinsServiceRegistryDiscoveryHook(connector);

        RegistryDiscoveryUpdate update = hook.discover(
                new ServiceDefinition("sample-service"), DeploymentEnvironment.TEST).orElseThrow();

        assertThat(update.provenance()).isEqualTo(RegistryProvenance.DISCOVERED_JENKINS);
        assertThat(update.attributes()).containsOnlyKeys("jenkins.job.TEST", "jenkins.controller.TEST");
        assertThat(update.attributes().get("jenkins.job.TEST"))
                .containsExactly("folder/sample-service/deploy");
        assertThat(update.attributes().values())
                .noneMatch(values -> values.contains("must-not-be-persisted")
                        || values.contains("sensitive build output"));
    }

    @Test
    void rejectsFuzzyJobNamingAndEnvironmentMismatches() {
        JenkinsConnector connector = mock(JenkinsConnector.class);
        when(connector.getLatestDeployment("sample-service", Environment.TEST))
                .thenReturn(Optional.of(deployment("sample-service", Environment.TEST,
                        "sample-service-old-test-deploy", Map.of())));
        JenkinsServiceRegistryDiscoveryHook hook = new JenkinsServiceRegistryDiscoveryHook(connector);

        assertThat(hook.discover(new ServiceDefinition("sample-service"), DeploymentEnvironment.TEST)).isEmpty();

        when(connector.getLatestDeployment("sample-service", Environment.TEST))
                .thenReturn(Optional.of(deployment("sample-service", Environment.DEV,
                        "sample-service-test-deploy", Map.of())));
        assertThat(hook.discover(new ServiceDefinition("sample-service"), DeploymentEnvironment.TEST)).isEmpty();
    }

    private static DeploymentInfo deployment(String service, Environment environment, String job,
            Map<String, String> metadata) {
        return new DeploymentInfo(service, environment, job, 42, "SUCCESS", Instant.parse("2026-01-01T00:00:00Z"),
                "abcdef123456", URI.create("https://jenkins.example.invalid/job/sample/42"), List.of(), List.of(),
                List.of("sensitive build output"), metadata);
    }
}
