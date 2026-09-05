package com.jmopsagent.orchestration;

import com.jmopsagent.claude.ClaudeCodeClient;
import com.jmopsagent.claude.ClaudeFollowUpRequest;
import com.jmopsagent.claude.ClaudeInvocationResult;
import com.jmopsagent.claude.ClaudeReasoningRequest;
import com.jmopsagent.claude.EvidenceRequestType;
import com.jmopsagent.claude.NextEvidenceRequest;
import com.jmopsagent.claude.ReasoningDecision;
import com.jmopsagent.claude.ReasoningStatus;
import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.SplunkSearchResult;
import com.jmopsagent.connector.TraceEvent;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.domain.RootCauseCategory;
import com.jmopsagent.kubernetes.MockKubernetesConnector;
import com.jmopsagent.persistence.InvestigationRepository;
import com.jmopsagent.registry.ServiceRegistry;
import com.jmopsagent.registry.YamlServiceRegistry;
import com.jmopsagent.splunk.MockSplunkConnector;
import com.jmopsagent.splunk.SplunkConnector;
import com.jmopsagent.tas.MockTasConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "jmops.limits.max-claude-iterations=2"
})
@ActiveProfiles({"test", "local-mock"})
@Import(RequestedEvidencePlatformRoutingIntegrationTest.Configuration.class)
class RequestedEvidencePlatformRoutingIntegrationTest {
    private static final String TAS_PRIMARY_TRACE = "TRACE-TAS-PRIMARY";
    private static final String EKS_PRIMARY_TRACE = "TRACE-EKS-PRIMARY";

