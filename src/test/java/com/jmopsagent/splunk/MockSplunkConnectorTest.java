package com.jmopsagent.splunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.SplunkSearchResult;
import com.jmopsagent.connector.mock.MockFixtures;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MockSplunkConnectorTest {
    private final MockSplunkConnector connector = new MockSplunkConnector();
    private final EvidenceQuery query = new EvidenceQuery(Instant.parse("2026-01-15T10:30:00Z"),
            Instant.parse("2026-01-15T10:50:00Z"), 50, 50_000);

    @Test
    void reconstructsTheSyntheticCallChainAndLocalizesDownstreamFailure() {
        SplunkSearchResult result = connector.searchByTrackingId(MockFixtures.TRACKING_ID, Environment.TEST, query);

        assertThat(result.traceEvents()).extracting(event -> event.service())
                .containsExactly("edge-gateway", "identity-service", "edge-gateway", MockFixtures.FAILING_SERVICE);
        assertThat(result.traceEvents()).anySatisfy(event -> {
            assertThat(event.downstreamService()).isEqualTo(MockFixtures.FAILING_SERVICE);
            assertThat(event.httpStatus()).isEqualTo(500);
            assertThat(event.outcome()).isEqualTo("FAILURE");
        });
    }

    @Test
    void returnsCollapsedBoundedErrorEvidenceForFailingService() {
        SplunkSearchResult result = connector.searchErrorsForService(MockFixtures.FAILING_SERVICE, Environment.TEST, query);

        assertThat(result.evidence()).hasSize(2);
        assertThat(result.evidence()).allSatisfy(item -> assertThat(item.content()).hasSizeLessThan(50_001));
        assertThat(result.evidence().getFirst().metadata()).containsEntry("frequency", "18");
    }

    @Test
    void recentActivityReturnsBoundedNonBodyMetadata() {
        SplunkSearchResult result = connector.searchRecentActivity("healthy-service", Environment.DEV, query);

        assertThat(result.evidence()).singleElement().satisfies(item -> {
            assertThat(item.type().name()).isEqualTo("RECENT_ACTIVITY");
            assertThat(item.metadata()).containsEntry("statusClass", "2xx")
                    .containsEntry("trafficEventCount", "24")
                    .containsEntry("countSemantics", "synthetic-request-events");
            assertThat(item.content()).doesNotContain("requestBody", "responseBody");
        });
    }
}
