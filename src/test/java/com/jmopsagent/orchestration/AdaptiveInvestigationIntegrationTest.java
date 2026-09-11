package com.jmopsagent.orchestration;

import com.jmopsagent.claude.*;
import com.jmopsagent.connector.*;
import com.jmopsagent.conversation.FollowUpConversationService;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.domain.RootCauseCategory;
import com.jmopsagent.gitlab.GitLabConnector;
import com.jmopsagent.jenkins.JenkinsConnector;
import com.jmopsagent.kubernetes.KubernetesConnector;
import com.jmopsagent.persistence.InvestigationRepository;
import com.jmopsagent.splunk.*;
import com.jmopsagent.tas.TasConnector;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "local-mock"})
@Import(RetrospectiveInvestigationIntegrationTest.Configuration.class)
class AdaptiveInvestigationIntegrationTest {
    @Autowired InvestigationApplicationService application;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired InvestigationRepository repository;
    @Autowired InvestigationLimitsProperties limits;
    @Autowired FollowUpConversationService followUps;
    @MockitoBean TasConnector tas;
    @MockitoBean KubernetesConnector kubernetes;
    @MockitoBean SplunkConnector splunk;
    @MockitoBean JenkinsConnector jenkins;
    @MockitoBean GitLabConnector gitlab;
    @MockitoBean ClaudeCodeClient claude;
    private Instant failure;
    private final List<EvidenceQuery> searches = new ArrayList<>();

