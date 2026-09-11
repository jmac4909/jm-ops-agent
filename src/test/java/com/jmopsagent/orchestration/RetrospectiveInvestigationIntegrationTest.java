package com.jmopsagent.orchestration;

import com.jmopsagent.claude.*;
import com.jmopsagent.connector.*;
import com.jmopsagent.conversation.FollowUpConversationService;
import com.jmopsagent.domain.IncidentWindow;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.domain.RootCauseCategory;
import com.jmopsagent.gitlab.GitLabConnector;
import com.jmopsagent.jenkins.JenkinsConnector;
import com.jmopsagent.kubernetes.KubernetesConnector;
import com.jmopsagent.persistence.InvestigationRepository;
import com.jmopsagent.registry.ServiceRegistry;
import com.jmopsagent.registry.YamlServiceRegistry;
import com.jmopsagent.splunk.*;
import com.jmopsagent.tas.TasConnector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "local-mock"})
@Import(RetrospectiveInvestigationIntegrationTest.Configuration.class)
class RetrospectiveInvestigationIntegrationTest {
    static final IncidentWindow WINDOW = IncidentWindow.parse("2025-09-09T09:00", "2025-09-09T12:00");
    static final Instant FAILURE = Instant.parse("2025-09-09T10:30:00Z");
    @Autowired InvestigationApplicationService application;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired InvestigationStateService state;
    @Autowired InvestigationRepository repository;
    @Autowired FollowUpConversationService followUps;
    @MockitoBean TasConnector tas;
    @MockitoBean KubernetesConnector kubernetes;
    @MockitoBean SplunkConnector splunk;
    @MockitoBean JenkinsConnector jenkins;
    @MockitoBean GitLabConnector gitlab;
    @MockitoBean ClaudeCodeClient claude;

