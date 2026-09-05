package com.jmopsagent.orchestration;

import com.jmopsagent.claude.ClaudeCodeClient;
import com.jmopsagent.claude.ClaudeFollowUpRequest;
import com.jmopsagent.claude.ClaudeInvocationResult;
import com.jmopsagent.claude.ClaudeReasoningRequest;
import com.jmopsagent.claude.EvidenceRequestType;
import com.jmopsagent.claude.NextEvidenceRequest;
import com.jmopsagent.claude.ReasoningDecision;
import com.jmopsagent.claude.ReasoningStatus;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationEventType;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.domain.RootCauseCategory;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.SplunkSearchResult;
import com.jmopsagent.splunk.MockSplunkConnector;
import com.jmopsagent.splunk.SplunkConnector;
import com.jmopsagent.persistence.InvestigationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "jmops.limits.max-claude-iterations=2"
})
@ActiveProfiles({"test", "local-mock"})
@Import(InvestigationLimitAndRequestSafetyTest.Configuration.class)
class InvestigationLimitAndRequestSafetyTest {
    @Autowired InvestigationApplicationService applicationService;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired InvestigationRepository repository;
    @Autowired RequestingClaudeClient claude;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        claude.reset();
    }

    @Test
    void stopsAtTheConfiguredClaudeIterationLimitWithBestAvailableConclusion() {
        Investigation created = applicationService.createServiceInvestigation(
                "iteration-limit-service", "TEST", "Intermittent unexplained failure");

        orchestrator.investigate(created.getId());

        Investigation completed = applicationService.get(created.getId());
        assertThat(claude.calls()).isEqualTo(2);
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(completed.getFinalDiagnosis()).contains("Maximum Claude iterations reached");
        assertThat(applicationService.timeline(created.getId()))
                .anySatisfy(event -> {
                    assertThat(event.getType()).isEqualTo(InvestigationEventType.LIMIT_REACHED);
                    assertThat(event.getMessage()).contains("Maximum Claude iterations reached");
                });
    }

    @Test
    void rejectsClaudeEvidenceRequestOutsideTheResolvedServiceScope() {
        Investigation created = applicationService.createServiceInvestigation(
                "unauthorized-request-service", "TEST", "Intermittent unexplained failure");

        orchestrator.investigate(created.getId());

        assertThat(claude.calls()).isOne();
        assertThat(applicationService.get(created.getId()).getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(applicationService.timeline(created.getId()))
                .anySatisfy(event -> assertThat(event.getMessage())
                        .contains("Rejected an evidence request for a service outside the resolved tracking scope"));
    }

    @Test
    void mapsRecentActivityToTheRuntimeAppropriateBoundedLogOperation() {
        Investigation created = applicationService.createServiceInvestigation(
                "recent-activity-request-service", "TEST", "Show recent request activity");

        orchestrator.investigate(created.getId());

        assertThat(claude.calls()).isEqualTo(2);
        assertThat(claude.activityObserved()).isTrue();
        Investigation result = applicationService.get(created.getId());
        assertThat(result.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(result.getFinalDiagnosis()).contains("Recent activity evidence was collected");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        RequestingClaudeClient requestingClaudeClient() {
            return new RequestingClaudeClient();
        }

        @Bean
        @Primary
        SplunkConnector truncatingSplunkConnector() {
            return new MockSplunkConnector() {
                @Override
                public SplunkSearchResult searchRecentActivity(
                        String service, Environment environment, EvidenceQuery query) {
                    SplunkSearchResult result = super.searchRecentActivity(service, environment, query);
                    return new SplunkSearchResult(result.evidence(), result.traceEvents(),
                            result.rawResultCount(), true);
                }
            };
        }
    }

    static final class RequestingClaudeClient implements ClaudeCodeClient {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean activityObserved;

        @Override
        public ClaudeInvocationResult analyze(ClaudeReasoningRequest request) {
            calls.incrementAndGet();
            if (request.service().equals("recent-activity-request-service") && request.iteration() > 1) {
                activityObserved = request.evidence().stream()
                        .anyMatch(item -> item.summary().contains("Recent successful HTTP activity")
                                || item.summary().contains("Representative recent application logs"));
                ReasoningDecision complete = new ReasoningDecision(ReasoningStatus.COMPLETE,
                        "Recent activity evidence was collected through the approved runtime operation", List.of(),
                        List.of(), RootCauseCategory.UNKNOWN, List.of("Review the bounded activity sample."));
                Instant now = Instant.now();
                return new ClaudeInvocationResult("activity-session", complete, now, now.plusMillis(1),
                        Duration.ofMillis(1), 1, null, Map.of(), null, true);
            }
            String requestedService = request.service().equals("unauthorized-request-service")
                    ? "outside-service" : request.service();
            EvidenceRequestType requestType = request.service().equals("recent-activity-request-service")
                    ? EvidenceRequestType.RECENT_ACTIVITY : EvidenceRequestType.WORKLOAD_HEALTH;
            ReasoningDecision decision = new ReasoningDecision(ReasoningStatus.NEEDS_MORE_EVIDENCE,
                    "More bounded runtime health evidence is needed", List.of(),
                    List.of(new NextEvidenceRequest(requestType, requestedService,
                            "Confirm current health")), RootCauseCategory.UNKNOWN,
                    List.of("Continue only within configured investigation limits."));
            Instant now = Instant.now();
            return new ClaudeInvocationResult("limit-session", decision, now, now.plusMillis(1),
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
            activityObserved = false;
        }

        boolean activityObserved() {
            return activityObserved;
        }
    }
}
