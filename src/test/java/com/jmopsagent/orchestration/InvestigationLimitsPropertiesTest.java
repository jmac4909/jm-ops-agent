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
    void automaticHistoryDiscoveryIsBoundedAndConfigurable() {
        InvestigationLimitsProperties properties = new InvestigationLimitsProperties();

        assertThat(properties.getHistoryDiscoveryWindow()).isEqualTo(Duration.ofDays(30));
        properties.setHistoryDiscoveryWindow(Duration.ofDays(60));
        assertThat(properties.getHistoryDiscoveryWindow()).isEqualTo(Duration.ofDays(60));
        assertThatIllegalArgumentException().isThrownBy(() ->
                properties.setHistoryDiscoveryWindow(Duration.ofDays(91)));
    }
}
