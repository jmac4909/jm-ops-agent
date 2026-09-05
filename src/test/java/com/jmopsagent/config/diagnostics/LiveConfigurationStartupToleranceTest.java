package com.jmopsagent.config.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jmopsagent.JmOpsAgentApplication;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.process.ProcessResult;
import com.jmopsagent.connector.process.ProcessRunner;
import com.jmopsagent.controller.DiagnosticsController;
import com.jmopsagent.gitlab.GitLabConnector;
import com.jmopsagent.jenkins.JenkinsConnector;
import com.jmopsagent.jenkins.JenkinsConnectorException;
import com.jmopsagent.jenkins.JenkinsFailureKind;
import com.jmopsagent.splunk.SplunkConnector;
import com.jmopsagent.splunk.SplunkSearchOutcome;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.ui.ExtendedModelMap;

@SpringBootTest(classes = {JmOpsAgentApplication.class, LiveConfigurationStartupToleranceTest.TestBeans.class},
        properties = {
                "spring.datasource.url=jdbc:h2:mem:jmops-live-invalid-config;DB_CLOSE_DELAY=-1",
                "jmops.claude.enabled=false",
                "jmops.integrations.jenkins.base-url=http://jenkins.example.invalid",
                "jmops.integrations.jenkins.username=startup-reader",
                "jmops.integrations.jenkins.token=jenkins-startup-secret",
                "jmops.integrations.gitlab.base-url=https" + "://invalid host.example.invalid/private-marker",
                "jmops.integrations.gitlab.token=gitlab-startup-secret",
                "jmops.integrations.splunk.base-url=https://logs.example.invalid",
                "jmops.integrations.splunk.auth-mode=BEARER_TOKEN",
                "jmops.integrations.splunk.token=splunk-startup-secret",
                "jmops.integrations.splunk.request-timeout=not-a-duration-with-sensitive-detail",
                "jmops.integrations.splunk.test-indexes=test_index"
        })
@ActiveProfiles("local-live")
class LiveConfigurationStartupToleranceTest {
    private static final EvidenceQuery QUERY = new EvidenceQuery(
            Instant.parse("2026-01-15T10:30:00Z"), Instant.parse("2026-01-15T10:40:00Z"), 10, 10_000);

    @Autowired DiagnosticsController diagnosticsController;
    @Autowired GitLabConnector gitLabConnector;
    @Autowired JenkinsConnector jenkinsConnector;
    @Autowired SplunkConnector splunkConnector;

    @Test
    void liveProfileBootsAndDiagnosticsExposeOnlySanitizedConfigurationState() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(diagnosticsController.diagnostics(model)).isEqualTo("diagnostics");

        @SuppressWarnings("unchecked")
        Map<String, String> diagnostics = (Map<String, String>) model.get("diagnostics");
        assertThat(diagnostics.get("Jenkins connectivity"))
                .isEqualTo("UNAVAILABLE — Configured HTTPS endpoint is invalid");
        assertThat(diagnostics.get("GitLab connectivity"))
                .isEqualTo("UNAVAILABLE — Configured HTTPS endpoint is invalid");
        assertThat(diagnostics.get("Splunk"))
                .isEqualTo("UNCONFIGURED — Request timeout setting is invalid");
        assertThat(diagnostics.toString())
                .doesNotContain("jenkins.example.invalid", "invalid host.example.invalid", "private-marker",
                        "logs.example.invalid", "not-a-duration-with-sensitive-detail",
                        "startup-reader", "jenkins-startup-secret",
                        "gitlab-startup-secret", "splunk-startup-secret");
    }

    @Test
    void requestTimeBehaviorRemainsFailClosed() {
        assertThat(gitLabConnector.resolveRepository("catalog-service")).isEmpty();

        assertThatThrownBy(() -> jenkinsConnector.getLatestDeployment("catalog-service", Environment.TEST))
                .isInstanceOfSatisfying(JenkinsConnectorException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(JenkinsFailureKind.INVALID_CONFIGURATION);
                    assertThat(failure.getMessage())
                            .doesNotContain("jenkins.example.invalid", "startup-reader", "jenkins-startup-secret");
                });

        assertThat(splunkConnector.searchServiceEventsDetailed(
                "catalog-service", Environment.TEST, QUERY).outcome())
                .isEqualTo(SplunkSearchOutcome.UNCONFIGURED);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        ProcessRunner diagnosticsProcessRunner() {
            return request -> {
                Instant now = Instant.now();
                return new ProcessResult(true, 0, "available", "", false, false, "", now, now);
            };
        }
    }
}
