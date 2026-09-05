package com.jmopsagent.orchestration;

import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.domain.EvidenceItem;
import com.jmopsagent.domain.EvidenceReliability;
import com.jmopsagent.domain.EvidenceSource;
import com.jmopsagent.domain.EvidenceType;
import com.jmopsagent.domain.RootCauseCategory;
import com.jmopsagent.claude.ReasoningStatus;
import com.jmopsagent.sanitization.SanitizationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalHeuristicsTest {
    private final OperationalHeuristics heuristics = new OperationalHeuristics();

    @Test
    void classifiesParameterResolutionAsConfiguration() {
        var result = heuristics.bestEffort(List.of(evidence(
                "ParameterResolutionException: Could not resolve parameter /catalog/db-url")), null);

        assertThat(result.rootCauseCategory()).isEqualTo(RootCauseCategory.CONFIG);
        assertThat(result.hypotheses().getFirst().evidenceIds()).isNotEmpty();
    }

    @Test
    void recommendsExplicitCodeEscalationWhenOperationalEvidenceIsInconclusive() {
        var result = heuristics.bestEffort(List.of(evidence("HTTP 500 with an unknown exception")),
                "Claude unavailable");

        assertThat(result.status().name()).isEqualTo("CODE_INVESTIGATION_RECOMMENDED");
        assertThat(result.recommendedActions()).singleElement().asString().contains("deployed revision");
    }

    @Test
    void classifiesKubernetesReadinessFailureAsRuntimeWhenJenkinsSucceeded() {
        var result = heuristics.bestEffort(List.of(
                evidence(EvidenceSource.KUBERNETES, EvidenceType.WORKLOAD_HEALTH,
                        "Deployment has unavailable replicas; ready=false"),
                evidence(EvidenceSource.KUBERNETES, EvidenceType.POD_EVENT,
                        "Readiness probe failed repeatedly"),
                evidence(EvidenceSource.JENKINS, EvidenceType.DEPLOYMENT_METADATA,
                        "Jenkins build was SUCCESS\nFailed stages: \nConsole errors: ")), null);

        assertThat(result.rootCauseCategory()).isEqualTo(RootCauseCategory.RUNTIME);
        assertThat(result.summary()).containsIgnoringCase("readiness");
    }

    @Test
    void requiresJenkinsFailureEvidenceBeforeClassifyingDeploymentFailure() {
        var result = heuristics.bestEffort(List.of(
                evidence(EvidenceSource.KUBERNETES, EvidenceType.WORKLOAD_HEALTH,
                        "Deployment has unavailable replicas"),
                evidence(EvidenceSource.JENKINS, EvidenceType.DEPLOYMENT_METADATA,
                        "Jenkins build deploy-service #88 was FAILURE; deployment verification failed")), null);

        assertThat(result.rootCauseCategory()).isEqualTo(RootCauseCategory.DEPLOYMENT);
    }

    @Test
    void classifiesPositiveDownstreamDependencyEvidenceBeforeGenericHttp500() {
        var result = heuristics.bestEffort(List.of(
                evidence(EvidenceSource.SPLUNK, EvidenceType.LOG_PATTERN,
                        "WebClientResponseException: downstream inventory-api returned HTTP 500"),
                evidence(EvidenceSource.DATABASE, EvidenceType.DEPENDENCY_STATUS,
                        "Downstream API evidence indicates a failure; inventory-api returned HTTP 500")), null);

        assertThat(result.rootCauseCategory()).isEqualTo(RootCauseCategory.DEPENDENCY);
        assertThat(result.summary()).containsIgnoringCase("downstream");
    }

    @Test
    void doesNotTreatNegativeDependencyEvidenceAsAFailure() {
        var result = heuristics.bestEffort(List.of(evidence(EvidenceSource.DATABASE, EvidenceType.DEPENDENCY_STATUS,
                "No dependency failure evidence; no dependency failure indicator was found")), null);

        assertThat(result.rootCauseCategory()).isEqualTo(RootCauseCategory.UNKNOWN);
    }

    @Test
    void healthyEvidenceDoesNotRecommendCodeInvestigation() {
        var result = heuristics.bestEffort(List.of(
                evidence(EvidenceSource.KUBERNETES, EvidenceType.WORKLOAD_HEALTH,
                        "Workload is healthy; all readiness checks are passing; ready=true"),
                evidence(EvidenceSource.JENKINS, EvidenceType.DEPLOYMENT_METADATA,
                        "Jenkins build was SUCCESS\nFailed stages: \nConsole errors: ")), "Claude unavailable");

        assertThat(result.status()).isEqualTo(ReasoningStatus.COMPLETE);
        assertThat(result.rootCauseCategory()).isEqualTo(RootCauseCategory.UNKNOWN);
        assertThat(result.summary()).containsIgnoringCase("does not contain a concrete failure");
    }

    @Test
    void positiveReadinessWordingIsNotClassifiedAsRuntimeFailure() {
        var result = heuristics.bestEffort(List.of(evidence(EvidenceSource.KUBERNETES,
                EvidenceType.WORKLOAD_HEALTH, "Readiness checks passing; ready=true; all replicas available")), null);

        assertThat(result.rootCauseCategory()).isEqualTo(RootCauseCategory.UNKNOWN);
        assertThat(result.summary()).doesNotContainIgnoringCase("degraded");
    }

    @Test
    void advisoryHistoryAndRunbookDoNotBecomeCurrentConfigurationProof() {
        EvidenceItem historical = evidence(EvidenceSource.HISTORICAL_INCIDENT, EvidenceType.HISTORICAL_MATCH,
                "Previous incident: parameter mismatch and could not resolve database URL");
        EvidenceItem runbook = evidence(EvidenceSource.RUNBOOK, EvidenceType.RUNBOOK_EXCERPT,
                "If a parameter is not found, verify configuration");
        var result = heuristics.bestEffort(List.of(
                evidence(EvidenceSource.KUBERNETES, EvidenceType.WORKLOAD_HEALTH,
                        "Workload is healthy; ready=true"), historical, runbook), null);

        assertThat(result.status()).isEqualTo(ReasoningStatus.COMPLETE);
        assertThat(result.rootCauseCategory()).isEqualTo(RootCauseCategory.UNKNOWN);
        assertThat(result.hypotheses()).allSatisfy(hypothesis -> assertThat(hypothesis.evidenceIds())
                .doesNotContain(historical.getId().toString(), runbook.getId().toString()));
    }

    private EvidenceItem evidence(String content) {
        return evidence(EvidenceSource.SPLUNK, EvidenceType.ERROR_LOG, content);
    }

    private EvidenceItem evidence(EvidenceSource source, EvidenceType type, String content) {
        return EvidenceItem.create(source, type, Instant.now(),
                "catalog-service", DeploymentEnvironment.TEST, content,
                new SanitizationResult(content, false, 0, List.of(), false), null, "{}",
                EvidenceReliability.HIGH);
    }
}
