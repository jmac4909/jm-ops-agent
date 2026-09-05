package com.jmopsagent.orchestration;

import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.SplunkSearchResult;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.persistence.InvestigationRepository;
import com.jmopsagent.splunk.MockSplunkConnector;
import com.jmopsagent.splunk.SplunkConnectorResult;
import com.jmopsagent.splunk.SplunkSearchOutcome;
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
import java.util.ArrayList;
import java.util.List;

import static com.jmopsagent.connector.mock.MockFixtures.TRACKING_ID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "jmops.limits.tracking-search-window=72h"
})
@ActiveProfiles({"test", "local-mock"})
@Import(TrackingSearchWindowIntegrationTest.Configuration.class)
class TrackingSearchWindowIntegrationTest {
    @Autowired InvestigationApplicationService applicationService;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired InvestigationRepository repository;
    @Autowired ExpandingWindowSplunkConnector splunk;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        splunk.reset();
    }

    @Test
    void expandsFromFourHoursToConfiguredMaximumOnlyAfterNoData() {
        Investigation investigation = applicationService.createTrackingInvestigation(TRACKING_ID, "TEST");

        orchestrator.investigate(investigation.getId());

        assertThat(applicationService.get(investigation.getId()).getStatus())
                .isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(splunk.trackingWindows()).containsExactly(Duration.ofHours(4), Duration.ofHours(72));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        ExpandingWindowSplunkConnector expandingWindowSplunkConnector() {
            return new ExpandingWindowSplunkConnector();
        }
    }

    static final class ExpandingWindowSplunkConnector extends MockSplunkConnector {
        private final List<Duration> trackingWindows = new ArrayList<>();

        @Override
        public SplunkConnectorResult searchByTrackingIdDetailed(
                String trackingId, Environment environment, EvidenceQuery query) {
            trackingWindows.add(Duration.between(query.from(), query.to()));
            if (trackingWindows.size() == 1) {
                return new SplunkConnectorResult(
                        new SplunkSearchResult(List.of(), List.of(), 0, false), SplunkSearchOutcome.NO_DATA);
            }
            return SplunkConnectorResult.fromLegacy(super.searchByTrackingId(trackingId, environment, query));
        }

        List<Duration> trackingWindows() {
            return List.copyOf(trackingWindows);
        }

        void reset() {
            trackingWindows.clear();
        }
    }
}
