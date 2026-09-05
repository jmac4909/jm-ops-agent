package com.jmopsagent.orchestration;

import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.EvidenceSource;
import com.jmopsagent.connector.EvidenceType;
import com.jmopsagent.connector.SplunkSearchResult;
import com.jmopsagent.connector.TraceEvent;
import com.jmopsagent.connector.mock.MockFixtures;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationEventType;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.persistence.InvestigationRepository;
import com.jmopsagent.splunk.MockSplunkConnector;
import com.jmopsagent.splunk.SplunkConnectorResult;
import com.jmopsagent.splunk.SplunkSearchOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "jmops.limits.max-evidence-items=12"
})
@ActiveProfiles({"test", "local-mock"})
@Import(TrackingEvidenceBudgetIntegrationTest.Configuration.class)
class TrackingEvidenceBudgetIntegrationTest {
    @Autowired InvestigationApplicationService applicationService;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired InvestigationRepository repository;
    @Autowired OversizedTrackingSplunkConnector splunk;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        splunk.reset();
    }

    @Test
    void localizesBeforeSamplingAndReservesCapacityForDownstreamTriage() {
        Investigation created = applicationService.createTrackingInvestigation(MockFixtures.TRACKING_ID, "TEST");

        orchestrator.investigate(created.getId());

        Investigation completed = applicationService.get(created.getId());
        var evidence = applicationService.evidence(created.getId());
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(completed.getService()).isEqualTo(MockFixtures.FAILING_SERVICE);
        assertThat(splunk.aroundTimestampCalls()).isZero();
        assertThat(evidence).hasSizeLessThanOrEqualTo(12);
        assertThat(evidence).anySatisfy(item -> {
            assertThat(item.getSourceSystem()).isEqualTo(com.jmopsagent.domain.EvidenceSource.KUBERNETES);
            assertThat(item.getEvidenceType()).isEqualTo(com.jmopsagent.domain.EvidenceType.WORKLOAD_HEALTH);
        });
        assertThat(evidence).anySatisfy(item ->
                assertThat(item.getSourceSystem()).isEqualTo(com.jmopsagent.domain.EvidenceSource.JENKINS));
        assertThat(evidence).anySatisfy(item -> {
            assertThat(item.getSourceSystem()).isEqualTo(com.jmopsagent.domain.EvidenceSource.KUBERNETES);
            assertThat(item.getEvidenceType()).isEqualTo(com.jmopsagent.domain.EvidenceType.POD_LOG);
            assertThat(item.getService()).isEqualTo(MockFixtures.FAILING_SERVICE);
        });
        assertThat(evidence)
                .filteredOn(item -> item.getEvidenceType() == com.jmopsagent.domain.EvidenceType.CALL_CHAIN)
                .anySatisfy(item -> assertThat(item.getMetadataJson()).contains("\"httpStatus\":500"));
        assertThat(applicationService.timeline(created.getId()))
                .anySatisfy(event -> {
                    assertThat(event.getType()).isEqualTo(InvestigationEventType.ANALYSIS);
                    assertThat(event.getMessage()).contains("Failure localized");
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        OversizedTrackingSplunkConnector oversizedTrackingSplunkConnector() {
            return new OversizedTrackingSplunkConnector();
        }
    }

    static final class OversizedTrackingSplunkConnector extends MockSplunkConnector {
        private int aroundTimestampCalls;

        @Override
        public SplunkConnectorResult searchByTrackingIdDetailed(
                String trackingId, Environment environment, EvidenceQuery query) {
            List<TraceEvent> trace = new ArrayList<>();
            List<ConnectorEvidence> evidence = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                boolean failure = index == 19;
                Instant timestamp = MockFixtures.REQUEST_TIME.plusMillis(index * 10L);
                String downstream = failure ? MockFixtures.FAILING_SERVICE : "identity-service";
                String summary = failure ? "Downstream service returned HTTP 500" : "Downstream call succeeded";
                TraceEvent event = new TraceEvent(timestamp, trackingId, "edge-gateway", "request-" + index,
                        failure ? "FAILURE" : "SUCCESS", failure ? 500 : 200, downstream, summary, null, Map.of());
                trace.add(event);
                evidence.add(MockFixtures.evidence("oversized-trace-" + index, EvidenceSource.SPLUNK,
                        EvidenceType.TRACE_EVENT, timestamp, event.service(), environment, summary,
                        "operation=" + event.operation() + " status=" + event.httpStatus(),
                        Map.of("status", Integer.toString(event.httpStatus())), 0.95));
            }
            return new SplunkConnectorResult(new SplunkSearchResult(evidence, trace, trace.size(), false),
                    SplunkSearchOutcome.SUCCESS);
        }

        @Override
        public SplunkConnectorResult searchAroundTimestampDetailed(String service, Environment environment,
                Instant timestamp, EvidenceQuery query) {
            aroundTimestampCalls++;
            return SplunkConnectorResult.fromLegacy(super.searchAroundTimestamp(service, environment, timestamp, query));
        }

        int aroundTimestampCalls() {
            return aroundTimestampCalls;
        }

        void reset() {
            aroundTimestampCalls = 0;
        }
    }
}
