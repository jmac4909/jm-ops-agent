package com.jmopsagent.claude;

import com.jmopsagent.domain.RootCauseCategory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("local-mock")
public class DeterministicClaudeCodeClient implements ClaudeCodeClient {

    @Override
    public ClaudeInvocationResult analyze(ClaudeReasoningRequest request) {
        Instant started = Instant.now();
        List<ReasoningEvidence> currentEvidence = request.evidence().stream()
                .filter(this::isCurrentEvidence)
                .toList();
        String corpus = currentEvidence.stream()
                .map(e -> (e.summary() + " " + e.content()).toLowerCase(Locale.ROOT))
                .reduce("", (left, right) -> left + " " + right);

        ReasoningDecision decision;
        if (corpus.contains("parameter") && (corpus.contains("mismatch") || corpus.contains("not found")
                || corpus.contains("could not resolve"))) {
            List<String> ids = matchingIds(request, "parameter", "readiness", "config");
            decision = complete(
                    displayService(request.service()) + " is deployed but cannot become ready because its "
                            + request.environment() + " database parameter path does not resolve. "
                            + "The first downstream HTTP 500 and the runtime/configuration evidence align immediately after the latest deployment.",
                    RootCauseCategory.CONFIG,
                    0.92,
                    ids,
                    List.of(
                            "Verify the configured TEST Parameter Store path against the approved deployment configuration.",
                            "Correct the configuration through the normal reviewed deployment process; JM Ops Agent made no changes.",
                            "After deployment, confirm readiness and re-run the failing request with a new tracking ID."));
        } else if (corpus.contains("database") && (corpus.contains("connection") || corpus.contains("timeout"))) {
            decision = complete(
                    "The service failure is most consistent with a database connectivity or database configuration problem.",
                    RootCauseCategory.DEPENDENCY, 0.84, matchingIds(request, "database", "connection", "timeout"),
                    List.of("Validate the non-production database endpoint and connectivity using the approved read-only checks.",
                            "Compare current database configuration with the last known successful deployment."));
        } else if (hasCollectedDependencyFailure(currentEvidence)) {
            decision = complete(
                    "The collected dependency evidence localizes the failure to a downstream service rather than this service's runtime or code.",
                    RootCauseCategory.DEPENDENCY, 0.84,
                    matchingIds(request, "downstream", "returned http 500", "dependency"),
                    List.of("Inspect the downstream service's bounded operational evidence around the failure timestamp.",
                            "Confirm recovery with a new request only after the downstream owner has reviewed the failure."));
        } else if (hasDeploymentFailure(currentEvidence)) {
            decision = complete("The most recent deployment did not complete successfully and is the strongest correlated cause.",
                    RootCauseCategory.DEPLOYMENT, 0.88, matchingIds(request, "deployment", "failed"),
                    List.of("Review the failed Jenkins stage and console error.",
                            "Use the normal reviewed pipeline process for any retry or correction."));
        } else if (hasRuntimeReadinessFailure(currentEvidence)) {
            decision = complete("Kubernetes reports unavailable replicas or failed readiness checks while the latest deployment completed successfully. "
                            + "The strongest supported cause is a runtime health failure.",
                    RootCauseCategory.RUNTIME, 0.86, matchingIds(request, "readiness", "not ready", "unavailable"),
                    List.of("Inspect the bounded pod events and readiness logs for the affected workload.",
                            "Verify dependency availability before changing application code."));
        } else if (corpus.contains("500") || corpus.contains("exception")) {
            decision = new ReasoningDecision(ReasoningStatus.CODE_INVESTIGATION_RECOMMENDED,
                    "A concrete application error is present, but runtime and configuration evidence do not yet explain it. "
                            + "Inspect the exact deployed revision and only the code path named by the error.",
                    List.of(new Hypothesis("Application-level defect in the failing request path", 0.68,
                            matchingIds(request, "500", "exception", "error"))),
                    List.of(), RootCauseCategory.CODE,
                    List.of("Use Investigate Code to inspect the deployed SHA without modifying the repository."));
        } else {
            decision = new ReasoningDecision(ReasoningStatus.NEEDS_MORE_EVIDENCE,
                    "The available evidence does not contain a sufficiently specific failure signature.",
                    List.of(new Hypothesis("Cause remains unknown", 0.25, currentEvidence.stream()
                            .limit(2).map(ReasoningEvidence::id).toList())),
                    List.of(new NextEvidenceRequest(EvidenceRequestType.RECENT_LOGS, request.service(),
                                    "Find the first concrete service error."),
                            new NextEvidenceRequest(EvidenceRequestType.ERROR_PATTERNS, request.service(),
                                    "Determine whether the error repeats.")),
                    RootCauseCategory.UNKNOWN,
                    List.of("Collect the bounded requested evidence and reassess."));
        }
        Instant completed = Instant.now();
        return new ClaudeInvocationResult("mock-" + request.investigationId(), decision, started, completed,
                Duration.between(started, completed), 1, null, Map.of("adapter", "deterministic"), null, true);
    }

