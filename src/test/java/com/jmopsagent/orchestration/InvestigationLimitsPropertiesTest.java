package com.jmopsagent.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InvestigationLimitsPropertiesTest {

    @Test
    void trackingSearchWindowDefaultsToSeventyTwoHoursAndCanBeConfigured() {
        InvestigationLimitsProperties properties = new InvestigationLimitsProperties();

        assertThat(properties.getTrackingSearchWindow()).isEqualTo(Duration.ofHours(72));

        properties.setTrackingSearchWindow(Duration.ofDays(7));
        assertThat(properties.getTrackingSearchWindow()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void trackingSearchWindowRejectsUnboundedValues() {
        InvestigationLimitsProperties properties = new InvestigationLimitsProperties();

        assertThatIllegalArgumentException().isThrownBy(() ->
                properties.setTrackingSearchWindow(Duration.ofSeconds(30)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                properties.setTrackingSearchWindow(Duration.ofDays(31)));
    }

    @Test
    void followUpEvidenceRefreshIsBoundedAndConfigurable() {
        InvestigationLimitsProperties properties = new InvestigationLimitsProperties();

        assertThat(properties.getMaxFollowUpEvidenceCollections()).isEqualTo(1);
        properties.setMaxFollowUpEvidenceCollections(2);
        assertThat(properties.getMaxFollowUpEvidenceCollections()).isEqualTo(2);
        assertThatIllegalArgumentException().isThrownBy(() ->
                properties.setMaxFollowUpEvidenceCollections(11));
    }
}
