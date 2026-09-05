package com.jmopsagent.orchestration;

import com.jmopsagent.claude.ClaudeCodeClient;
import com.jmopsagent.claude.ClaudeFollowUpRequest;
import com.jmopsagent.claude.ClaudeInvocationResult;
import com.jmopsagent.claude.ClaudeReasoningRequest;
import com.jmopsagent.claude.ReasoningDecision;
import com.jmopsagent.claude.ReasoningStatus;
import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.EvidenceSource;
import com.jmopsagent.connector.EvidenceType;
import com.jmopsagent.connector.SplunkSearchResult;
import com.jmopsagent.connector.TraceEvent;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationEventType;
import com.jmopsagent.domain.RootCauseCategory;
import com.jmopsagent.persistence.InvestigationRepository;
import com.jmopsagent.splunk.SplunkConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "local-mock"})
@Import(InvalidTraceIdentifierSafetyIntegrationTest.Configuration.class)
class InvalidTraceIdentifierSafetyIntegrationTest {
    private static final String INVALID_SERVICE = "edge; Authorization: Splunk trace-secret-value";
    private static final String INVALID_DOWNSTREAM = "catalog / Authorization: Splunk downstream-secret-value";

    @Autowired InvestigationApplicationService applicationService;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired InvestigationRepository repository;
    @Autowired CapturingClaudeClient claude;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        claude.reset();
    }

    @Test
    void invalidTraceIdentifiersCannotEnterPersistenceScopeRenderingOrReasoning() {
        Investigation created = applicationService.createTrackingInvestigation("TRACE-INJECTION", "TEST");

        orchestrator.investigate(created.getId());

        Investigation completed = applicationService.get(created.getId());
        String persisted = applicationService.evidence(created.getId()).stream()
                .map(item -> String.join(" ", Objects.toString(item.getService(), ""), item.getSummary(),
                        item.getSanitizedContent(), Objects.toString(item.getMetadataJson(), "")))
                .collect(Collectors.joining(" "))
                + applicationService.timeline(created.getId()).stream()
                .map(event -> event.getMessage()).collect(Collectors.joining(" "))
                + Objects.toString(completed.getService(), "")
                + Objects.toString(completed.getFinalDiagnosis(), "");
        ClaudeReasoningRequest reasoning = claude.lastRequest();
        String reasoningInput = reasoning.service() + " " + reasoning.evidence().stream()
                .map(item -> item.summary() + " " + item.content()).collect(Collectors.joining(" "));

        assertThat(completed.getService()).isEqualTo("catalog-service");
        assertThat(persisted).doesNotContain(INVALID_SERVICE, "trace-secret-value");
        assertThat(reasoningInput).doesNotContain(INVALID_SERVICE, "trace-secret-value");
        assertThat(applicationService.timeline(created.getId()))
                .filteredOn(event -> event.getType() == InvestigationEventType.NOTE)
                .anySatisfy(event -> assertThat(event.getMessage())
                        .isEqualTo("Invalid service identifiers in tracking evidence were ignored"));
    }

    @Test
    void entirelyInvalidFailureIdentifiersStopWithoutFalseLocalizationOrReasoning() {
        Investigation created = applicationService.createTrackingInvestigation("TRACE-INJECTION-ALL", "TEST");

        orchestrator.investigate(created.getId());

        Investigation completed = applicationService.get(created.getId());
        String persisted = applicationService.evidence(created.getId()).stream()
                .map(item -> String.join(" ", Objects.toString(item.getService(), ""), item.getSummary(),
                        item.getSanitizedContent(), Objects.toString(item.getMetadataJson(), "")))
                .collect(Collectors.joining(" "))
                + applicationService.timeline(created.getId()).stream()
                .map(event -> event.getMessage()).collect(Collectors.joining(" "));

        assertThat(completed.getService()).isNull();
        assertThat(completed.getRootCauseCategory()).isEqualTo(RootCauseCategory.UNKNOWN);
        assertThat(claude.wasInvoked()).isFalse();
        assertThat(persisted)
                .doesNotContain(INVALID_SERVICE, INVALID_DOWNSTREAM, "trace-secret-value",
                        "downstream-secret-value", "Failure localized to null");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        MaliciousTraceSplunkConnector maliciousTraceSplunkConnector() {
            return new MaliciousTraceSplunkConnector();
        }

        @Bean
        @Primary
        CapturingClaudeClient capturingClaudeClient() {
            return new CapturingClaudeClient();
        }
    }

    static final class MaliciousTraceSplunkConnector implements SplunkConnector {
        @Override
        public SplunkSearchResult searchByTrackingId(String trackingId, Environment environment, EvidenceQuery query) {
            Instant timestamp = Instant.parse("2026-09-04T10:40:00Z");
            String downstream = trackingId.endsWith("-ALL") ? INVALID_DOWNSTREAM : "catalog-service";
            TraceEvent trace = new TraceEvent(timestamp, trackingId, INVALID_SERVICE, "providerLookup",
                    "FAILURE", 500, downstream, "Observed downstream HTTP 500",
                    URI.create("https://splunk.example.invalid/search"), Map.of());
            ConnectorEvidence evidence = new ConnectorEvidence("unsafe-trace", EvidenceSource.SPLUNK,
                    EvidenceType.TRACE_EVENT, timestamp, INVALID_SERVICE, environment,
                    "Observed downstream HTTP 500", "status=500", null, Map.of("status", "500"), 0.9);
            return new SplunkSearchResult(List.of(evidence), List.of(trace), 1, false);
        }

        @Override
        public SplunkSearchResult searchErrorsForService(String service, Environment environment, EvidenceQuery query) {
            return empty();
        }

        @Override
        public SplunkSearchResult searchAroundTimestamp(String service, Environment environment, Instant timestamp,
                                                        EvidenceQuery query) {
            return empty();
        }

        @Override
        public SplunkSearchResult searchServiceEvents(String service, Environment environment, EvidenceQuery query) {
            return empty();
        }

        @Override
        public SplunkSearchResult getErrorPatterns(String service, Environment environment, EvidenceQuery query) {
            return empty();
        }

        private SplunkSearchResult empty() {
            return new SplunkSearchResult(List.of(), List.of(), 0, false);
        }
    }

    static final class CapturingClaudeClient implements ClaudeCodeClient {
        private volatile ClaudeReasoningRequest lastRequest;

        @Override
        public ClaudeInvocationResult analyze(ClaudeReasoningRequest request) {
            lastRequest = request;
            Instant now = Instant.now();
            ReasoningDecision decision = new ReasoningDecision(ReasoningStatus.COMPLETE,
                    "Evidence remained within the validated service scope", List.of(), List.of(),
                    RootCauseCategory.UNKNOWN, List.of("Continue with the approved read-only workflow."));
            return new ClaudeInvocationResult("trace-safety-session", decision, now, now.plusMillis(1),
                    Duration.ofMillis(1), 1, null, Map.of(), null, true);
        }

        @Override
        public ClaudeInvocationResult followUp(ClaudeFollowUpRequest request) {
            throw new UnsupportedOperationException("not used by this test");
        }

        ClaudeReasoningRequest lastRequest() {
            return Objects.requireNonNull(lastRequest, "Claude reasoning was not invoked");
        }

        boolean wasInvoked() {
            return lastRequest != null;
        }

        void reset() {
            lastRequest = null;
        }
    }
}