    @BeforeEach
    void setup() {
        repository.deleteAll();
        limits.setMaxSplunkSearches(5);
        limits.setMaxCodeFiles(8);
        failure = Instant.now().minus(Duration.ofDays(10)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        searches.clear();
        when(tas.getApplicationStatus(anyString(), any())).thenReturn(List.of(event("entry-api", Instant.now(), "Current healthy application")));
        when(tas.getRecentLogs(anyString(), any(), any())).thenReturn(List.of(event("entry-api", Instant.now(), "INFO statusCode=200; ErrorMessage: null")));
        doAnswer(invocation -> {
            EvidenceQuery query = invocation.getArgument(2);
            searches.add(query);
            if (!((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire()) return SplunkConnectorResult.limitReached();
            boolean containsFailure = !failure.isBefore(query.from()) && failure.isBefore(query.to());
            return result(containsFailure ? List.of(event(invocation.getArgument(0), failure,
                    "[ERROR] No catalog details found; X-TrackingId=DEMO-TRACE-123")) : List.of(), List.of());
        }).when(splunk).searchErrorsForServiceDetailed(anyString(), any(), any(), any());
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            return result(List.of(), List.of());
        }).when(splunk).searchByTrackingIdDetailed(anyString(), any(), any(), any());
        when(jenkins.getDeploymentHistory(anyString(), any(), any())).thenAnswer(invocation -> List.of(
                build(invocation.getArgument(0), "abc123", 10, failure.minusSeconds(3600)),
                build(invocation.getArgument(0), "def456", 11, failure.plusSeconds(3600))));
        when(gitlab.getCommits(anyString(), eq("abc123"), anyInt())).thenReturn(List.of(new CommitChange(
                "abc123", "DEMO projection change", "demo-author", failure.minusSeconds(3600), List.of(), "")));
        when(gitlab.getRepositoryTree(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of("src/Details.java"));
        when(gitlab.getFileContent(anyString(), anyString(), anyString(), anyInt())).thenReturn(Optional.of("class Details { /* projection */ }"));
        when(claude.analyze(any())).thenReturn(decision(ReasoningStatus.COMPLETE, List.of()));
        when(claude.followUp(any())).thenReturn(decision(ReasoningStatus.COMPLETE, List.of()));
    }

    @AfterEach
    void resetLimits() { limits.setMaxCodeFiles(8); limits.setMaxSplunkSearches(5); }

    @Test
    void ordinaryServiceEntryDiscoversRecoveredIncidentAndDependenciesUnderDefaultBudgets() {
        var investigation = application.createServiceInvestigation("entry-api", "TEST", "Returning errors; another team may have fixed it");
        assertThat(investigation.isRetrospective()).isFalse();
        orchestrator.investigate(investigation.getId());
        var completed = application.get(investigation.getId());
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(completed.isRetrospective()).isTrue();
        assertThat(completed.getIncidentStart()).isEqualTo(failure.minusSeconds(600));
        assertThat(completed.getSplunkSearchCount()).isEqualTo(5);
        assertThat(searches).hasSize(5);
        assertThat(searches.get(0).from()).isAfter(failure);
        assertThat(searches.get(1).from()).isAfter(failure);
        assertThat(searches.get(2).from()).isBefore(failure);
        verify(gitlab).getCommits("data-api", "abc123", 5);
        verify(jenkins, never()).getLatestDeployment(anyString(), any());
        verify(tas, never()).getEnvironmentMetadata(anyString(), any());
        var request = ArgumentCaptor.forClass(ClaudeReasoningRequest.class);
        verify(claude).analyze(request.capture());
        assertThat(request.getValue().evidence().toString()).contains("No catalog details", "abc123", "def456")
                .doesNotContain("Current healthy application", "INFO statusCode=200");
    }

    @Test
    void allEntryMethodsInferTheSameTimeAndTraceWithoutModes() {
        failure = Instant.parse("2025-09-09T10:30:00Z");
        String text = "entry-api returned errors in TEST on Sep 9, 2025, tracking id DEMO-TRACE-123; now fixed";
        var direct = application.createInvestigation(text, null, null, null, null);
        var service = application.createServiceInvestigation("entry-api", "TEST", text);
        var tracking = application.createTrackingInvestigation(text, "TEST");
        for (var investigation : List.of(direct, service, tracking)) {
            assertThat(investigation.getTrackingId()).isEqualTo("DEMO-TRACE-123");
            assertThat(investigation.getIncidentStart()).isEqualTo(Instant.parse("2025-09-09T00:00:00Z"));
            orchestrator.investigate(investigation.getId());
            assertThat(application.get(investigation.getId()).getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        }
        verify(splunk, times(3)).searchByTrackingIdDetailed(eq("DEMO-TRACE-123"), eq(Environment.TEST), any(), any());
        verifyNoInteractions(kubernetes);
    }

    @Test
    void codeRecommendationAutomaticallyCollectsSourceAndReasonsAgain() {
        failure = Instant.parse("2025-09-09T10:30:00Z");
        AtomicInteger calls = new AtomicInteger();
        when(claude.analyze(any())).thenAnswer(invocation -> calls.incrementAndGet() == 1
                ? decision(ReasoningStatus.CODE_INVESTIGATION_RECOMMENDED, List.of()) : decision(ReasoningStatus.COMPLETE, List.of()));
        var created = application.createInvestigation("entry-api failed in TEST on Sep 9, 2025", null, null, null, null);
        orchestrator.investigate(created.getId());
        verify(gitlab).getFileContent("entry-api", "abc123", "src/Details.java", 40_000);
        verify(claude, times(2)).analyze(any());
        assertThat(application.get(created.getId()).getSourceFileReadCount()).isEqualTo(1);
        assertThat(application.get(created.getId()).getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
    }

    @Test
    void ordinaryFollowUpRequestsSourceWithSavedScopeAndSharedFileBudget() {
        failure = Instant.parse("2025-09-09T10:30:00Z");
        var created = application.createInvestigation("entry-api failed in TEST on Sep 9, 2025", null, null, null, null);
        orchestrator.investigate(created.getId());
        String priorDiagnosis = application.get(created.getId()).getFinalDiagnosis();
        limits.setMaxCodeFiles(1);
        doReturn(decision(ReasoningStatus.NEEDS_MORE_EVIDENCE, List.of(
                new NextEvidenceRequest(EvidenceRequestType.RELEVANT_CODE_FILES, "data-api", "Need source to explain the cause"))))
                .when(claude).followUp(any());
        var followUp = followUps.ask(created.getId(), "Dig deeper, why did this happen?");
        verify(gitlab, times(1)).getFileContent("data-api", "abc123", "src/Details.java", 40_000);
        assertThat(followUp.getTargetedEvidenceItems()).isEqualTo(1);
        assertThat(followUp.getAnswer()).contains("Evidence gap");
        assertThat(application.get(created.getId()).getFinalDiagnosis()).isEqualTo(priorDiagnosis);
        followUps.ask(created.getId(), "What else explains it?");
        verify(gitlab, times(1)).getFileContent(anyString(), anyString(), anyString(), anyInt());
        assertThat(application.get(created.getId()).getSourceFileReadCount()).isEqualTo(1);
    }

    @Test
    void trackingIdFoundInServiceLogsTriggersTracingWithoutUserSelectingIt() {
        failure = Instant.now().minusSeconds(30);
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            return result(List.of(), List.of(new TraceEvent(failure, "DEMO-TRACE-123", "entry-api", "details", "FAILURE", 500,
                    null, "HTTP 500", null, Map.of())));
        }).when(splunk).searchByTrackingIdDetailed(anyString(), any(), any(), any());
        var created = application.createServiceInvestigation("entry-api", "TEST", "Returning errors");
        orchestrator.investigate(created.getId());
        verify(splunk).searchByTrackingIdDetailed(eq("DEMO-TRACE-123"), eq(Environment.TEST), any(), any());
        assertThat(application.get(created.getId()).getTrackingId()).isEqualTo("DEMO-TRACE-123");
        assertThat(application.get(created.getId()).getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
    }

    @Test
    void followUpDateIsAppliedBeforeTrafficRefreshAndFirstModelAnswer() {
        failure = Instant.now().minusSeconds(30);
        var created = application.createServiceInvestigation("entry-api", "TEST", "Returning errors");
        orchestrator.investigate(created.getId());
        failure = created.getStartedAt().atOffset(java.time.ZoneOffset.UTC).toLocalDate().minusDays(1)
                .atTime(12, 0).toInstant(java.time.ZoneOffset.UTC);
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            EvidenceQuery query = invocation.getArgument(2);
            assertThat(query.from()).isEqualTo(failure.minusSeconds(12 * 3600));
            assertThat(query.to()).isEqualTo(failure.plusSeconds(12 * 3600));
            return result(List.of(event("entry-api", failure, "[ERROR] yesterday traffic")), List.of());
        }).when(splunk).searchRecentBusinessCallsDetailed(anyString(), any(), any(), any());
        followUps.ask(created.getId(), "Show recent requests for yesterday's incident");
        verify(splunk).searchRecentBusinessCallsDetailed(eq("entry-api"), eq(Environment.TEST), any(), any());
        var prompt = ArgumentCaptor.forClass(ClaudeFollowUpRequest.class);
        verify(claude).followUp(prompt.capture());
        assertThat(prompt.getValue().evidence().toString()).contains("yesterday traffic").doesNotContain("Current healthy application");
        assertThat(prompt.getValue().sessionId()).isNull();
        assertThat(application.evidence(created.getId())).anyMatch(com.jmopsagent.domain.EvidenceItem::isSupersededByScopeChange);
    }

    @Test
    void followUpCanCorrectAnAutomaticallySelectedWindow() {
        var created = application.createServiceInvestigation("entry-api", "TEST", "Errors, perhaps already fixed");
        orchestrator.investigate(created.getId());
        failure = Instant.parse("2025-09-03T12:00:00Z");
        followUps.ask(created.getId(), "Actually the errors happened on Sep 3, 2025. Dig deeper.");
        assertThat(application.get(created.getId()).getIncidentStart()).isEqualTo(Instant.parse("2025-09-03T00:00:00Z"));
        var prompt = ArgumentCaptor.forClass(ClaudeFollowUpRequest.class);
        verify(claude).followUp(prompt.capture());
        assertThat(prompt.getValue().evidence().toString()).contains("2025-09-03T12:00:00Z");
        assertThat(prompt.getValue().evidence().stream().filter(item -> item.content().contains("Retrospective investigation:")))
                .hasSize(1);
    }

    @Test
    void codeRecommendationHonorsDependencySourceRequest() {
        limits.setMaxCodeFiles(1);
        failure = Instant.parse("2025-09-09T10:30:00Z");
        when(claude.analyze(any())).thenReturn(decision(ReasoningStatus.CODE_INVESTIGATION_RECOMMENDED, List.of(
                new NextEvidenceRequest(EvidenceRequestType.RELEVANT_CODE_FILES, "data-api", "Inspect failing projection"))),
                decision(ReasoningStatus.COMPLETE, List.of()));
        var created = application.createInvestigation("entry-api failed in TEST on Sep 9, 2025", null, null, null, null);
        orchestrator.investigate(created.getId());
        verify(gitlab).getFileContent("data-api", "abc123", "src/Details.java", 40_000);
        verify(gitlab, never()).getFileContent(eq("entry-api"), anyString(), anyString(), anyInt());
    }

    @Test
    void deploymentTimestampDoesNotBecomeTheFailureTime() {
        failure = Instant.now().minusSeconds(30);
        var created = application.createServiceInvestigation("entry-api", "TEST", "deployed 2025-09-01T09:00:00Z, now failing");
        orchestrator.investigate(created.getId());
        assertThat(searches.getFirst().from()).isAfter(Instant.now().minusSeconds(1900));
        verify(splunk, never()).searchAroundTimestampDetailed(anyString(), any(), eq(Instant.parse("2025-09-01T09:00:00Z")), any(), any());
        assertThat(application.get(created.getId()).isRetrospective()).isFalse();
    }

    @Test
    void retainedCfErrorCannotSuppressHistoricalDiscoveryOrSelectTodaysRevision() {
        when(tas.getRecentLogs(anyString(), any(), any())).thenReturn(List.of(new ConnectorEvidence("cf-recent", EvidenceSource.TAS,
                EvidenceType.APPLICATION_LOG, Instant.now(), "entry-api", Environment.TEST, "CF logs",
                failure + " [ERROR] Old application failure", null, Map.of(), .8)));
        var created = application.createServiceInvestigation("entry-api", "TEST", "Returning errors");
        orchestrator.investigate(created.getId());
        assertThat(application.get(created.getId()).isRetrospective()).isTrue();
        verify(jenkins, never()).getLatestDeployment(anyString(), any());
        verify(gitlab).getCommits("entry-api", "abc123", 5);
    }

    @Test
    void routerMetadataIsNeverReinterpretedAsApplicationTracking() {
        failure = Instant.now().minusSeconds(30);
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            return result(List.of(new ConnectorEvidence("router", EvidenceSource.SPLUNK, EvidenceType.APPLICATION_LOG,
                    failure, invocation.getArgument(0), Environment.TEST, "Router error", "[ERROR] statusCode=500 GET /12345678-1234-1234-1234-123456789012",
                    null, Map.of("sourceFormat", "http-access"), .8)), List.of());
        }).when(splunk).searchErrorsForServiceDetailed(anyString(), any(), any(), any());
        var created = application.createServiceInvestigation("entry-api", "TEST", "Returning errors");
        orchestrator.investigate(created.getId());
        verify(splunk, never()).searchByTrackingIdDetailed(anyString(), any(), any(), any());
        assertThat(application.get(created.getId()).getTrackingId()).isNull();
    }

    @Test
    void dateOnlyFollowUpReusesSavedTrackingIdAndLocalizesAnOlderFailure() {
        var created = application.createTrackingInvestigation("DEMO-TRACE-123", "TEST");
        orchestrator.investigate(created.getId());
        assertThat(application.get(created.getId()).getService()).isNull();
        failure = Instant.parse("2025-09-03T12:00:00Z");
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            EvidenceQuery query = invocation.getArgument(2);
            assertThat(query.from()).isEqualTo(Instant.parse("2025-09-03T00:00:00Z"));
            return result(List.of(), List.of(new TraceEvent(failure, "DEMO-TRACE-123", "entry-api", "details", "FAILURE", 500,
                    null, "HTTP 500", null, Map.of())));
        }).when(splunk).searchByTrackingIdDetailed(anyString(), any(), any(), any());
        followUps.ask(created.getId(), "It happened on Sep 3, 2025");
        assertThat(application.get(created.getId()).getService()).isEqualTo("entry-api");
        assertThat(application.get(created.getId()).getSplunkSearchCount()).isEqualTo(7);
        verify(gitlab).getCommits("entry-api", "abc123", 5);
        verify(claude).followUp(any());
    }

    @Test
    void latestQaTrackingLookupUsesSelectedServiceAndReturnsNewestObservedIdWithoutTriage() {
        Instant newest = Instant.now().minusSeconds(60);
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            return result(List.of(call("entry-api", newest.minusSeconds(90), "DEMO-OLD", "application-log"),
                    call("entry-api", newest.plusSeconds(30), "ROUTER-ID", "http-access"),
                    call("data-api", newest.plusSeconds(40), "OTHER-SERVICE", "application-log"),
                    call("entry-api", null, "UNKNOWN-TIME", "application-log"),
                    call("entry-api", newest, "DEMO-LATEST", "application-log")), List.of());
        }).when(splunk).searchLatestTrackingIdDetailed(anyString(), any(), any(), any());
        var created = application.createInvestigation("get me last tracking ID used for QA on this service", "entry-api", null, "TEST", null);
        assertThat(created.getTrackingId()).isNull();
        orchestrator.investigate(created.getId());
        var completed = application.get(created.getId());
        assertThat(completed.getTrackingId()).isEqualTo("DEMO-LATEST");
        assertThat(completed.getFinalDiagnosis()).contains("DEMO-LATEST", newest.toString(), "entry-api", "TEST", "do not identify who ran");
        verifyNoInteractions(tas, kubernetes, jenkins, gitlab, claude);
    }

    @Test
    void lookupThenWhyDidThatFailTracesSavedIdAndCollectsHistoricalCauseEvidence() {
        failure = Instant.now().minus(Duration.ofDays(1));
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            return result(List.of(call("entry-api", failure, "DEMO-LATEST", "application-log")), List.of());
        }).when(splunk).searchLatestTrackingIdDetailed(anyString(), any(), any(), any());
        var created = application.createInvestigation("latest tracking ID for this service", "entry-api", null, "TEST", null);
        orchestrator.investigate(created.getId());
        String original = application.get(created.getId()).getFinalDiagnosis();
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            assertThat((String) invocation.getArgument(0)).isEqualTo("DEMO-LATEST");
            EvidenceQuery query = invocation.getArgument(2);
            assertThat(query.from()).isBefore(failure);
            assertThat(query.to()).isAfter(failure);
            return result(List.of(), List.of(new TraceEvent(failure, "DEMO-LATEST", "entry-api", "details", "FAILURE", 500,
                    null, "HTTP 500", null, Map.of())));
        }).when(splunk).searchByTrackingIdDetailed(anyString(), any(), any(), any());
        followUps.ask(created.getId(), "why did that fail?");
        verify(gitlab).getCommits("entry-api", "abc123", 5);
        assertThat(application.get(created.getId()).isRetrospective()).isTrue();
        assertThat(application.get(created.getId()).getFinalDiagnosis()).isEqualTo(original);
        verify(claude).followUp(any());
    }

    @Test
    void latestLookupInHistoricalConversationUsesCurrentWindowWithoutChangingIncident() {
        failure = Instant.parse("2025-09-09T10:30:00Z");
        var created = application.createInvestigation("entry-api broken yesterday in TEST", null, null, null, null);
        orchestrator.investigate(created.getId());
        var priorWindow = application.get(created.getId()).incidentWindow();
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            EvidenceQuery query = invocation.getArgument(2);
            assertThat(query.to()).isAfter(priorWindow.end());
            return result(List.of(call("entry-api", Instant.now().minusSeconds(60), "DEMO-CURRENT", "application-log")), List.of());
        }).when(splunk).searchLatestTrackingIdDetailed(anyString(), any(), any(), any());
        var answer = followUps.ask(created.getId(), "get me last tracking ID used for QA on this service");
        assertThat(answer.getAnswer()).contains("DEMO-CURRENT");
        assertThat(application.get(created.getId()).incidentWindow()).isEqualTo(priorWindow);
        verify(claude, never()).followUp(any());
    }

    @Test
    void numericDateAndVagueFailureUseSameTriageFromSelectedContext() {
        failure = recentSeptemberDay(5);
        var created = application.createInvestigation("why was this broken on 9/5", "entry-api", null, "TEST", null);
        orchestrator.investigate(created.getId());
        assertThat(application.get(created.getId()).getIncidentStart()).isEqualTo(recentSeptemberDay(5));
        verify(gitlab).getCommits("data-api", "abc123", 5);
        verify(tas, never()).getApplicationStatus(anyString(), any());
    }

    @Test
    void invalidDateFollowUpAsksForClarificationBeforeAnySearch() {
        var created = application.createInvestigation("this service not working", "entry-api", null, "TEST", null);
        orchestrator.investigate(created.getId());
        clearInvocations(splunk, claude, tas, jenkins, gitlab);
        var response = followUps.ask(created.getId(), "why was this broken on 2/30");
        assertThat(response.getAnswer()).contains("invalid", "clarify");
        verifyNoInteractions(splunk, claude, tas, jenkins, gitlab);
    }

    @Test
    void latestLookupWithNoApplicationIdsDoesNotAdoptARouterId() {
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            return result(List.of(call("entry-api", Instant.now().minusSeconds(10), "ROUTER-ONLY", "http-access")), List.of());
        }).when(splunk).searchLatestTrackingIdDetailed(anyString(), any(), any(), any());
        var created = application.createInvestigation("last tracking ID for this service", "entry-api", null, "TEST", null);
        orchestrator.investigate(created.getId());
        assertThat(application.get(created.getId()).getTrackingId()).isNull();
        assertThat(application.get(created.getId()).getFinalDiagnosis()).contains("No qualifying application tracking ID");
        verifyNoInteractions(tas, jenkins, gitlab, claude);
    }

    @Test
    void newNumericDateFollowUpCanInvestigateAnIncidentAfterTheOriginalRun() {
        failure = Instant.now().minusSeconds(30);
        var created = com.jmopsagent.domain.Investigation.forServiceTriage("entry-api", com.jmopsagent.domain.DeploymentEnvironment.TEST, "Returning errors");
        created.setStartedAt(Instant.parse("2025-09-01T12:00:00Z"));
        repository.save(created);
        orchestrator.investigate(created.getId());
        assertThat(application.get(created.getId()).getStartedAt()).isEqualTo(Instant.parse("2025-09-01T12:00:00Z"));
        failure = recentSeptemberDay(5);
        followUps.ask(created.getId(), "why was this broken on 9/5");
        var completed = application.get(created.getId());
        assertThat(completed.getIncidentStart()).isEqualTo(recentSeptemberDay(5));
        assertThat(completed.getEvidenceScopeResolvedAt()).isAfter(completed.getIncidentEnd());
        verify(claude).followUp(any());
    }

    @Test
    void correctedIncidentAndLaterFailedLookupCannotReviveAnOldSelectedRequest() {
        failure = recentSeptemberDay(5);
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            return result(List.of(call("entry-api", failure, "DEMO-SEP5", "application-log")), List.of());
        }).when(splunk).searchLatestTrackingIdDetailed(anyString(), any(), any(), any());
        var created = application.createInvestigation("latest tracking ID on 9/5", "entry-api", null, "TEST", null);
        orchestrator.investigate(created.getId());
        assertThat(application.get(created.getId()).getActiveLookupEvidenceId()).isNotNull();
        followUps.ask(created.getId(), "Actually why was this broken on 9/6");
        assertThat(application.get(created.getId()).getActiveLookupEvidenceId()).isNull();
        clearInvocations(splunk);
        followUps.ask(created.getId(), "why did this fail?");
        verifyNoInteractions(splunk);
        assertThat(application.get(created.getId()).getIncidentStart()).isEqualTo(recentSeptemberDay(6));
        followUps.ask(created.getId(), "latest tracking ID on 9/5");
        assertThat(application.get(created.getId()).getActiveLookupEvidenceId()).isNotNull();
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            return result(List.of(), List.of());
        }).when(splunk).searchLatestTrackingIdDetailed(anyString(), any(), any(), any());
        followUps.ask(created.getId(), "get the latest tracking ID");
        assertThat(application.get(created.getId()).getTrackingId()).isNull();
        assertThat(application.get(created.getId()).getActiveLookupEvidenceId()).isNull();
        clearInvocations(splunk);
        followUps.ask(created.getId(), "why did that fail?");
        verifyNoInteractions(splunk);
    }

    @Test
    void followUpExplicitServiceAndEnvironmentOverrideSavedLookupContext() {
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            String service = invocation.getArgument(0);
            Environment environment = invocation.getArgument(1);
            var item = call(service, Instant.now().minusSeconds(10), "DEMO-" + environment, "application-log");
            return result(List.of(new ConnectorEvidence(item.externalId(), item.source(), item.type(), item.timestamp(), service,
                    environment, item.summary(), item.content(), item.sourceUrl(), item.metadata(), item.reliability())), List.of());
        }).when(splunk).searchLatestTrackingIdDetailed(anyString(), any(), any(), any());
        var created = application.createInvestigation("latest tracking ID", "entry-api", null, "TEST", null);
        orchestrator.investigate(created.getId());
        var answer = followUps.ask(created.getId(), "latest tracking ID for data-api in DEV");
        assertThat(answer.getAnswer()).contains("data-api", "DEV", "DEMO-DEV");
        assertThat(application.get(created.getId()).getService()).isEqualTo("data-api");
        assertThat(application.get(created.getId()).getEnvironment()).isEqualTo(com.jmopsagent.domain.DeploymentEnvironment.DEV);
        verify(splunk).searchLatestTrackingIdDetailed(eq("data-api"), eq(Environment.DEV), any(), any());
        clearInvocations(splunk);
        assertThat(followUps.ask(created.getId(), "latest tracking ID for data-api in PROD").getAnswer()).contains("DEV", "TEST");
        verifyNoInteractions(splunk);
    }

    @Test
    void delayedTrackingFollowUpUsesCollectionTimeToDetectARecoveredFailure() {
        failure = Instant.now().minusSeconds(30);
        var created = com.jmopsagent.domain.Investigation.forServiceTriage("entry-api", com.jmopsagent.domain.DeploymentEnvironment.TEST, "not working");
        created.setStartedAt(Instant.parse("2025-09-01T12:00:00Z"));
        repository.save(created);
        orchestrator.investigate(created.getId());
        failure = Instant.now().minusSeconds(7200);
        doAnswer(invocation -> {
            ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
            return result(List.of(), List.of(new TraceEvent(failure, "DEMO-RECOVERED", "entry-api", "details", "FAILURE", 500,
                    null, "HTTP 500", null, Map.of())));
        }).when(splunk).searchByTrackingIdDetailed(anyString(), any(), any(), any());
        clearInvocations(jenkins, tas);
        followUps.ask(created.getId(), "tracking ID DEMO-RECOVERED explains the failure");
        assertThat(application.get(created.getId()).isRetrospective()).isTrue();
        assertThat(application.get(created.getId()).getIncidentStart()).isAfter(Instant.now().minusSeconds(7900));
        verify(jenkins, never()).getLatestDeployment(anyString(), any());
        verify(tas, never()).getEnvironmentMetadata(anyString(), any());
    }

    @Test
    void dependencyQuestionsKeepTheHistoricalTargetAndContinueReasoning() {
        failure = recentSeptemberDay(5);
        var created = application.createInvestigation("why was this broken on 9/5", "entry-api", null, "TEST", null);
        orchestrator.investigate(created.getId());
        var window = application.get(created.getId()).incidentWindow();
        clearInvocations(splunk, tas, jenkins, gitlab);
        followUps.ask(created.getId(), "Could data-api have caused this failure?");
        followUps.ask(created.getId(), "How did entry-api call data-api?");
        assertThat(application.get(created.getId()).getService()).isEqualTo("entry-api");
        assertThat(application.get(created.getId()).incidentWindow()).isEqualTo(window);
        verify(claude, times(2)).followUp(any());
        verifyNoInteractions(splunk, tas, jenkins, gitlab);
    }

    @Test
    void monthsOldCalendarRequestFindsAnObservedFailureAndInvestigatesItsDependencies() {
        failure = Instant.parse("2025-03-12T10:30:00Z");
        when(claude.analyze(any())).thenReturn(decision(ReasoningStatus.CODE_INVESTIGATION_RECOMMENDED, List.of(
                new NextEvidenceRequest(EvidenceRequestType.RELEVANT_CODE_FILES, "data-api", "Investigate historical projection"))),
                decision(ReasoningStatus.COMPLETE, List.of()));
        var created = application.createInvestigation("why was entry-api broken in March 2025 in TEST?", null, null, null, null);
        assertThat(created.getIncidentStart()).isEqualTo(Instant.parse("2025-03-01T00:00:00Z"));
        orchestrator.investigate(created.getId());
        assertThat(searches.getFirst().from()).isEqualTo(Instant.parse("2025-03-01T00:00:00Z"));
        assertThat(searches.getFirst().to()).isEqualTo(Instant.parse("2025-04-01T00:00:00Z"));
        var completed = application.get(created.getId());
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(completed.getIncidentStart()).isEqualTo(failure.minusSeconds(600));
        assertThat(completed.getSplunkSearchCount()).isLessThanOrEqualTo(5);
        verify(jenkins).getDeploymentHistory("data-api", Environment.TEST, failure);
        verify(gitlab).getFileContent("data-api", "abc123", "src/Details.java", 40_000);
        verify(tas, never()).getApplicationStatus(anyString(), any());
        verify(gitlab).getRepositoryConfigurationAt("entry-api", Environment.TEST, failure.minusSeconds(600), 20_000);
        assertThat(application.evidence(created.getId())).anySatisfy(item -> assertThat(item.getSanitizedContent())
                .contains("Requested historical period 2025-03-01", "does not cover every incident"));
    }

    @Test
    void monthsOldLookupAndDiagnosisWorkAfterTheOriginalTurnExhaustsItsBudget() {
        var created = application.createServiceInvestigation("entry-api", "TEST", "Returning errors");
        orchestrator.investigate(created.getId());
        assertThat(application.get(created.getId()).getSplunkSearchCount()).isEqualTo(5);
        failure = Instant.parse("2025-02-20T11:00:00Z");
        doAnswer(invocation -> {
            if (!((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire()) return SplunkConnectorResult.limitReached();
            EvidenceQuery query = invocation.getArgument(2);
            assertThat(query.from()).isEqualTo(Instant.parse("2025-02-01T00:00:00Z"));
            assertThat(query.to()).isEqualTo(Instant.parse("2025-03-01T00:00:00Z"));
            return result(List.of(call("entry-api", failure, "DEMO-FEB", "application-log")), List.of());
        }).when(splunk).searchLatestTrackingIdDetailed(anyString(), any(), any(), any());
        var answer = followUps.ask(created.getId(), "get me last tracking ID used for QA in February 2025 on this service");
        assertThat(answer.getAnswer()).contains("DEMO-FEB", "2025-02-20T11:00:00Z");
        assertThat(application.get(created.getId()).getSplunkSearchCount()).isEqualTo(6);
        followUps.ask(created.getId(), "why did that fail?");
        assertThat(application.get(created.getId()).getIncidentStart()).isEqualTo(failure.minusSeconds(600));
        verify(splunk).searchByTrackingIdDetailed(eq("DEMO-FEB"), eq(Environment.TEST), any(), any());
        assertThat(application.get(created.getId()).getSplunkSearchCount()).isBetween(7, 11);
        verify(jenkins).getDeploymentHistory("data-api", Environment.TEST, failure);
    }

    @Test
    void conversationCanMoveFromOldMonthToCurrentConditionsAndBackUsingDefaultBudgets() {
        failure = Instant.parse("2025-03-12T10:30:00Z");
        var created = application.createInvestigation("why was entry-api broken in March 2025 in TEST?", null, null, null, null);
        orchestrator.investigate(created.getId());
        int used = application.get(created.getId()).getSplunkSearchCount();
        searches.clear();
        // The old failure still exists. An explicit now request must not rediscover it.
        when(claude.followUp(any())).thenReturn(decision(ReasoningStatus.NEEDS_MORE_EVIDENCE, List.of(
                new NextEvidenceRequest(EvidenceRequestType.EARLIER_FAILURES, "entry-api", "Look for an older failure"))));
        followUps.ask(created.getId(), "why is this failing now?");
        assertThat(application.get(created.getId()).isRetrospective()).isFalse();
        assertThat(searches).allSatisfy(query -> assertThat(query.from()).isAfter(Instant.now().minusSeconds(3600)));
        assertThat(application.get(created.getId()).getSplunkSearchCount() - used).isLessThanOrEqualTo(5);
        verify(tas).getApplicationStatus("entry-api", Environment.TEST);
        when(claude.followUp(any())).thenReturn(decision(ReasoningStatus.COMPLETE, List.of()));
        failure = Instant.parse("2024-02-29T10:30:00Z");
        searches.clear();
        followUps.ask(created.getId(), "Actually investigate why it was broken on February 29, 2024");
        assertThat(application.get(created.getId()).getIncidentStart()).isEqualTo(Instant.parse("2024-02-29T00:00:00Z"));
        assertThat(searches).allSatisfy(query -> assertThat(query.from()).isEqualTo(Instant.parse("2024-02-29T00:00:00Z")));
        var prompt = ArgumentCaptor.forClass(ClaudeFollowUpRequest.class);
        verify(claude, times(2)).followUp(prompt.capture());
        assertThat(prompt.getValue().evidence().toString()).contains("2024-02-29T10:30:00Z").doesNotContain("Current healthy application", "2025-03-12T10:30:00Z");
    }

    @Test
    void missingOldMonthEvidenceStaysInconclusiveAndNeverFallsBackToCurrentHealth() {
        var created = application.createInvestigation("why was entry-api broken in May 2024 in TEST?", null, null, null, null);
        orchestrator.investigate(created.getId());
        assertThat(application.get(created.getId()).getRootCauseCategory()).isEqualTo(RootCauseCategory.UNKNOWN);
        assertThat(application.get(created.getId()).getFinalDiagnosis()).contains("No timestamped failure");
        assertThat(searches).allSatisfy(query -> {
            assertThat(query.from()).isEqualTo(Instant.parse("2024-05-01T00:00:00Z"));
            assertThat(query.to()).isEqualTo(Instant.parse("2024-06-01T00:00:00Z"));
        });
        verify(tas, never()).getApplicationStatus(anyString(), any());
        clearInvocations(splunk, tas, jenkins, gitlab, claude);
        var answer = followUps.ask(created.getId(), "why did it fail from 2025-05-09T12:00Z to 2025-05-09T09:00Z?");
        assertThat(answer.getAnswer()).contains("clarify");
        verifyNoInteractions(splunk, tas, jenkins, gitlab, claude);
    }

    @Test
    void combinedMonthLookupAndDiagnosisKeepsTheSelectedRequestInsteadOfAnUnrelatedError() {
        var created = application.createServiceInvestigation("entry-api", "TEST", "Returning errors");
        orchestrator.investigate(created.getId());
        Instant selected = Instant.parse("2025-05-12T09:00:00Z");
        failure = Instant.parse("2025-05-20T09:00:00Z");
        doAnswer(invocation -> {
            if (!((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire()) return SplunkConnectorResult.limitReached();
            return result(List.of(call("entry-api", selected, "DEMO-SELECTED", "application-log")), List.of());
        }).when(splunk).searchLatestTrackingIdDetailed(anyString(), any(), any(), any());
        doAnswer(invocation -> {
            if (!((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire()) return SplunkConnectorResult.limitReached();
            assertThat((String) invocation.getArgument(0)).isEqualTo("DEMO-SELECTED");
            return result(List.of(), List.of(new TraceEvent(selected, "DEMO-SELECTED", "entry-api", "details", "FAILURE", 500,
                    null, "HTTP 500", null, Map.of())));
        }).when(splunk).searchByTrackingIdDetailed(anyString(), any(), any(), any());
        clearInvocations(splunk);
        followUps.ask(created.getId(), "get latest tracking ID in May 2025 and explain why it failed");
        assertThat(application.get(created.getId()).getTrackingId()).isEqualTo("DEMO-SELECTED");
        assertThat(application.get(created.getId()).getIncidentStart()).isEqualTo(selected.minusSeconds(600));
        verify(splunk).searchByTrackingIdDetailed(eq("DEMO-SELECTED"), eq(Environment.TEST), any(), any());
        assertThat(application.get(created.getId()).getActiveLookupEvidenceId()).isNull();
    }

    @Test
    void monthCorrectionPreservesExplicitTrackingIdentityAcrossAllEntryRoutes() {
        var entries = List.of(
                application.createInvestigation("entry-api in TEST, X-TrackingId: DEMO-EXPLICIT", null, null, null, null),
                application.createInvestigation("this service not working", "entry-api", "DEMO-EXPLICIT", "TEST", null),
                application.createTrackingInvestigation("DEMO-EXPLICIT", "TEST"));
        for (var created : entries) {
            orchestrator.investigate(created.getId());
            assertThat(application.get(created.getId()).isTrackingIdUserSupplied()).isTrue();
            Instant selected = Instant.parse("2025-05-12T09:00:00Z");
            failure = Instant.parse("2025-05-20T09:00:00Z");
            doAnswer(invocation -> {
                if (!((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire()) return SplunkConnectorResult.limitReached();
                EvidenceQuery query = invocation.getArgument(2);
                assertThat(query.from()).isEqualTo(Instant.parse("2025-05-01T00:00:00Z"));
                assertThat(query.to()).isEqualTo(Instant.parse("2025-06-01T00:00:00Z"));
                return result(List.of(), List.of(new TraceEvent(selected, "DEMO-EXPLICIT", "entry-api", "details", "FAILURE", 500,
                        null, "HTTP 500", null, Map.of())));
            }).when(splunk).searchByTrackingIdDetailed(anyString(), any(), any(), any());
            searches.clear();
            followUps.ask(created.getId(), "it happened in May 2025");
            assertThat(application.get(created.getId()).getTrackingId()).isEqualTo("DEMO-EXPLICIT");
            assertThat(application.get(created.getId()).getIncidentStart()).isEqualTo(selected.minusSeconds(600));
            assertThat(searches).allSatisfy(query -> assertThat(query.from()).isEqualTo(selected.minusSeconds(600)));
            // Restore the no-data initial tracing fixture for the next independent entry.
            doAnswer(invocation -> {
                ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire();
                return result(List.of(), List.of());
            }).when(splunk).searchByTrackingIdDetailed(anyString(), any(), any(), any());
        }
    }

    @Test
    void explicitlyConfirmingADiscoveredIdPreservesItAcrossLaterDateCorrections() {
        failure = Instant.now().minusSeconds(30);
        var created = application.createServiceInvestigation("entry-api", "TEST", "Returning errors");
        orchestrator.investigate(created.getId());
        assertThat(application.get(created.getId()).getTrackingId()).isEqualTo("DEMO-TRACE-123");
        assertThat(application.get(created.getId()).isTrackingIdUserSupplied()).isFalse();
        followUps.ask(created.getId(), "use tracking ID DEMO-TRACE-123");
        assertThat(application.get(created.getId()).isTrackingIdUserSupplied()).isTrue();
        clearInvocations(splunk);
        followUps.ask(created.getId(), "it happened in May 2025");
        assertThat(application.get(created.getId()).getTrackingId()).isEqualTo("DEMO-TRACE-123");
        verify(splunk).searchByTrackingIdDetailed(eq("DEMO-TRACE-123"), eq(Environment.TEST), any(), any());
    }

    private static Instant recentSeptemberDay(int day) {
        var today = Instant.now().atOffset(java.time.ZoneOffset.UTC).toLocalDate();
        var date = java.time.LocalDate.of(today.getYear(), 9, day);
        if (date.isAfter(today)) date = date.minusYears(1);
        return date.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
    }

    private static ConnectorEvidence call(String service, Instant at, String tracking, String sourceFormat) {
        return new ConnectorEvidence("call", EvidenceSource.SPLUNK, EvidenceType.RECENT_BUSINESS_CALLS, at, service, Environment.TEST,
                "Application call", "trackingId=" + tracking + " statusCode=200", null,
                Map.of("trackingId", tracking, "httpStatus", "200", "sourceFormat", sourceFormat), .9);
    }

    private static ConnectorEvidence event(String service, Instant time, String text) {
        return new ConnectorEvidence("demo-event", EvidenceSource.SPLUNK, EvidenceType.APPLICATION_LOG, time, service,
                Environment.TEST, "Service evidence", text, null, Map.of(), .9);
    }
    private static DeploymentInfo build(String service, String sha, long number, Instant time) {
        return new DeploymentInfo(service, Environment.TEST, "demo/deploy", number, "SUCCESS", time.minusSeconds(60), sha,
                null, List.of(), List.of(), List.of(), Map.of("completedAt", time.toString()));
    }
    private static SplunkConnectorResult result(List<ConnectorEvidence> items, List<TraceEvent> trace) {
        return new SplunkConnectorResult(new SplunkSearchResult(items, trace, items.size() + trace.size(), false),
                items.isEmpty() && trace.isEmpty() ? SplunkSearchOutcome.NO_DATA : SplunkSearchOutcome.SUCCESS);
    }
    private static ClaudeInvocationResult decision(ReasoningStatus status, List<NextEvidenceRequest> requests) {
        return new ClaudeInvocationResult("demo-session", new ReasoningDecision(status, "Evidence-based candidate cause", List.of(), requests,
                RootCauseCategory.UNKNOWN, List.of()), Instant.now(), Instant.now(), Duration.ZERO, 1, null, Map.of(), null, true);
    }
}