    @Override
    public ClaudeInvocationResult followUp(ClaudeFollowUpRequest request) {
        Instant started = Instant.now();
        List<String> ids = request.evidence().stream().limit(4).map(ReasoningEvidence::id).toList();
        boolean refreshed = request.targetedEvidenceCollected();
        String refreshOutcome = request.targetedEvidenceContext() == null ? ""
                : "\nTargeted refresh outcome: " + request.targetedEvidenceContext();
        ReasoningDecision decision = complete(
                "Based on the supplied evidence, " + request.priorDiagnosis() + " Follow-up question: " + request.question()
                        + (refreshed ? "\nTargeted refresh: " + request.targetedEvidenceContext()
                        : refreshOutcome.isBlank() ? "\nNo live evidence was recollected for this answer."
                        : refreshOutcome),
                categoryFrom(request.priorDiagnosis()), 0.82, ids,
                List.of("Recollect evidence only if the environment has changed since the investigation."));
        Instant completed = Instant.now();
        return new ClaudeInvocationResult(request.sessionId() == null ? "mock-followup-" + UUID.randomUUID() : request.sessionId(),
                decision, started, completed, Duration.between(started, completed), 1, null,
                Map.of("adapter", "deterministic"), null, true);
    }

    private ReasoningDecision complete(String summary, RootCauseCategory category, double confidence,
                                       List<String> evidenceIds, List<String> actions) {
        return new ReasoningDecision(ReasoningStatus.COMPLETE, summary,
                List.of(new Hypothesis(summary, confidence, evidenceIds)), List.of(), category, actions);
    }

    private List<String> matchingIds(ClaudeReasoningRequest request, String... terms) {
        List<ReasoningEvidence> currentEvidence = request.evidence().stream().filter(this::isCurrentEvidence).toList();
        List<String> matches = currentEvidence.stream().filter(e -> {
            String value = (e.summary() + " " + e.content()).toLowerCase(Locale.ROOT);
            for (String term : terms) if (value.contains(term)) return true;
            return false;
        }).map(ReasoningEvidence::id).limit(12).toList();
        return matches.isEmpty() ? currentEvidence.stream().limit(3).map(ReasoningEvidence::id).toList() : matches;
    }

    private RootCauseCategory categoryFrom(String diagnosis) {
        String value = diagnosis == null ? "" : diagnosis.toLowerCase(Locale.ROOT);
        if (value.contains("config") || value.contains("parameter")) return RootCauseCategory.CONFIG;
        if (value.contains("deploy")) return RootCauseCategory.DEPLOYMENT;
        if (value.contains("database") || value.contains("dependency")) return RootCauseCategory.DEPENDENCY;
        if (value.contains("code") || value.contains("application")) return RootCauseCategory.CODE;
        return RootCauseCategory.UNKNOWN;
    }

    private boolean hasDeploymentFailure(List<ReasoningEvidence> evidence) {
        return evidence.stream().filter(item -> "JENKINS".equalsIgnoreCase(item.source())).anyMatch(item -> {
            String value = text(item);
            return value.contains("was failure") || value.contains("result=failure")
                    || value.contains("result: failure") || value.contains("deployment verification failed")
                    || value.contains("stage failed");
        });
    }

    private boolean hasCollectedDependencyFailure(List<ReasoningEvidence> evidence) {
        return evidence.stream().filter(item -> "DEPENDENCY_STATUS".equalsIgnoreCase(item.type())).anyMatch(item -> {
            String value = text(item);
            return !value.contains("no dependency failure")
                    && (value.contains("indicates a failure") || value.contains("returned http 5")
                    || value.contains("connection refused") || value.contains("connection timeout"));
        });
    }

    private boolean hasRuntimeReadinessFailure(List<ReasoningEvidence> evidence) {
        return evidence.stream().filter(item -> "KUBERNETES".equalsIgnoreCase(item.source())).anyMatch(item -> {
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

    private boolean isCurrentEvidence(ReasoningEvidence evidence) {
        return !"HISTORICAL_INCIDENT".equalsIgnoreCase(evidence.source())
                && !"RUNBOOK".equalsIgnoreCase(evidence.source())
                && !"HISTORICAL_MATCH".equalsIgnoreCase(evidence.type())
                && !"RUNBOOK_EXCERPT".equalsIgnoreCase(evidence.type());
    }

    private String text(ReasoningEvidence evidence) {
        return (evidence.summary() + " " + evidence.content()).toLowerCase(Locale.ROOT);
    }

    private String displayService(String service) {
        return service == null || service.isBlank() ? "The localized service" : service;
    }
}
