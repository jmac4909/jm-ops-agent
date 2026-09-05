package com.jmopsagent.orchestration;

import com.jmopsagent.claude.Hypothesis;
import com.jmopsagent.claude.ReasoningDecision;
import com.jmopsagent.claude.ReasoningStatus;
import com.jmopsagent.domain.EvidenceItem;
import com.jmopsagent.domain.EvidenceType;
import com.jmopsagent.domain.RootCauseCategory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Deterministic, evidence-citing fallback used only when the configured reasoning CLI is unavailable or invalid. */
@Component
public class OperationalHeuristics {

    public ReasoningDecision bestEffort(List<EvidenceItem> evidence, String failureReason) {
        List<EvidenceItem> currentEvidence = evidence.stream().filter(this::isCurrentEvidence).toList();
        String corpus = currentEvidence.stream()
                .map(item -> (item.getSummary() + " " + item.getSanitizedContent()).toLowerCase(Locale.ROOT))
                .reduce("", (left, right) -> left + " " + right);
        if (corpus.contains("parameter") && (corpus.contains("not found") || corpus.contains("mismatch")
                || corpus.contains("could not resolve"))) {
            return decision("The sanitized evidence points to an unresolved or mismatched configuration parameter.",
                    RootCauseCategory.CONFIG, .86, matches(currentEvidence, "parameter", "config", "readiness"),
                    List.of("Verify the DEV/TEST parameter path in the reviewed deployment configuration.",
                            "Apply any correction only through the normal approved deployment workflow."));
        }
        if (corpus.contains("database") && (corpus.contains("connection") || corpus.contains("timeout"))) {
            return decision("The evidence points to database connectivity or database configuration.",
                    RootCauseCategory.DEPENDENCY, .78, matches(currentEvidence, "database", "connection", "timeout"),
                    List.of("Run the approved read-only database connectivity checks.",
                            "Compare dependency configuration to the previous successful deployment."));
        }
        if (hasCollectedDependencyFailure(currentEvidence)) {
            return decision("The collected dependency evidence localizes the failure to a downstream service.",
                    RootCauseCategory.DEPENDENCY, .78, matches(currentEvidence, "downstream", "returned http 500", "dependency"),
                    List.of("Inspect the downstream service's bounded operational evidence around the failure timestamp."));
        }
        if (hasDeploymentFailure(currentEvidence)) {
            return decision("The failed deployment is the strongest correlated operational cause.",
                    RootCauseCategory.DEPLOYMENT, .82, matches(currentEvidence, "deployment", "failed"),
                    List.of("Inspect the failed Jenkins stage and correct it through the approved pipeline workflow."));
        }
        if (hasRuntimeReadinessFailure(currentEvidence)) {
            return decision("Kubernetes readiness or replica health is degraded while no failed deployment was observed.",
                    RootCauseCategory.RUNTIME, .80, matches(currentEvidence, "readiness", "not ready", "unavailable"),
                    List.of("Inspect the bounded pod events and readiness logs for the affected workload."));
        }
        if (!hasConcreteFailureEvidence(currentEvidence)) {
            return decision("Current live evidence does not contain a concrete failure. No application-code cause was inferred.",
                    RootCauseCategory.UNKNOWN, .30,
                    matches(currentEvidence, "healthy", "success", "ready=true", "no warning", "no error"),
                    List.of("Confirm the symptom and failure timestamp before collecting additional evidence."));
        }
        String limitation = failureReason == null ? "Reasoning was inconclusive." : failureReason;
        return new ReasoningDecision(ReasoningStatus.CODE_INVESTIGATION_RECOMMENDED,
                "Runtime/configuration evidence did not establish a supported operational root cause. " + limitation,
                List.of(new Hypothesis("Application-level investigation may be required", .45,
                        currentEvidence.stream().filter(i -> i.getSummary().toLowerCase(Locale.ROOT).contains("error"))
                                .limit(5).map(i -> i.getId().toString()).toList())),
                List.of(), RootCauseCategory.UNKNOWN,
                List.of("Inspect the exact deployed revision only if the developer chooses Investigate Code."));
    }

    private ReasoningDecision decision(String summary, RootCauseCategory category, double confidence,
                                       List<String> evidenceIds, List<String> actions) {
        return new ReasoningDecision(ReasoningStatus.COMPLETE, summary,
                List.of(new Hypothesis(summary, confidence, evidenceIds)), List.of(), category, actions);
    }

    private List<String> matches(List<EvidenceItem> evidence, String... terms) {
        return evidence.stream().filter(item -> {
            String text = (item.getSummary() + " " + item.getSanitizedContent()).toLowerCase(Locale.ROOT);
            for (String term : terms) if (text.contains(term)) return true;
            return false;
        }).limit(12).map(item -> item.getId().toString()).toList();
    }

    private boolean hasDeploymentFailure(List<EvidenceItem> evidence) {
        return evidence.stream().filter(item -> item.getSourceSystem() == com.jmopsagent.domain.EvidenceSource.JENKINS)
                .anyMatch(item -> {
                    String value = text(item);
                    return value.contains("was failure") || value.contains("result=failure")
                            || value.contains("result: failure") || value.contains("deployment verification failed")
                            || value.contains("stage failed");
                });
    }

    private boolean hasCollectedDependencyFailure(List<EvidenceItem> evidence) {
        return evidence.stream().filter(item -> item.getEvidenceType() == EvidenceType.DEPENDENCY_STATUS)
                .anyMatch(item -> {
                    String value = text(item);
                    return !value.contains("no dependency failure")
                            && (value.contains("indicates a failure") || value.contains("returned http 5")
                            || value.contains("connection refused") || value.contains("connection timeout"));
                });
    }

    private boolean hasRuntimeReadinessFailure(List<EvidenceItem> evidence) {
        return evidence.stream().filter(item -> item.getSourceSystem() == com.jmopsagent.domain.EvidenceSource.KUBERNETES)
                .anyMatch(item -> {
                    String value = text(item);
                    return hasNegativeReadinessSignal(value);
                });
    }

    private boolean hasNegativeReadinessSignal(String value) {
        return value.contains("not ready") || value.contains("ready=false")
                || value.contains("unavailable replica") || value.contains("readinessprobefailed")
                || value.contains("readiness probe failed") || value.contains("failed readiness")
                || value.contains("readiness failure")
                || value.contains("readiness") && (value.contains("reported down")
                || value.contains("returned down") || value.contains("http 503")
                || value.contains("refusing_traffic"));
    }

    private boolean hasConcreteFailureEvidence(List<EvidenceItem> evidence) {
        return evidence.stream().anyMatch(item -> {
            String value = text(item);
            return value.contains("http 5") || value.contains("internal_server_error")
                    || value.contains("exception") || value.contains("connection refused")
                    || value.contains("connection timeout") || value.contains("could not resolve")
                    || value.contains("was failure") || value.contains("ready=false")
                    || value.contains("unavailable replica") || value.contains("probe failed");
        });
    }

    private boolean isCurrentEvidence(EvidenceItem item) {
        return item.getSourceSystem() != com.jmopsagent.domain.EvidenceSource.HISTORICAL_INCIDENT
                && item.getSourceSystem() != com.jmopsagent.domain.EvidenceSource.RUNBOOK
                && item.getEvidenceType() != EvidenceType.HISTORICAL_MATCH
                && item.getEvidenceType() != EvidenceType.RUNBOOK_EXCERPT;
    }

    private String text(EvidenceItem item) {
        return (item.getSummary() + " " + item.getSanitizedContent()).toLowerCase(Locale.ROOT);
    }
}
