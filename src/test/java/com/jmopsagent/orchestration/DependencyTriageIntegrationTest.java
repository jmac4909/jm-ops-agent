package com.jmopsagent.orchestration;

import com.jmopsagent.claude.*;
import com.jmopsagent.connector.*;
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
@Import(DependencyTriageIntegrationTest.Configuration.class)
class DependencyTriageIntegrationTest {
    @Autowired InvestigationApplicationService application;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired InvestigationRepository repository;
    @Autowired InvestigationLimitsProperties limits;
    @Autowired ProbeHttpFixture probeFixture;
    @MockitoBean TasConnector tas;
    @MockitoBean KubernetesConnector kubernetes;
    @MockitoBean SplunkConnector splunk;
    @MockitoBean JenkinsConnector jenkins;
    @MockitoBean GitLabConnector gitlab;
    @MockitoBean ClaudeCodeClient claude;

    @BeforeEach
    void setup() {
        repository.deleteAll();
        probeFixture.requests.set(0);
        limits.setMaxDependencyServices(3);
        limits.setMaxSplunkSearches(5);
        when(tas.getRecentLogs(anyString(), eq(Environment.TEST), any())).thenAnswer(invocation -> {
            String service = invocation.getArgument(0);
            return List.of(evidence(service, EvidenceType.APPLICATION_LOG,
                    "[ERROR] findDetails ErrorMessage: null\n[DataClient#get] GET http://data.example.invalid/detail"));
        });
        when(tas.getEvents(anyString(), eq(Environment.TEST), any())).thenAnswer(invocation ->
                List.of(evidence(invocation.getArgument(0), EvidenceType.DEPLOYMENT,
                        "2026-09-09T10:00:00Z audit.app.restage platform-bot")));
        when(splunk.searchErrorsForServiceDetailed(anyString(), any(), any(), any())).thenAnswer(invocation -> ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire() ? empty()
                : new SplunkConnectorResult(new SplunkSearchResult(List.of(), List.of(), 0, false), SplunkSearchOutcome.LIMIT_REACHED));
        when(splunk.searchServiceEventsDetailed(anyString(), any(), any(), any())).thenAnswer(invocation -> ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire() ? empty()
                : new SplunkConnectorResult(new SplunkSearchResult(List.of(), List.of(), 0, false), SplunkSearchOutcome.LIMIT_REACHED));
        when(splunk.searchRecentBusinessCallsDetailed(anyString(), any(), any(), any())).thenAnswer(invocation -> ((SplunkSearchPermit) invocation.getArgument(3)).tryAcquire() ? empty()
                : new SplunkConnectorResult(new SplunkSearchResult(List.of(), List.of(), 0, false), SplunkSearchOutcome.LIMIT_REACHED));
        when(gitlab.searchCommits(anyString(), anyList(), any())).thenReturn(List.of(
                new CommitChange("abc123", "DEMO-42 explicit column projections", "demo-author",
                        Instant.parse("2026-09-01T10:00:00Z"), List.of(), "")));
        when(gitlab.getRepositoryConfiguration(anyString(), any(), anyInt())).thenAnswer(invocation ->
                List.of(evidence(invocation.getArgument(0), EvidenceType.CONFIGURATION, "database.table=demo-data-test")));
        when(claude.analyze(any())).thenAnswer(invocation -> decision(ReasoningStatus.COMPLETE, List.of()));
    }

