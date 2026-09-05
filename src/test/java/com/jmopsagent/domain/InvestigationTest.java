package com.jmopsagent.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigationTest {

    @Test
    void enforcesStrictEnvironmentAllowList() {
        assertThat(DeploymentEnvironment.parse("test")).isEqualTo(DeploymentEnvironment.TEST);
        assertThatThrownBy(() -> DeploymentEnvironment.parse("PROD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only DEV and TEST");
    }

    @Test
    void codeEscalationIsExplicitAndCanResumeACompletedInvestigation() {
        Investigation investigation = Investigation.forServiceTriage(
                "catalog-service", DeploymentEnvironment.TEST, "HTTP 500 after deployment");
        investigation.complete("Likely application defect", ConfidenceLevel.MEDIUM,
                RootCauseCategory.CODE, List.of("Inspect the deployed revision"));

        investigation.beginCodeInvestigation();

        assertThat(investigation.getStatus()).isEqualTo(InvestigationStatus.CODE_INVESTIGATION);
        assertThat(investigation.getCompletedAt()).isNull();
        assertThat(investigation.getEvents()).extracting(InvestigationEvent::getStatus)
                .contains(InvestigationStatus.CODE_INVESTIGATION);
    }

    @Test
    void genericTransitionCannotReopenTerminalInvestigation() {
        Investigation investigation = Investigation.forTrackingId("DEMO-TRACE-001", DeploymentEnvironment.TEST);
        investigation.complete("Config mismatch", ConfidenceLevel.HIGH, RootCauseCategory.CONFIG, List.of());

        assertThatThrownBy(() -> investigation.transitionTo(InvestigationStatus.ANALYZING, "try again"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unavailableCodeEscalationRetainsTheOperationalDiagnosis() {
        Investigation investigation = Investigation.forServiceTriage(
                "catalog-service", DeploymentEnvironment.TEST, "HTTP 500");
        investigation.complete("Supported operational diagnosis", ConfidenceLevel.HIGH,
                RootCauseCategory.CONFIG, List.of("Verify configuration"));
        investigation.beginCodeInvestigation();

        investigation.completeCodeInvestigationWithLimitation("Exact deployed SHA was unavailable");

        assertThat(investigation.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(investigation.getFinalDiagnosis()).contains("Supported operational diagnosis", "Code investigation limitation");
        assertThat(investigation.getCompletedAt()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(value = InvestigationStatus.class, names = {"ANALYZING", "NEEDS_MORE_EVIDENCE"})
    void unavailableCodeEscalationCanRecoverFromActiveReasoningStatuses(InvestigationStatus activeStatus) {
        Investigation investigation = Investigation.forServiceTriage(
                "catalog-service", DeploymentEnvironment.TEST, "HTTP 500");
        investigation.complete("Supported operational diagnosis", ConfidenceLevel.MEDIUM,
                RootCauseCategory.CODE, List.of("Inspect the deployed revision"));
        investigation.beginCodeInvestigation();
        investigation.transitionTo(InvestigationStatus.ANALYZING, "Analyzing code evidence");
        if (activeStatus == InvestigationStatus.NEEDS_MORE_EVIDENCE) {
            investigation.transitionTo(activeStatus, "Collecting one more approved code artifact");
        }

        investigation.completeCodeInvestigationWithLimitation("Code reasoning was unavailable");

        assertThat(investigation.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(investigation.getFinalDiagnosis()).contains("Supported operational diagnosis",
                "Code investigation limitation");
        assertThat(investigation.getCompletedAt()).isNotNull();
    }

    @Test
    void codeEscalationRecoveryRejectsAnOrdinaryOperationalInvestigation() {
        Investigation investigation = Investigation.forServiceTriage(
                "catalog-service", DeploymentEnvironment.TEST, "HTTP 500");

        assertThatThrownBy(() -> investigation.completeCodeInvestigationWithLimitation("Not a code stage"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No code investigation is active");
    }
}
