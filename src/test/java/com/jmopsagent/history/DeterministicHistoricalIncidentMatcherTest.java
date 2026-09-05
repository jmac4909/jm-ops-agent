package com.jmopsagent.history;

import com.jmopsagent.domain.ConfidenceLevel;
import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.domain.FeedbackRating;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.domain.RootCauseCategory;
import com.jmopsagent.persistence.InvestigationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeterministicHistoricalIncidentMatcherTest {

    @Test
    void deterministicMatchingPrefersAConfirmedSimilarOutcome() {
        Investigation confirmed = incident("Could not resolve parameter /catalog/db-url");
        confirmed.recordFeedback(FeedbackRating.YES, "Parameter Store path mismatch", "Corrected deployment config");
        Investigation unconfirmed = incident("Could not resolve parameter /catalog/db-url");

        InvestigationRepository repository = mock(InvestigationRepository.class);
        when(repository.findByStatusOrderByCompletedAtDesc(eq(InvestigationStatus.COMPLETED), any(Pageable.class)))
                .thenReturn(List.of(unconfirmed, confirmed));
        HistoricalIncidentMatcher matcher = new DeterministicHistoricalIncidentMatcher(repository);

        List<HistoricalIncidentMatch> matches = matcher.findMatches(
                HistoricalIncidentQuery.forService("catalog-service", DeploymentEnvironment.TEST,
                        "Could not resolve parameter /catalog/db-url"), 5);

        assertThat(matches).hasSize(2);
        assertThat(matches.getFirst().confirmed()).isTrue();
        assertThat(matches.getFirst().actualRootCause()).isEqualTo("Parameter Store path mismatch");
    }

    private static Investigation incident(String diagnosis) {
        Investigation investigation = Investigation.forServiceTriage("catalog-service",
                DeploymentEnvironment.TEST, "500 errors after deployment");
        investigation.complete(diagnosis, ConfidenceLevel.HIGH, RootCauseCategory.CONFIG,
                List.of("Verify the configured parameter path"));
        return investigation;
    }
}