    @BeforeEach
    void setup() {
        repository.deleteAll();
        when(tas.getEvents(anyString(), any(), any())).thenAnswer(invocation -> List.of(new ConnectorEvidence(
                "cf-retained-events", EvidenceSource.TAS, EvidenceType.DEPLOYMENT, Instant.now(), invocation.getArgument(0),
                Environment.TEST, "CF events", "2025-09-09T10:00:00Z audit.app.restage demo-platform-account", null, Map.of(), 0.8)));
        doAnswer(invocation -> {
            assertWindow(invocation.getArgument(2));
            if (!((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire()) return SplunkConnectorResult.limitReached();
            return logs(invocation.getArgument(0));
        }).when(splunk).searchErrorsForServiceDetailed(anyString(), any(), any(), any());
        when(splunk.searchRecentBusinessCallsDetailed(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            assertWindow(invocation.getArgument(2));
            if (!((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire()) return SplunkConnectorResult.limitReached();
            return logs(invocation.getArgument(0));
        });
        when(jenkins.getDeploymentHistory(anyString(), any(), any())).thenAnswer(invocation -> {
            String service = invocation.getArgument(0);
            return List.of(build(service, "fed987", 30, "2026-01-01T12:00:00Z"),
                    build(service, "def456", 20, "2025-09-09T13:00:00Z"),
                    build(service, "abc123", 10, "2025-09-09T10:00:00Z"),
                    build(service, "aaa111", 5, "2025-09-09T08:00:00Z"));
        });
        when(gitlab.getCommits(anyString(), eq("abc123"), anyInt())).thenReturn(List.of(change("abc123", "DEMO-42 explicit projections")));
        when(gitlab.compareRevisions(anyString(), eq("abc123"), eq("def456"), anyInt()))
                .thenReturn(List.of(change("def456", "DEMO-43 restore address columns")));
        when(gitlab.searchCommits(anyString(), anyList(), any())).thenAnswer(invocation -> {
            EvidenceQuery query = invocation.getArgument(2);
            if (query.from().equals(WINDOW.end())) {
                assertThat(query.to()).isEqualTo(Instant.parse("2025-09-12T12:00:00Z"));
            } else {
                assertThat(query.to()).isEqualTo(WINDOW.end());
            }
            return List.of(query.from().equals(WINDOW.end()) ? change("def456", "Possible fix: restore address columns")
                    : change("abc123", "Possible trigger: narrow projections"));
        });
        when(gitlab.getRepositoryConfigurationAt(anyString(), any(), eq(WINDOW.start()), anyInt()))
                .thenAnswer(invocation -> List.of(event(invocation.getArgument(0), WINDOW.start().minusSeconds(60),
                        EvidenceType.CONFIGURATION, "Historical config: table=demo-data-test; runtime values unverified")));
        when(claude.analyze(any())).thenReturn(decision(ReasoningStatus.COMPLETE, List.of()));
        when(claude.followUp(any())).thenReturn(decision(ReasoningStatus.COMPLETE, List.of()));
    }

    @Test
    void startsAfterAnotherTeamFixedItWithoutAnyPriorAgentRunAndSeparatesFailureAndRecovery() {
        assertThat(repository.count()).isZero();
        var created = application.createServiceInvestigation("entry-api", "TEST", "Already fixed by another team", WINDOW);
        assertThat(application.get(created.getId()).incidentWindow()).isEqualTo(WINDOW);
        assertThat(application.evidence(created.getId())).isEmpty();
        orchestrator.investigate(created.getId());
        assertThat(application.get(created.getId()).getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        var request = ArgumentCaptor.forClass(ClaudeReasoningRequest.class);
        verify(claude).analyze(request.capture());
        assertThat(request.getValue().investigationType()).isEqualTo("RETROSPECTIVE_SERVICE_TRIAGE");
        assertThat(request.getValue().evidence().toString())
                .contains("[ERROR] findDetails ErrorMessage: null", "abc123", "def456", "Before incident", "During incident",
                        "After incident", "restore address columns", "Retrospective investigation scope",
                        "Retained CF event listing (coverage unverified)", "collection timestamp as deployment time")
                .doesNotContain("fed987", "CURRENT_FIXED_HEALTHY", "OUTSIDE_INCIDENT");
        for (String service : List.of("entry-api", "search-api", "data-api")) {
            verify(gitlab).getCommits(service, "abc123", 5);
            verify(gitlab).compareRevisions(service, "abc123", "def456", 30_000);
            verify(gitlab).getRepositoryConfigurationAt(service, Environment.TEST, WINDOW.start(), 20_000);
        }
        verify(jenkins, never()).getLatestDeployment(anyString(), any());
        verify(tas, never()).getApplicationStatus(anyString(), any());
        verify(tas, never()).getRecentLogs(anyString(), any(), any());
        verify(tas, never()).getEnvironmentMetadata(anyString(), any());
        verify(gitlab, never()).getRepositoryConfiguration(anyString(), any(), anyInt());
        verifyNoInteractions(kubernetes);
    }

    @Test
    void reloadsWindowForModelRequestsFollowUpAndCodeWithoutUsingFixedSha() {
        AtomicInteger calls = new AtomicInteger();
        when(claude.analyze(any())).thenAnswer(invocation -> calls.incrementAndGet() == 1
                ? decision(ReasoningStatus.NEEDS_MORE_EVIDENCE, List.of(
                        new NextEvidenceRequest(EvidenceRequestType.RECENT_BUSINESS_CALLS, "entry-api", "Historical traffic"),
                        new NextEvidenceRequest(EvidenceRequestType.LATEST_DEPLOYMENT, "entry-api", "Deployment"),
                        new NextEvidenceRequest(EvidenceRequestType.DEPENDENCY_EVIDENCE, "data-api", "Probe")))
                : decision(ReasoningStatus.COMPLETE, List.of()));
        var created = application.createServiceInvestigation("entry-api", "TEST", "Already recovered", WINDOW);
        orchestrator.investigate(created.getId());
        var followUp = followUps.ask(created.getId(), "Show requests during the incident");
        assertThat(followUp.getTargetedEvidenceItems()).isEqualTo(1);
        var followRequest = ArgumentCaptor.forClass(ClaudeFollowUpRequest.class);
        verify(claude).followUp(followRequest.capture());
        assertThat(followRequest.getValue().targetedEvidenceContext()).contains("2025-09-09T09:00:00Z", "no present-day");
        when(gitlab.getRepositoryTree("entry-api", "abc123", "", 250)).thenReturn(List.of("src/Details.java"));
        when(gitlab.getFileContent("entry-api", "abc123", "src/Details.java", 40_000))
                .thenReturn(java.util.Optional.of("class Details { /* historical projection */ }"));
        state.beginCodeInvestigation(created.getId());
        orchestrator.investigateCode(created.getId());
        verify(gitlab).getFileContent("entry-api", "abc123", "src/Details.java", 40_000);
        verify(gitlab, never()).getRepositoryTree(anyString(), eq("def456"), anyString(), anyInt());
        verify(jenkins, never()).getLatestDeployment(anyString(), any());
        assertThat(application.evidence(created.getId())).anySatisfy(item ->
                assertThat(item.getSummary()).contains("Historical Jenkins candidate abc123"));
    }

    @Test
    void absentRetentionCannotBecomeAConfidentConfigDiagnosisOrUseCurrentCode() {
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            return empty();
        }).when(splunk).searchErrorsForServiceDetailed(anyString(), any(), any(), any());
        when(jenkins.getDeploymentHistory(anyString(), any(), any())).thenReturn(List.of());
        doReturn(List.of(change("def456", "parameter not found config mismatch database connection timeout"))).when(gitlab).searchCommits(anyString(), anyList(), any());
        when(claude.analyze(any())).thenReturn(new ClaudeInvocationResult(null, null, Instant.now(), Instant.now(),
                Duration.ZERO, 0, null, Map.of(), "Unavailable", false));
        var created = application.createServiceInvestigation("entry-api", "TEST", "Other team fixed it", WINDOW);
        orchestrator.investigate(created.getId());
        var completed = application.get(created.getId());
        assertThat(completed.getRootCauseCategory()).isEqualTo(RootCauseCategory.UNKNOWN);
        assertThat(completed.getFinalDiagnosis()).contains("Historical root cause remains inconclusive", "does not disprove");
        state.beginCodeInvestigation(created.getId());
        orchestrator.investigateCode(created.getId());
        verify(gitlab, never()).getRepositoryTree(anyString(), anyString(), anyString(), anyInt());
        verify(jenkins, never()).getLatestDeployment(anyString(), any());
    }

    @Test
    void successfulNoErrorLogsCannotSupportAConfidentHistoricalDiagnosis() {
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            return new SplunkConnectorResult(new SplunkSearchResult(List.of(event(invocation.getArgument(0), FAILURE,
                    EvidenceType.APPLICATION_LOG, "INFO HTTP 200; no errors; failedCount=0; exceptionCount=0; ErrorMessage: null")),
                    List.of(), 1, false), SplunkSearchOutcome.SUCCESS);
        }).when(splunk).searchErrorsForServiceDetailed(anyString(), any(), any(), any());
        when(claude.analyze(any())).thenReturn(new ClaudeInvocationResult("demo", new ReasoningDecision(
                ReasoningStatus.COMPLETE, "Unsupported confident config diagnosis", List.of(), List.of(),
                RootCauseCategory.CONFIG, List.of()), Instant.now(), Instant.now(), Duration.ZERO, 1, null, Map.of(), null, true));
        var created = application.createServiceInvestigation("entry-api", "TEST", "Already fixed", WINDOW);
        orchestrator.investigate(created.getId());
        assertThat(application.get(created.getId()).getRootCauseCategory()).isEqualTo(RootCauseCategory.UNKNOWN);
        assertThat(application.get(created.getId()).getFinalDiagnosis()).contains("did not establish a failure");
    }

    @Test
    void busyIncidentRetainsGroupedFailuresAndReservesCapacityForDependenciesAndHistory() {
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            EvidenceQuery query = invocation.getArgument(2);
            assertThat(query.maxResults()).isLessThanOrEqualTo(5);
            String service = invocation.getArgument(0);
            var items = java.util.stream.IntStream.range(0, 60).mapToObj(index -> new ConnectorEvidence(
                    "group-" + index, EvidenceSource.SPLUNK, EvidenceType.ERROR_PATTERN, FAILURE.plusSeconds(index),
                    service, Environment.TEST, "Repeated error pattern " + index, "No catalog details found " + index,
                    null, Map.of("frequency", "4", "httpStatusClass", "5xx"), 0.9)).toList();
            return new SplunkConnectorResult(new SplunkSearchResult(items, List.of(), 60, true), SplunkSearchOutcome.SUCCESS);
        }).when(splunk).searchErrorsForServiceDetailed(anyString(), any(), any(), any());
        var created = application.createServiceInvestigation("entry-api", "TEST", "Many failures, now fixed", WINDOW);
        orchestrator.investigate(created.getId());
        verify(gitlab).getCommits("entry-api", "abc123", 5);
        verify(gitlab).getCommits("data-api", "abc123", 5);
        verify(claude).analyze(any());
        assertThat(application.evidence(created.getId()).size()).isLessThan(50);
        assertThat(application.get(created.getId()).getFinalDiagnosis()).contains("projection change is a candidate");
    }

    @Test
    void dependencyFailureDoesNotReanchorRootAndSavedCandidatesSurviveJenkinsRetention() {
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            String service = invocation.getArgument(0);
            return new SplunkConnectorResult(new SplunkSearchResult(List.of(event(service,
                    service.equals("data-api") ? WINDOW.start() : FAILURE,
                    EvidenceType.APPLICATION_LOG, "[ERROR] Failure in " + service)), List.of(), 1, false), SplunkSearchOutcome.SUCCESS);
        }).when(splunk).searchErrorsForServiceDetailed(anyString(), any(), any(), any());
        var created = application.createServiceInvestigation("entry-api", "TEST", "Already recovered", WINDOW);
        orchestrator.investigate(created.getId());
        verify(gitlab).getCommits("entry-api", "abc123", 5);
        verify(gitlab).getCommits("data-api", "aaa111", 5);
        doReturn(List.of()).when(jenkins).getDeploymentHistory(anyString(), any(), any());
        when(gitlab.getRepositoryTree(anyString(), anyString(), eq(""), eq(250))).thenReturn(List.of("src/Details.java"));
        when(gitlab.getFileContent(anyString(), anyString(), eq("src/Details.java"), eq(40_000)))
                .thenReturn(java.util.Optional.of("class Details {}"));
        AtomicInteger codeCalls = new AtomicInteger();
        when(claude.analyze(any())).thenAnswer(invocation -> codeCalls.incrementAndGet() == 1
                ? decision(ReasoningStatus.NEEDS_MORE_EVIDENCE, List.of(
                        new NextEvidenceRequest(EvidenceRequestType.RELEVANT_CODE_FILES, "data-api", "Historical dependency source")))
                : decision(ReasoningStatus.COMPLETE, List.of()));
        state.beginCodeInvestigation(created.getId());
        orchestrator.investigateCode(created.getId());
        verify(gitlab).getFileContent("entry-api", "abc123", "src/Details.java", 40_000);
        verify(gitlab).getFileContent("data-api", "aaa111", "src/Details.java", 40_000);
        verify(jenkins, times(1)).getDeploymentHistory(eq("entry-api"), eq(Environment.TEST), any());
        verify(jenkins, times(1)).getDeploymentHistory(eq("data-api"), eq(Environment.TEST), any());
        assertThat(application.get(created.getId()).getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
    }

    @Test
    void historicalTrackingUsesExactWindowAndNeverExpandsToNowWhenNoData() {
        when(splunk.searchByTrackingIdDetailed(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            assertWindow(invocation.getArgument(2));
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            return empty();
        });
        var created = application.createTrackingInvestigation("DEMO-OLD-TRACE", "TEST", WINDOW);
        orchestrator.investigate(created.getId());
        verify(splunk, times(1)).searchByTrackingIdDetailed(anyString(), any(), any(), any());
        assertThat(application.get(created.getId()).getFinalDiagnosis()).contains("supplied incident window");
        verifyNoInteractions(tas, jenkins);
    }

    private static void assertWindow(EvidenceQuery query) {
        assertThat(query.from()).isEqualTo(WINDOW.start());
        assertThat(query.to()).isEqualTo(WINDOW.end());
    }

    private static SplunkConnectorResult logs(String service) {
        return new SplunkConnectorResult(new SplunkSearchResult(List.of(
                event(service, FAILURE, EvidenceType.APPLICATION_LOG, "[ERROR] findDetails ErrorMessage: null; downstream returned HTTP 500"),
                event(service, WINDOW.end(), EvidenceType.APPLICATION_LOG, "OUTSIDE_INCIDENT boundary error"),
                event(service, Instant.now(), EvidenceType.WORKLOAD_HEALTH, "CURRENT_FIXED_HEALTHY")), List.of(), 3, false), SplunkSearchOutcome.SUCCESS);
    }

    private static ConnectorEvidence event(String service, Instant time, EvidenceType type, String content) {
        return new ConnectorEvidence("demo-" + service + time, EvidenceSource.SPLUNK, type, time, service,
                Environment.TEST, "Historical evidence for " + service, content, null, Map.of(), 0.8);
    }

    private static SplunkConnectorResult empty() {
        return new SplunkConnectorResult(new SplunkSearchResult(List.of(), List.of(), 0, false), SplunkSearchOutcome.NO_DATA);
    }

    private static DeploymentInfo build(String service, String sha, long number, String completedAt) {
        return new DeploymentInfo(service, Environment.TEST, "demo/deploy", number, "SUCCESS",
                Instant.parse(completedAt).minusSeconds(120), sha, null, List.of(), List.of(), List.of(),
                Map.of("completedAt", completedAt));
    }

    private static CommitChange change(String sha, String title) {
        return new CommitChange(sha, title, "demo-author", WINDOW.start().minusSeconds(60), List.of("src/Details.java"), "demo diff");
    }

    private static ClaudeInvocationResult decision(ReasoningStatus status, List<NextEvidenceRequest> requests) {
        Instant now = Instant.now();
        return new ClaudeInvocationResult("demo-session", new ReasoningDecision(status, "Historical projection change is a candidate",
                List.of(), requests, RootCauseCategory.UNKNOWN, List.of()), now, now, Duration.ZERO, 1, null, Map.of(), null, true);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary
        ServiceRegistry historicalRegistry() {
            String common = """
                        runtime.platform: TAS
                        gitlab.repository: https://git.example.invalid/demo/service
                        jenkins.controller: demo
                        jenkins.job: demo/deploy
                    """;
            return new YamlServiceRegistry(new ByteArrayResource(("services:\n"
                    + "  - service: entry-api\n    dependencies: [search-api]\n" + common
                    + "  - service: search-api\n    dependencies: [data-api]\n" + common
                    + "  - service: data-api\n" + common).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