    @Autowired InvestigationApplicationService applicationService;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired InvestigationRepository repository;
    @Autowired RoutingClaudeClient claude;
    @Autowired RecordingKubernetesConnector kubernetes;
    @Autowired RecordingTasConnector tas;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        claude.reset();
        kubernetes.reset();
        tas.reset();
    }

    @Test
    void routesFollowOnLogsToKubernetesWhenPrimaryFailureIsOnTas() {
        Investigation created = applicationService.createTrackingInvestigation(TAS_PRIMARY_TRACE, "TEST");

        orchestrator.investigate(created.getId());

        Investigation completed = applicationService.get(created.getId());
        assertThat(completed.getService()).isEqualTo("tas-primary");
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(claude.calls()).isEqualTo(2);
        assertThat(kubernetes.recentLogServices()).contains("eks-secondary");
        assertThat(tas.recentLogServices()).doesNotContain("eks-secondary");
    }

    @Test
    void routesFollowOnLogsToTasWhenPrimaryFailureIsOnKubernetes() {
        Investigation created = applicationService.createTrackingInvestigation(EKS_PRIMARY_TRACE, "TEST");

        orchestrator.investigate(created.getId());

        Investigation completed = applicationService.get(created.getId());
        assertThat(completed.getService()).isEqualTo("eks-primary");
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(claude.calls()).isEqualTo(2);
        assertThat(tas.recentLogServices()).contains("tas-secondary");
        assertThat(kubernetes.recentLogServices()).doesNotContain("tas-secondary");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        private static final String REGISTRY_YAML = """
                services:
                  - service: tas-primary
                    runtime:
                      platform:
                        TEST: TAS
                    gitlab:
                      repository: https://git.example.invalid/demo/tas-primary
                    jenkins:
                      controller: controller-a
                      job: demo/tas-primary/deploy
                    tas:
                      appPattern: tas-primary-{environment}
                  - service: tas-secondary
                    runtime:
                      platform:
                        TEST: TAS
                    gitlab:
                      repository: https://git.example.invalid/demo/tas-secondary
                    jenkins:
                      controller: controller-a
                      job: demo/tas-secondary/deploy
                    tas:
                      appPattern: tas-secondary-{environment}
                  - service: eks-primary
                    runtime:
                      platform:
                        TEST: EKS
                    gitlab:
                      repository: https://git.example.invalid/demo/eks-primary
                    jenkins:
                      controller: controller-b
                      job: demo/eks-primary/deploy
                    eks:
                      namespace: demo-test
                      deployment: eks-primary
                      service: eks-primary
                  - service: eks-secondary
                    runtime:
                      platform:
                        TEST: EKS
                    gitlab:
                      repository: https://git.example.invalid/demo/eks-secondary
                    jenkins:
                      controller: controller-b
                      job: demo/eks-secondary/deploy
                    eks:
                      namespace: demo-test
                      deployment: eks-secondary
                      service: eks-secondary
                """;

        @Bean
        @Primary
        ServiceRegistry platformRoutingRegistry() {
            return new YamlServiceRegistry(new ByteArrayResource(
                    REGISTRY_YAML.getBytes(StandardCharsets.UTF_8)));
        }

        @Bean
        @Primary
        RoutingClaudeClient routingClaudeClient() {
            return new RoutingClaudeClient();
        }

        @Bean
        @Primary
        SplunkConnector platformTraceSplunkConnector() {
            return new PlatformTraceSplunkConnector();
        }

        @Bean
        @Primary
        RecordingKubernetesConnector recordingKubernetesConnector() {
            return new RecordingKubernetesConnector();
        }

        @Bean
        @Primary
        RecordingTasConnector recordingTasConnector() {
            return new RecordingTasConnector();
        }
    }

    static final class PlatformTraceSplunkConnector extends MockSplunkConnector {
        @Override
        public SplunkSearchResult searchByTrackingId(
                String trackingId, Environment environment, EvidenceQuery query) {
            return switch (trackingId) {
                case TAS_PRIMARY_TRACE -> trace(trackingId, environment, "eks-secondary", "tas-primary");
                case EKS_PRIMARY_TRACE -> trace(trackingId, environment, "tas-secondary", "eks-primary");
                default -> super.searchByTrackingId(trackingId, environment, query);
            };
        }

        private static SplunkSearchResult trace(
                String trackingId, Environment environment, String secondaryService, String failingService) {
            Instant observedAt = Instant.parse("2026-01-15T10:40:00Z");
            List<TraceEvent> events = List.of(
                    event(observedAt, trackingId, "edge-router", "SUCCESS", 200, secondaryService,
                            "A supporting service call completed successfully"),
                    event(observedAt.plusMillis(200), trackingId, "edge-router", "FAILURE", 500,
                            failingService, "The primary downstream call returned HTTP 500"),
                    event(observedAt.plusMillis(300), trackingId, failingService, "FAILURE", 500,
                            null, "The localized service reported an internal failure"));
            return new SplunkSearchResult(List.of(), events, events.size(), false);
        }

        private static TraceEvent event(Instant timestamp, String trackingId, String service, String outcome,
                int status, String downstream, String summary) {
            return new TraceEvent(timestamp, trackingId, service, "request", outcome, status, downstream,
                    summary, URI.create("https://observability.example.invalid/search"),
                    Map.of("environment", "TEST"));
        }
    }

    static final class RecordingKubernetesConnector extends MockKubernetesConnector {
        private final List<String> recentLogServices = new ArrayList<>();

        @Override
        public synchronized List<ConnectorEvidence> getRecentPodLogs(
                String service, Environment environment, EvidenceQuery query) {
            recentLogServices.add(service);
            return super.getRecentPodLogs(service, environment, query);
        }

        synchronized List<String> recentLogServices() {
            return List.copyOf(recentLogServices);
        }

        synchronized void reset() {
            recentLogServices.clear();
        }
    }

    static final class RecordingTasConnector extends MockTasConnector {
        private final List<String> recentLogServices = new ArrayList<>();

        @Override
        public synchronized List<ConnectorEvidence> getRecentLogs(
                String service, Environment environment, EvidenceQuery query) {
            recentLogServices.add(service);
            return super.getRecentLogs(service, environment, query);
        }

        synchronized List<String> recentLogServices() {
            return List.copyOf(recentLogServices);
        }

        synchronized void reset() {
            recentLogServices.clear();
        }
    }

    static final class RoutingClaudeClient implements ClaudeCodeClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ClaudeInvocationResult analyze(ClaudeReasoningRequest request) {
            int call = calls.incrementAndGet();
            ReasoningDecision decision;
            if (call == 1) {
                String requestedService = request.service().equals("tas-primary")
                        ? "eks-secondary" : "tas-secondary";
                decision = new ReasoningDecision(ReasoningStatus.NEEDS_MORE_EVIDENCE,
                        "A bounded log sample from the related service is needed", List.of(),
                        List.of(new NextEvidenceRequest(EvidenceRequestType.RECENT_LOGS, requestedService,
                                "Correlate the related service at the observed failure time")),
                        RootCauseCategory.UNKNOWN, List.of());
            } else {
                decision = new ReasoningDecision(ReasoningStatus.COMPLETE,
                        "The approved cross-platform evidence request completed", List.of(), List.of(),
                        RootCauseCategory.UNKNOWN, List.of("Review the bounded evidence sample."));
            }
            Instant now = Instant.now();
            return new ClaudeInvocationResult("platform-routing-session", decision, now, now.plusMillis(1),
                    Duration.ofMillis(1), 1, null, Map.of(), null, true);
        }

        @Override
        public ClaudeInvocationResult followUp(ClaudeFollowUpRequest request) {
            throw new UnsupportedOperationException("not used by this test");
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }
    }
}
