package com.jmopsagent.domain;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IncidentWindowTest {
    @Test
    void acceptsUtcFormValuesAndOffsetsAndKeepsLegacySubmissionsCurrent() {
        assertThat(IncidentWindow.parse(null, "")).isNull();
        assertThat(IncidentWindow.parse("2025-09-09T09:00", "2025-09-09T12:00"))
                .isEqualTo(IncidentWindow.parse("2025-09-09T05:00:00-04:00", "2025-09-09T08:00:00-04:00"));
        var investigation = Investigation.forServiceTriage("demo-api", DeploymentEnvironment.TEST, "Was failing");
        assertThat(investigation.isRetrospective()).isFalse();
        investigation.setIncidentWindow(IncidentWindow.parse("2025-09-09T09:00", "2025-09-09T12:00"));
        assertThat(investigation.isRetrospective()).isTrue();
        investigation.transitionTo(InvestigationStatus.DISCOVERING, "Started");
        assertThatThrownBy(() -> investigation.setIncidentWindow(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsIncompleteReversedMalformedOverlongAndFutureWindows() {
        for (String[] values : new String[][]{{"2025-09-09T09:00", ""}, {"", "2025-09-09T09:00"},
                {"2025-09-09T09:00", "2025-09-09T09:00"}, {"2025-09-09T09:00", "2025-09-08T09:00"},
                {"yesterday", "today"}, {"2024-09-09T09:00", "2025-09-17T09:00"}}) {
            assertThatThrownBy(() -> IncidentWindow.parse(values[0], values[1])).isInstanceOf(IllegalArgumentException.class);
        }
        var investigation = Investigation.forTrackingId("DEMO-TRACE", DeploymentEnvironment.TEST);
        assertThatThrownBy(() -> investigation.setIncidentWindow(new IncidentWindow(
                investigation.getStartedAt(), investigation.getStartedAt().plusSeconds(60))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("past");
        var window = IncidentWindow.parse("2025-09-09T09:00", "2025-09-09T12:00");
        assertThat(window.recoveryEnd(Instant.parse("2025-09-10T00:00:00Z")))
                .isEqualTo(Instant.parse("2025-09-10T00:00:00Z"));
        assertThat(window.recoveryEnd(Instant.parse("2026-09-10T00:00:00Z")))
                .isEqualTo(Instant.parse("2025-09-12T12:00:00Z"));
    }
}