    @Test
    void realProbeAdapterFlowsThroughPersistenceAndReasoningWithoutReprobingOrGenericAdvisory() {
        when(gitlab.getRepositoryConfiguration(anyString(), any(), anyInt())).thenReturn(List.of());
        when(claude.analyze(any())).thenAnswer(invocation -> decision(ReasoningStatus.NEEDS_MORE_EVIDENCE, List.of(
                new NextEvidenceRequest(EvidenceRequestType.DEPENDENCY_EVIDENCE, "data-api", "Compare dependency responses"))));
        var created = application.createServiceInvestigation("entry-api", "TEST", "Returning errors");
        orchestrator.investigate(created.getId());
        assertThat(application.get(created.getId()).getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(probeFixture.requests).hasValue(2);
        var evidence = application.evidence(created.getId());
        assertThat(evidence).filteredOn(item -> item.getSummary().startsWith("Registered GET probe"))
                .hasSize(2).allSatisfy(item -> assertThat(item.getService()).isEqualTo("data-api"));
        assertThat(evidence).noneSatisfy(item -> assertThat(item.getSummary()).isIn(
                "Direct dependency adapter is not configured", "No dependency failure evidence"));
        var requests = ArgumentCaptor.forClass(ClaudeReasoningRequest.class);
        verify(claude).analyze(requests.capture());
        assertThat(application.timeline(created.getId())).extracting(com.jmopsagent.domain.InvestigationEvent::getMessage)
                .contains("No new approved evidence could be collected").doesNotContain("Maximum Claude iterations reached");
        for (var request : requests.getAllValues()) {
            assertThat(request.evidence()).anySatisfy(item -> assertThat(item.content()).contains("data.state: present"));
            assertThat(request.evidence()).anySatisfy(item -> assertThat(item.content()).contains("data.state: missing"));
            assertThat(request.evidence().toString()).doesNotContain("private-response-value", "good-fixture", "bad-fixture");
        }
    }

    @Test
    void traversesRegistryAndFeignChainOnceAndRequestsChangesForTheDependency() {
        AtomicInteger calls = new AtomicInteger();
        when(claude.analyze(any())).thenAnswer(invocation -> calls.incrementAndGet() == 1
                ? decision(ReasoningStatus.NEEDS_MORE_EVIDENCE, List.of(
                        new NextEvidenceRequest(EvidenceRequestType.RECENT_CHANGES, "data-api", "Inspect dependency changes"),
                        new NextEvidenceRequest(EvidenceRequestType.RECENT_BUSINESS_CALLS, "entry-api", "Find business calls")))
                : decision(ReasoningStatus.COMPLETE, List.of()));
        var created = application.createServiceInvestigation("entry-api", "TEST", "Returning errors");
        orchestrator.investigate(created.getId());
        var completed = application.get(created.getId());
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(completed.getService()).isEqualTo("entry-api");
        verify(tas, times(1)).getRecentLogs(eq("search-api"), eq(Environment.TEST), any());
        verify(kubernetes, times(1)).getRecentPodLogs(eq("data-api"), eq(Environment.TEST), any());
        verify(tas, times(1)).getEvents(eq("search-api"), eq(Environment.TEST), any());
        verify(gitlab, times(2)).searchCommits(eq("data-api"), anyList(), any());
        verify(gitlab, times(1)).searchCommits(eq("entry-api"), anyList(), any());
        verify(gitlab, never()).getCommits(anyString(), anyString(), anyInt());
        verify(tas, never()).getRecentLogs(eq("unknown-api"), any(), any());
        var query = ArgumentCaptor.forClass(EvidenceQuery.class);
        verify(splunk).searchRecentBusinessCallsDetailed(eq("entry-api"), eq(Environment.TEST), query.capture(), any());
        assertThat(Duration.between(query.getValue().from(), query.getValue().to())).isEqualTo(Duration.ofHours(72));
        var items = application.evidence(created.getId());
        assertThat(items).anySatisfy(item -> {
            assertThat(item.getService()).isEqualTo("data-api");
            assertThat(item.getMetadataJson()).contains("unverified", "bounded sample");
        });
        assertThat(items).anySatisfy(item -> assertThat(item.getSanitizedContent()).contains("database.table=demo-data-test"));
        var reasoning = ArgumentCaptor.forClass(ClaudeReasoningRequest.class);
        verify(claude, times(2)).analyze(reasoning.capture());
        assertThat(reasoning.getAllValues().getFirst().evidence()).anySatisfy(item ->
                assertThat(item.content()).contains("Branch history does not establish"));
    }

    @Test
    void aggregatesFullCommitSamplesAndKeepsNonSplunkEvidenceWhenSearchBudgetIsExhausted() {
        limits.setMaxSplunkSearches(1);
        List<CommitChange> commits = java.util.stream.IntStream.range(0, 10).mapToObj(index ->
                new CommitChange("abc12" + index, "DEMO change " + index, "author",
                        Instant.parse("2026-09-01T10:00:00Z"), List.of(), "")).toList();
        when(gitlab.searchCommits(anyString(), anyList(), any())).thenReturn(commits);
        when(gitlab.getCommits(anyString(), anyString(), anyInt())).thenReturn(commits.subList(0, 5));
        when(jenkins.getLastBuilds(anyString(), any(), anyInt())).thenAnswer(invocation -> List.of(
                new DeploymentInfo(invocation.getArgument(0), Environment.TEST, "demo/deploy", 10, "SUCCESS",
                        Instant.parse("2026-09-08T00:00:00Z"), "abc123", null, List.of(), List.of(), List.of(), Map.of())));
        var created = application.createServiceInvestigation("entry-api", "TEST", "Returning errors");
        orchestrator.investigate(created.getId());
        var completed = application.get(created.getId());
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(completed.getSplunkSearchCount()).isEqualTo(1);
        verify(splunk, never()).searchServiceEventsDetailed(anyString(), any(), any(), any());
        verify(gitlab).searchCommits(eq("data-api"), anyList(), any());
        verify(claude).analyze(any());
        assertThat(application.evidence(created.getId()).stream()
                .filter(item -> item.getSummary().startsWith("Advisory branch commit history")).toList())
                .hasSize(3).allSatisfy(item -> assertThat(item.getMetadataJson()).contains("\"sampleSize\":10"));
    }

    @Test
    void stopsAtDependencyServiceBudgetWithoutCollectingTheNextService() {
        limits.setMaxDependencyServices(1);
        var created = application.createServiceInvestigation("entry-api", "TEST", "Returning errors");
        orchestrator.investigate(created.getId());
        verify(tas).getRecentLogs(eq("search-api"), eq(Environment.TEST), any());
        verify(kubernetes, never()).getRecentPodLogs(eq("data-api"), any(), any());
        assertThat(application.get(created.getId()).getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
    }

    private static SplunkConnectorResult empty() {
        return new SplunkConnectorResult(new SplunkSearchResult(List.of(), List.of(), 0, false), SplunkSearchOutcome.NO_DATA);
    }

    private static ConnectorEvidence evidence(String service, EvidenceType type, String content) {
        return new ConnectorEvidence("demo-" + service + type, EvidenceSource.TAS, type, null, service,
                Environment.TEST, "Demo dependency evidence", content, null, Map.of(), 0.8);
    }

    private static ClaudeInvocationResult decision(ReasoningStatus status, List<NextEvidenceRequest> requests) {
        Instant now = Instant.now();
        return new ClaudeInvocationResult("demo-session", new ReasoningDecision(status, "Correlate dependency evidence",
                List.of(), requests, RootCauseCategory.UNKNOWN, List.of()), now, now, Duration.ZERO, 1, null, Map.of(), null, true);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        ProbeHttpFixture probeHttpFixture() { return new ProbeHttpFixture(); }

        @Bean
        com.jmopsagent.database.RegisteredApiProbeConnector registeredProbe(ServiceRegistry registry, ProbeHttpFixture fixture) {
            return new com.jmopsagent.database.RegisteredApiProbeConnector(registry,
                    org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request -> {
                        fixture.requests.incrementAndGet();
                        return reactor.core.publisher.Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                                .create(org.springframework.http.HttpStatus.OK).body(request.url().getQuery().contains("good-fixture")
                                        ? "{\"data\":{\"state\":\"private-response-value\"}}" : "{\"data\":{}}")
                                .build());
                    }));
        }

        @Bean @Primary
        ServiceRegistry dependencyRegistry() {
            String common = """
                        gitlab:
                          repository: https://git.example.invalid/demo/service
                        jenkins:
                          controller: demo
                          job: demo/deploy
                    """;
            String yaml = "services:\n"
                    + "  - service: entry-api\n    runtime.platform: TAS\n    dependencies: [search-alias, unknown-api]\n"
                    + common
                    + "  - service: search-api\n    runtime.platform: TAS\n    aliases: [search-alias]\n    dependencies: [data-api]\n"
                    + common
                    + "  - service: data-api\n    runtime.platform: EKS\n    dependencyHosts: [data.example.invalid]\n    dependencies: [entry-api]\n"
                    + "    eks:\n      namespace: demo-test\n      deployment: data-api\n      service: data-api\n"
                    + "    apiProbes:\n      enabled.TEST: true\n      baseUrl.TEST: https://data.example.invalid\n"
                    + "      knownGoodPath.TEST: /detail?id=good-fixture\n      knownBadPath.TEST: /detail?id=bad-fixture\n"
                    + "      responseFields: [data.state]\n"
                    + common;
            return new YamlServiceRegistry(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        }
    }

    static class ProbeHttpFixture {
        final AtomicInteger requests = new AtomicInteger();
    }
}
