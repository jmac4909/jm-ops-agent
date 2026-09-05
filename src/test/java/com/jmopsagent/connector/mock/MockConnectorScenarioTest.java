package com.jmopsagent.connector.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.gitlab.MockGitLabConnector;
import com.jmopsagent.jenkins.MockJenkinsConnector;
import com.jmopsagent.kubernetes.MockKubernetesConnector;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MockConnectorScenarioTest {
    private final MockKubernetesConnector kubernetes = new MockKubernetesConnector();
    private final MockJenkinsConnector jenkins = new MockJenkinsConnector();
    private final MockGitLabConnector gitLab = new MockGitLabConnector();

    @Test
    void catalogServiceScenarioCorrelatesReadinessDeploymentAndConfigChange() {
        assertThat(kubernetes.getWorkloadHealth("catalog-service", Environment.TEST))
                .anySatisfy(item -> assertThat(item.summary()).containsIgnoringCase("not ready"));
        assertThat(kubernetes.getEffectiveConfiguration("catalog-service", Environment.TEST).getFirst().content())
                .contains("/catalog/db-ur");
        assertThat(jenkins.getLatestDeployment("catalog-service", Environment.TEST)).isPresent().get()
                .satisfies(deployment -> assertThat(deployment.result()).isEqualTo("SUCCESS"));
        assertThat(gitLab.compareRevisions("catalog-service", "previous", "b7c0ffee", 20_000).getFirst().boundedDiff())
                .contains("/catalog/db-url", "/catalog/db-ur");
    }

    @Test
    void includesRepresentativeHealthyAndFailureFixtures() {
        assertThat(kubernetes.getWorkloadHealth("healthy-service", Environment.TEST).getFirst().summary())
                .containsIgnoringCase("healthy");
        assertThat(jenkins.getLatestDeployment("deployment-failure-service", Environment.TEST)).isPresent().get()
                .satisfies(deployment -> assertThat(deployment.result()).isEqualTo("FAILURE"));
        EvidenceQuery query = new EvidenceQuery(Instant.parse("2026-01-15T10:30:00Z"),
                Instant.parse("2026-01-15T10:50:00Z"), 50, 50_000);
        assertThat(kubernetes.getRecentPodLogs("database-error-service", Environment.TEST, query).getFirst().content())
                .contains("PSQLException");
        assertThat(kubernetes.getRecentPodLogs("downstream-500-service", Environment.TEST, query).getFirst().content())
                .contains("HTTP 500");
    }
}
