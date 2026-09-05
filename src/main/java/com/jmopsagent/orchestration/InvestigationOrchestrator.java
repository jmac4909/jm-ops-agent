package com.jmopsagent.orchestration;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.jmopsagent.claude.ClaudeCodeClient;
import com.jmopsagent.claude.BoundedReasoningEvidenceMapper;
import com.jmopsagent.claude.ClaudeInvocationResult;
import com.jmopsagent.claude.ClaudeReasoningRequest;
import com.jmopsagent.claude.EvidenceRequestType;
import com.jmopsagent.claude.NextEvidenceRequest;
import com.jmopsagent.claude.ReasoningDecision;
import com.jmopsagent.claude.ReasoningEvidence;
import com.jmopsagent.claude.ReasoningStatus;
import com.jmopsagent.connector.CommitChange;
import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.DeploymentInfo;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.SplunkSearchResult;
import com.jmopsagent.connector.TraceEvent;
import com.jmopsagent.database.DependencyConnector;
import com.jmopsagent.database.DependencyType;
import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.domain.EvidenceItem;
import com.jmopsagent.domain.EvidenceReliability;
import com.jmopsagent.domain.EvidenceSource;
import com.jmopsagent.domain.EvidenceType;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationEventType;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.domain.InvestigationType;
import com.jmopsagent.domain.RootCauseCategory;
import com.jmopsagent.gitlab.GitLabConnector;
import com.jmopsagent.history.HistoricalIncidentMatch;
import com.jmopsagent.history.HistoricalIncidentMatcher;
import com.jmopsagent.history.HistoricalIncidentQuery;
import com.jmopsagent.jenkins.JenkinsConnector;
import com.jmopsagent.kubernetes.KubernetesConnector;
import com.jmopsagent.persistence.SanitizedEvidenceStore;
import com.jmopsagent.registry.ServiceDefinition;
import com.jmopsagent.registry.ServiceRegistry;
import com.jmopsagent.registry.ServiceRegistryEnricher;
import com.jmopsagent.sanitization.EvidenceDraft;
import com.jmopsagent.splunk.SplunkConnector;
import com.jmopsagent.splunk.SplunkConnectorResult;
import com.jmopsagent.splunk.SplunkSearchPermit;
import com.jmopsagent.splunk.SplunkSearchOutcome;
import com.jmopsagent.tas.TasConnector;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class InvestigationOrchestrator {
    private static final int MAX_TRACKING_EVIDENCE_ITEMS = 12;
    private static final int DESIRED_DOWNSTREAM_EVIDENCE_RESERVE = 6;
    private static final Pattern STACK_CLASS = Pattern.compile("(?:at\\s+)?([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)+)\\.[\\w$<>]+\\(");
    private static final Pattern ISO_TIMESTAMP = Pattern.compile(
            "\\b\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?(?:Z|[+-]\\d{2}:\\d{2})\\b");

    private final InvestigationStateService state;
    private final SanitizedEvidenceStore evidenceStore;
    private final EnvironmentPolicy environmentPolicy;
    private final ConnectorEvidenceMapper mapper;
    private final ServiceRegistry serviceRegistry;
    private final ServiceRegistryEnricher serviceRegistryEnricher;
    private final HistoricalIncidentMatcher historicalMatcher;
    private final KubernetesConnector kubernetes;
    private final TasConnector tas;
    private final SplunkConnector splunk;
    private final JenkinsConnector jenkins;
    private final GitLabConnector gitLab;
    private final List<DependencyConnector> dependencies;
    private final ClaudeCodeClient claude;
    private final BoundedReasoningEvidenceMapper reasoningEvidenceMapper;
    private final OperationalHeuristics heuristics;
    private final InvestigationLimitsProperties limits;
    private final ObjectMapper objectMapper;

    public InvestigationOrchestrator(InvestigationStateService state,
                                     SanitizedEvidenceStore evidenceStore,
                                     EnvironmentPolicy environmentPolicy,
                                     ConnectorEvidenceMapper mapper,
                                     ServiceRegistry serviceRegistry,
                                     ServiceRegistryEnricher serviceRegistryEnricher,
                                     HistoricalIncidentMatcher historicalMatcher,
                                     KubernetesConnector kubernetes,
                                     TasConnector tas,
                                     SplunkConnector splunk,
                                     JenkinsConnector jenkins,
                                     GitLabConnector gitLab,
                                     List<DependencyConnector> dependencies,
                                     ClaudeCodeClient claude,
                                     BoundedReasoningEvidenceMapper reasoningEvidenceMapper,
                                     OperationalHeuristics heuristics,
                                     InvestigationLimitsProperties limits,
                                     ObjectMapper objectMapper) {
        this.state = state;
        this.evidenceStore = evidenceStore;
        this.environmentPolicy = environmentPolicy;
        this.mapper = mapper;
        this.serviceRegistry = serviceRegistry;
        this.serviceRegistryEnricher = serviceRegistryEnricher;
        this.historicalMatcher = historicalMatcher;
        this.kubernetes = kubernetes;
        this.tas = tas;
        this.splunk = splunk;
        this.jenkins = jenkins;
        this.gitLab = gitLab;
        this.dependencies = dependencies;
        this.claude = claude;
        this.reasoningEvidenceMapper = reasoningEvidenceMapper;
        this.heuristics = heuristics;
        this.limits = limits;
        this.objectMapper = objectMapper;
    }

    public void investigate(UUID investigationId) {
        Investigation initial = state.snapshotWithEvidence(investigationId);
        if (initial.getStatus().isTerminal()) return;
        Context context = new Context(initial, limits);
        try {
            state.transition(investigationId, InvestigationStatus.DISCOVERING,
                    initial.getType() == InvestigationType.TRACKING_ID
                            ? "Searching for the first meaningful failure in the tracking path"
                            : "Resolving service identity and investigation scope");

            if (initial.getType() == InvestigationType.TRACKING_ID) {
                discoverTrackingPath(context);
            } else {
                resolveService(context, initial.getService());
            }
            if (context.service == null) {
                ReasoningDecision unknown = unknownDecision("The tracking evidence did not identify a failing service.");
                state.complete(investigationId, unknown, context.limitation());
                return;
            }

            state.transition(investigationId, InvestigationStatus.COLLECTING_EVIDENCE,
                    "Collecting bounded evidence for " + context.service);
            collectOperationalEvidence(context);
            collectHistoricalEvidence(context);
            analyze(context, false);
        } catch (LimitReachedException ex) {
            completeAtLimit(context, ex.getMessage());
        } catch (RuntimeException ex) {
            state.fail(investigationId, "Investigation failed safely (" + ex.getClass().getSimpleName() + ")");
        }
    }

    public void investigateCode(UUID investigationId) {
        Investigation snapshot = state.snapshotWithEvidence(investigationId);
        if (snapshot.getStatus() != InvestigationStatus.CODE_INVESTIGATION) {
            throw new IllegalStateException("Code investigation was not explicitly started");
        }
        Context context = new Context(snapshot, limits);
        context.service = snapshot.getService();
        if (context.service == null) {
            state.completeCodeInvestigationWithLimitation(investigationId, "A localized service was unavailable");
            return;
        }
        try {
            state.note(investigationId, InvestigationEventType.NOTE,
                    "Inspecting the exact deployed revision through read-only GitLab APIs");
            collectCodeEvidence(context);
            analyze(context, true);
        } catch (LimitReachedException ex) {
            completeAtLimit(context, ex.getMessage());
        } catch (DeployedRevisionUnavailableException ex) {
            state.completeCodeInvestigationWithLimitation(investigationId, ex.getMessage());
        } catch (RuntimeException ex) {
            state.completeCodeInvestigationWithLimitation(investigationId,
                    "Read-only code evidence was unavailable (" + ex.getClass().getSimpleName() + ")");
        }
    }

    private void discoverTrackingPath(Context context) {
        checkLimits(context);
        Instant searchEnd = Instant.now();
        Duration maximumWindow = limits.getTrackingSearchWindow();
        Duration initialWindow = maximumWindow.compareTo(Duration.ofHours(4)) < 0
                ? maximumWindow : Duration.ofHours(4);
        EvidenceQuery query = new EvidenceQuery(searchEnd.minus(initialWindow), searchEnd,
                Math.min(limits.getMaxLogEvents(), 500), 200_000);
        SplunkSearchResult result = splunkSearch(context,
                permit -> splunk.searchByTrackingIdDetailed(
                        context.investigation.getTrackingId(), context.environment, query, permit));
        if (context.lastSplunkOutcome == SplunkSearchOutcome.NO_DATA
                && maximumWindow.compareTo(initialWindow) > 0) {
            state.note(context.id, InvestigationEventType.NOTE,
                    "No tracking events were found in the initial window; expanding to the configured maximum");
            EvidenceQuery expanded = new EvidenceQuery(searchEnd.minus(maximumWindow), searchEnd,
                    Math.min(limits.getMaxLogEvents(), 500), 200_000);
            result = splunkSearch(context, permit -> splunk.searchByTrackingIdDetailed(
                    context.investigation.getTrackingId(), context.environment, expanded, permit));
        }
        List<TraceEvent> trace = normalizeTraceEvents(context, result.traceEvents()).stream()
                .sorted(Comparator.comparing(TraceEvent::timestamp, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        context.traceEvents.addAll(trace);
        for (TraceEvent event : trace) {
            allowService(context, event.service());
            allowService(context, event.downstreamService());
        }
        if (result.truncated()) {
            context.addLimitation("Splunk tracking results were truncated to the configured bound");
        }

        Optional<TraceEvent> firstFailure = trace.stream().filter(this::isFailure).findFirst();
        if (firstFailure.isPresent()) {
            TraceEvent failure = firstFailure.get();
            String candidate = failure.downstreamService() != null && !failure.downstreamService().isBlank()
                    ? failure.downstreamService() : failure.service();
            context.failureAt = failure.timestamp();
            resolveService(context, candidate);
            if (context.service != null) {
                String path = summarizePath(trace);
                state.localizeService(context.id, context.service,
                        "Failure localized to " + context.service + (path.isBlank() ? "" : ". Call path: " + path));
            }
        } else {
            state.note(context.id, InvestigationEventType.ANALYSIS,
                    "No HTTP 5xx or failed trace event was found in the bounded tracking window");
        }
        persistRepresentativeTrackingEvidence(context, result.evidence(), trace, firstFailure.orElse(null));
    }

    private void persistRepresentativeTrackingEvidence(Context context, List<ConnectorEvidence> connectorEvidence,
            List<TraceEvent> trace, TraceEvent firstFailure) {
        int available = limits.getMaxEvidenceItems() - context.evidenceCount;
        int reserve = available > 1
                ? Math.min(DESIRED_DOWNSTREAM_EVIDENCE_RESERVE, Math.max(1, available / 2)) : 0;
        int budget = Math.min(MAX_TRACKING_EVIDENCE_ITEMS, Math.max(0, available - reserve));

        int connectorTarget = connectorEvidence.isEmpty() || budget == 0
                ? 0 : Math.min(connectorEvidence.size(), Math.max(1, budget / 3));
        int traceTarget = Math.min(trace.size(), Math.max(0, budget - connectorTarget));
        if (!trace.isEmpty() && budget > 0 && traceTarget == 0) {
            connectorTarget = Math.max(0, connectorTarget - 1);
            traceTarget = 1;
        }

        int unused = budget - connectorTarget - traceTarget;
        int additionalTrace = Math.min(unused, trace.size() - traceTarget);
        traceTarget += additionalTrace;
        unused -= additionalTrace;
        connectorTarget += Math.min(unused, connectorEvidence.size() - connectorTarget);

        int failureIndex = firstFailure == null ? -1 : trace.indexOf(firstFailure);
        List<TraceEvent> retainedTrace = representativeItems(trace, traceTarget, failureIndex);
        int matchingEvidenceIndex = matchingEvidenceIndex(connectorEvidence, firstFailure);
        List<ConnectorEvidence> retainedConnectorEvidence = representativeItems(
                connectorEvidence, connectorTarget, matchingEvidenceIndex);

        retainedTrace.forEach(event -> append(context,
                mapper.trace(event, context.investigation.getEnvironment())));
        retainedConnectorEvidence.forEach(item -> append(context, mapper.map(item)));

        int retained = retainedTrace.size() + retainedConnectorEvidence.size();
        int availableRepresentations = trace.size() + connectorEvidence.size();
        if (retained > 0) {
            state.note(context.id, InvestigationEventType.EVIDENCE_COLLECTED,
                    "Splunk tracking search retained " + retained + " representative bounded item(s)");
        }
        if (retained < availableRepresentations) {
            String limitation = "Tracking evidence was reduced to a representative subset to reserve capacity for downstream triage";
            context.addLimitation(limitation);
            state.note(context.id, InvestigationEventType.NOTE, limitation);
        }
    }

    private static int matchingEvidenceIndex(List<ConnectorEvidence> evidence, TraceEvent failure) {
        if (failure == null) return -1;
        for (int index = 0; index < evidence.size(); index++) {
            ConnectorEvidence item = evidence.get(index);
            if (java.util.Objects.equals(item.timestamp(), failure.timestamp())
                    || java.util.Objects.equals(validatedServiceIdentifier(item.service()), failure.downstreamService())) {
                return index;
            }
        }
        return -1;
    }

    private static <T> List<T> representativeItems(List<T> items, int limit, int preferredIndex) {
        if (limit <= 0 || items.isEmpty()) return List.of();
        if (items.size() <= limit) return List.copyOf(items);

        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        addRepresentativeIndex(indexes, preferredIndex, items.size(), limit);
        addRepresentativeIndex(indexes, 0, items.size(), limit);
        addRepresentativeIndex(indexes, items.size() - 1, items.size(), limit);
        if (limit > 1) {
            for (int slot = 1; slot < limit && indexes.size() < limit; slot++) {
                int index = (int) Math.round((double) slot * (items.size() - 1) / (limit - 1));
                addRepresentativeIndex(indexes, index, items.size(), limit);
            }
        }
        for (int index = 0; index < items.size() && indexes.size() < limit; index++) {
            addRepresentativeIndex(indexes, index, items.size(), limit);
        }
        return indexes.stream().sorted().map(items::get).toList();
    }

    private static void addRepresentativeIndex(Set<Integer> indexes, int index, int size, int limit) {
        if (indexes.size() < limit && index >= 0 && index < size) indexes.add(index);
    }

    private void resolveService(Context context, String candidate) {
        if (candidate == null || candidate.isBlank()) return;
        String normalized = validatedServiceIdentifier(candidate);
        if (normalized == null) {
            recordInvalidTraceIdentifier(context);
            return;
        }
        Optional<ServiceDefinition> definition = serviceRegistry.resolve(normalized);
        boolean registered = definition.isPresent();
        boolean enrichmentNeeded = definition.isEmpty() || definition
                .map(value -> registryEntryNeedsEnrichment(value, context.investigation.getEnvironment()))
                .orElse(true);
        if (enrichmentNeeded) {
            state.note(context.id, InvestigationEventType.NOTE, registered
                    ? "Checking bounded read-only discovery for missing service registry metadata"
                    : "Attempting bounded read-only service discovery for an unregistered service");
            ServiceDefinition before = definition.orElse(null);
            try {
                ServiceDefinition enriched = serviceRegistryEnricher.enrich(
                        normalized, context.investigation.getEnvironment());
                definition = Optional.of(enriched);
                if (before == null || registryMetadataChanged(before, enriched)) {
                    state.note(context.id, InvestigationEventType.EVIDENCE_COLLECTED,
                            "Service registry was enriched from an exact connector match");
                }
            } catch (IllegalArgumentException ignored) {
                definition = serviceRegistry.resolve(normalized);
                if (!registered) {
                    state.note(context.id, InvestigationEventType.NOTE, definition.isPresent()
                            ? "Service registry retained a partial exact discovery result"
                            : "No exact service discovery match was found; retaining the validated input name");
                } else {
                    state.note(context.id, InvestigationEventType.NOTE,
                            "Existing service registry values were retained after bounded discovery found no exact update");
                }
            }
        }
        if (definition.isPresent() && validatedServiceIdentifier(definition.get().service()) == null) {
            recordInvalidTraceIdentifier(context);
            definition = Optional.empty();
        }
        context.definition = definition.orElse(null);
        context.service = definition.map(ServiceDefinition::service).orElse(normalized);
        allowService(context, context.service);
        definition.ifPresent(value -> value.aliases().forEach(alias -> allowService(context, alias)));
        if (context.investigation.getService() == null || !context.investigation.getService().equals(context.service)) {
            state.localizeService(context.id, context.service,
                    definition.isPresent() ? "Service resolved through the registry" : "Service retained as an unconfirmed registry name");
        }
    }

    private static boolean registryEntryNeedsEnrichment(ServiceDefinition definition,
            DeploymentEnvironment environment) {
        if (definition.attributeValue("gitlab.repository").isEmpty()
                || definition.attributeForEnvironment("jenkins.job", environment).isEmpty()
                || definition.attributeForEnvironment("jenkins.controller", environment).isEmpty()) {
            return true;
        }

        String platform = definition.attributeForEnvironment("runtime.platform", environment)
                .orElse("").trim().toUpperCase(Locale.ROOT);
        if (platform.equals("TAS") || platform.equals("CF")) return false;
        boolean tasOnlyByConvention = platform.isBlank()
                && definition.attributeValue("tas.appPattern").isPresent()
                && definition.attributeForEnvironment("eks.namespace", environment).isEmpty()
                && definition.attributeForEnvironment("eks.deployment", environment).isEmpty()
                && definition.attributeForEnvironment("eks.service", environment).isEmpty();
        if (tasOnlyByConvention) return false;

        return definition.attributeForEnvironment("eks.namespace", environment).isEmpty()
                || definition.attributeForEnvironment("eks.deployment", environment).isEmpty()
                || definition.attributeForEnvironment("eks.service", environment).isEmpty();
    }

    private static boolean registryMetadataChanged(ServiceDefinition before, ServiceDefinition after) {
        return !before.attributes().equals(after.attributes())
                || !before.aliasesWithProvenance().equals(after.aliasesWithProvenance());
    }

    private void collectOperationalEvidence(Context context) {
        checkLimits(context);
        boolean tasWorkload = usesTas(context);
        if (tasWorkload) {
            appendConnectorEvidence(context, safeList(context, "TAS application status",
                    () -> tas.getApplicationStatus(context.service, context.environment)), "TAS runtime");
        } else {
            appendConnectorEvidence(context, safeList(context, "Kubernetes workload health",
                    () -> kubernetes.getWorkloadHealth(context.service, context.environment)), "Kubernetes health");
            appendConnectorEvidence(context, safeList(context, "Kubernetes pod events",
                    () -> kubernetes.getRecentPodEvents(context.service, context.environment, recentQuery(context))),
                    "Kubernetes events");
        }

        safeOptional(context, "Jenkins latest deployment",
                () -> jenkins.getLatestDeployment(context.service, context.environment))
                .ifPresent(deployment -> recordLatestDeploymentAttempt(context, deployment));

        List<ConnectorEvidence> errors;
        if (tasWorkload) {
            SplunkSearchResult splunkErrors = context.failureAt == null
                    ? splunkSearch(context, permit -> splunk.searchErrorsForServiceDetailed(
                            context.service, context.environment, recentQuery(context), permit))
                    : splunkSearch(context, permit -> splunk.searchAroundTimestampDetailed(
                            context.service, context.environment, context.failureAt, around(context.failureAt), permit));
            errors = splunkErrors.evidence();
            appendConnectorEvidence(context, errors, "Splunk service errors");
            if (splunkErrors.truncated()) context.addLimitation("Service log results were truncated");
        } else {
            errors = safeList(context, "Kubernetes application logs",
                    () -> kubernetes.getRecentPodLogs(context.service, context.environment,
                            context.failureAt == null ? recentQuery(context) : around(context.failureAt)));
            appendConnectorEvidence(context, errors, "Kubernetes application logs");
        }
        if (context.failureAt == null) {
            errors.stream().map(ConnectorEvidence::timestamp).filter(java.util.Objects::nonNull)
                    .min(Comparator.naturalOrder()).ifPresent(timestamp -> {
                        context.failureAt = timestamp;
                        state.note(context.id, InvestigationEventType.ANALYSIS,
                                "Established the first observed error time at " + timestamp);
                    });
        }

        List<EvidenceItem> currentEvidence = state.snapshotWithEvidence(context.id).getEvidenceItems();
        if (evidenceIndicatesDependency(currentEvidence)) {
            collectDependencyEvidence(context, context.service);
        }

        if (tasWorkload) {
            appendConnectorEvidence(context, safeList(context, "TAS environment metadata",
                    () -> tas.getEnvironmentMetadata(context.service, context.environment)), "TAS configuration");
        } else {
            appendConnectorEvidence(context, safeList(context, "Kubernetes effective configuration",
                    () -> kubernetes.getEffectiveConfiguration(context.service, context.environment)),
                    "Kubernetes configuration");
        }
        collectRecentChanges(context);
    }

    private void collectRecentChanges(Context context) {
        List<DeploymentInfo> builds = safeList(context, "Jenkins deployment history",
                () -> jenkins.getLastBuilds(context.service, context.environment, 10));
        DeploymentInfo deployed = resolveLastSuccessfulDeployment(context, builds);
        String deployedSha = deployedSha(context);
        if (deployed == null || deployedSha == null) {
            context.addLimitation("Exact Git SHA for the last successful deployment was unavailable; source changes were not inspected");
            state.note(context.id, InvestigationEventType.NOTE,
                    "Skipped source comparison because no successful deployed SHA could be established");
            return;
        }
        List<CommitChange> changes;
        Optional<String> previousSuccessfulSha = successfulDeployments(builds).stream()
                .filter(build -> !sameBuild(build, deployed))
                .map(DeploymentInfo::commitSha)
                .map(this::validatedRevision)
                .flatMap(Optional::stream)
                .filter(sha -> !sha.equals(deployedSha))
                .findFirst();
        if (previousSuccessfulSha.isPresent()) {
            changes = safeList(context, "GitLab revision comparison",
                    () -> gitLab.compareRevisions(context.service, previousSuccessfulSha.get(), deployedSha, 60_000));
        } else {
            changes = safeList(context, "GitLab recent commits",
                    () -> gitLab.getCommits(context.service, deployedSha, 5));
        }
        for (CommitChange change : changes) append(context,
                mapper.change(change, context.service, context.investigation.getEnvironment()));
        if (!changes.isEmpty()) state.note(context.id, InvestigationEventType.EVIDENCE_COLLECTED,
                "Compared recent source/configuration changes at deployed SHA " + shortSha(deployedSha));
    }

    private void collectHistoricalEvidence(Context context) {
        Investigation snapshot = state.snapshotWithEvidence(context.id);
        String signature = snapshot.getEvidenceItems().stream()
                .filter(item -> item.getEvidenceType() == EvidenceType.ERROR_LOG
                        || item.getEvidenceType() == EvidenceType.POD_LOG
                        || item.getEvidenceType() == EvidenceType.CONFIGURATION)
                .map(item -> item.getSummary() + " " + item.getSanitizedContent())
                .reduce("", (left, right) -> bounded(left + " " + right, 4_000));
        List<HistoricalIncidentMatch> matches = historicalMatcher.findMatches(
                HistoricalIncidentQuery.forService(context.service, context.investigation.getEnvironment(), signature),
                limits.getMaxHistoricalIncidents());
        for (HistoricalIncidentMatch match : matches) {
            String advisory = match.asAdvisorySummary();
            context.history.add(advisory);
            append(context, new EvidenceDraft(EvidenceSource.HISTORICAL_INCIDENT, EvidenceType.HISTORICAL_MATCH,
                    match.completedAt(), context.service, context.investigation.getEnvironment(),
                    "Advisory historical match", advisory, null, json(match),
                    match.confirmed() ? EvidenceReliability.MEDIUM : EvidenceReliability.LOW));
        }
        if (!matches.isEmpty()) state.note(context.id, InvestigationEventType.EVIDENCE_COLLECTED,
                "Included " + matches.size() + " advisory historical match(es)");
    }

    private void analyze(Context context, boolean codeStage) {
        ClaudeInvocationResult lastInvocation = null;
        ReasoningDecision lastDecision = null;
        for (int iteration = 1; iteration <= limits.getMaxClaudeIterations(); iteration++) {
            checkLimits(context);
            state.transition(context.id, InvestigationStatus.ANALYZING,
                    "Analyzing sanitized evidence (iteration " + iteration + " of " + limits.getMaxClaudeIterations() + ")");
            Investigation snapshot = state.snapshotWithEvidence(context.id);
            List<ReasoningEvidence> evidence = reasoningEvidenceMapper.map(snapshot.getEvidenceItems());
            ClaudeReasoningRequest request = new ClaudeReasoningRequest(context.id,
                    codeStage ? "CODE_INVESTIGATION" : snapshot.getType().name(), context.service,
                    snapshot.getEnvironment().name(), snapshot.getTrackingId(), snapshot.getUserProblem(), iteration,
                    evidence, context.history, snapshot.getClaudeSessionId(), iteration == limits.getMaxClaudeIterations());
            lastInvocation = claude.analyze(request);
            state.recordClaude(context.id, lastInvocation);
            if (!lastInvocation.successful()) {
                ReasoningDecision fallback = heuristics.bestEffort(snapshot.getEvidenceItems(),
                        "Claude Code was unavailable or returned an invalid response; deterministic analysis was used.");
                state.complete(context.id, fallback, context.limitation());
                return;
            }
            lastDecision = lastInvocation.decision();
            if (lastDecision.status() == ReasoningStatus.COMPLETE
                    || lastDecision.status() == ReasoningStatus.CODE_INVESTIGATION_RECOMMENDED) {
                state.complete(context.id, lastDecision, context.limitation());
                return;
            }
            if (iteration == limits.getMaxClaudeIterations()) break;
            state.transition(context.id, InvestigationStatus.NEEDS_MORE_EVIDENCE,
                    "Reasoning requested additional approved evidence");
            int before = context.evidenceCount;
            for (NextEvidenceRequest evidenceRequest : lastDecision.nextEvidenceRequests()) {
                collectRequestedEvidence(context, evidenceRequest, codeStage);
            }
            if (context.evidenceCount == before) {
                context.addLimitation("No new approved evidence could be collected");
                break;
            }
        }
        String limit = "Maximum Claude iterations reached";
        context.addLimitation(limit);
        ReasoningDecision conclusion = lastDecision == null
                ? heuristics.bestEffort(state.snapshotWithEvidence(context.id).getEvidenceItems(), limit)
                : new ReasoningDecision(ReasoningStatus.COMPLETE, lastDecision.summary(), lastDecision.hypotheses(),
                        List.of(), lastDecision.rootCauseCategory(), lastDecision.recommendedActions());
        state.note(context.id, InvestigationEventType.LIMIT_REACHED, limit);
        state.complete(context.id, conclusion, context.limitation());
    }

    private void collectRequestedEvidence(Context context, NextEvidenceRequest request, boolean codeStage) {
        checkLimits(context);
        String service = approvedRequestedService(context, request.service());
        if (service == null) {
            state.note(context.id, InvestigationEventType.NOTE,
                    "Rejected an evidence request for a service outside the resolved tracking scope");
            return;
        }
        boolean tasWorkload = usesTas(service, context.investigation.getEnvironment());
        switch (request.type()) {
            case WORKLOAD_HEALTH -> {
                List<ConnectorEvidence> items = tasWorkload
                        ? safeList(context, "Requested TAS application health",
                        () -> tas.getApplicationStatus(service, context.environment))
                        : safeList(context, "Requested Kubernetes workload health",
                        () -> kubernetes.getWorkloadHealth(service, context.environment));
                appendConnectorEvidence(context, items, "Requested workload health");
            }
            case RECENT_RUNTIME_EVENTS -> {
                List<ConnectorEvidence> items = tasWorkload
                        ? safeList(context, "Requested TAS recent logs",
                        () -> tas.getRecentLogs(service, context.environment, recentQuery(context)))
                        : safeList(context, "Requested Kubernetes pod events",
                        () -> kubernetes.getRecentPodEvents(service, context.environment, recentQuery(context)));
                appendConnectorEvidence(context, items, "Requested runtime events");
            }
            case RECENT_LOGS -> {
                List<ConnectorEvidence> items = tasWorkload
                        ? safeList(context, "Requested TAS logs",
                        () -> tas.getRecentLogs(service, context.environment, recentQuery(context)))
                        : safeList(context, "Requested Kubernetes logs",
                        () -> kubernetes.getRecentPodLogs(service, context.environment, recentQuery(context)));
                appendConnectorEvidence(context, items, "Requested runtime logs");
            }
            case DEPLOYMENT_METADATA -> {
                List<ConnectorEvidence> items = tasWorkload
                        ? safeList(context, "Requested TAS application metadata",
                        () -> tas.getApplicationStatus(service, context.environment))
                        : safeList(context, "Requested Kubernetes deployment metadata",
                        () -> kubernetes.getDeploymentMetadata(service, context.environment));
                appendConnectorEvidence(context, items, "Requested deployment metadata");
            }
            case LATEST_DEPLOYMENT -> safeOptional(context, "Requested Jenkins deployment",
                    () -> jenkins.getLatestDeployment(service, context.environment))
                    .ifPresent(item -> append(context, mapper.deployment(item)));
            case RECENT_CHANGES -> collectRecentChanges(context);
            case EFFECTIVE_CONFIGURATION -> {
                List<ConnectorEvidence> items = tasWorkload
                        ? safeList(context, "Requested TAS environment metadata",
                        () -> tas.getEnvironmentMetadata(service, context.environment))
                        : safeList(context, "Requested Kubernetes effective configuration",
                        () -> kubernetes.getEffectiveConfiguration(service, context.environment));
                appendConnectorEvidence(context, items, "Requested configuration");
            }
            case SERVICE_EVENTS -> {
                if (tasWorkload) {
                    SplunkSearchResult result = splunkSearch(context,
                            permit -> splunk.searchServiceEventsDetailed(
                                    service, context.environment, recentQuery(context), permit));
                    appendSplunkEvidence(context, result, "Requested service events");
                } else {
                    appendConnectorEvidence(context, safeList(context, "Requested Kubernetes logs",
                            () -> kubernetes.getRecentPodLogs(service, context.environment, recentQuery(context))),
                            "Requested service events");
                }
            }
            case RECENT_ACTIVITY -> {
                if (tasWorkload) {
                    SplunkSearchResult result = splunkSearch(context,
                            permit -> splunk.searchRecentActivityDetailed(
                                    service, context.environment, recentQuery(context), permit));
                    appendSplunkEvidence(context, result, "Requested recent activity");
                } else {
                    appendConnectorEvidence(context, safeList(context, "Requested Kubernetes logs",
                            () -> kubernetes.getRecentPodLogs(service, context.environment, recentQuery(context))),
                            "Requested recent activity");
                }
            }
            case RECENT_BUSINESS_CALLS -> {
                if (tasWorkload) {
                    SplunkSearchResult result = splunkSearch(context,
                            permit -> splunk.searchRecentBusinessCallsDetailed(
                                    service, context.environment, recentQuery(context), permit));
                    appendSplunkEvidence(context, result, "Requested recent business calls");
                } else {
                    appendConnectorEvidence(context, safeList(context, "Requested Kubernetes logs",
                            () -> kubernetes.getRecentPodLogs(service, context.environment, recentQuery(context))),
                            "Requested recent business calls");
                }
            }
            case ERROR_PATTERNS -> {
                if (tasWorkload) {
                    SplunkSearchResult result = splunkSearch(context,
                            permit -> splunk.getErrorPatternsDetailed(
                                    service, context.environment, recentQuery(context), permit));
                    appendSplunkEvidence(context, result, "Requested error patterns");
                } else {
                    appendConnectorEvidence(context, safeList(context, "Requested Kubernetes logs",
                            () -> kubernetes.getRecentPodLogs(service, context.environment, recentQuery(context))),
                            "Requested error patterns");
                }
            }
            case DEPENDENCY_EVIDENCE -> collectDependencyEvidence(context, service);
            case HISTORICAL_INCIDENTS -> collectHistoricalEvidence(context);
            case RELEVANT_CODE_FILES -> {
                if (codeStage) collectCodeFiles(context, deployedSha(context));
                else state.note(context.id, InvestigationEventType.NOTE,
                        "Deferred source inspection until the developer selects Investigate Code");
            }
        }
    }

    private void collectDependencyEvidence(Context context, String service) {
        DependencyType type = inferDependencyType(state.snapshotWithEvidence(context.id).getEvidenceItems());
        dependencies.stream().filter(connector -> connector.supports(type)).findFirst().ifPresentOrElse(connector ->
                        appendConnectorEvidence(context, safeList(context, "Requested dependency evidence",
                                () -> connector.inspect(service, context.environment, type, recentQuery(context))),
                                "Dependency evidence"),
                () -> state.note(context.id, InvestigationEventType.NOTE,
                        "No live adapter is configured for the inferred " + type + " dependency; retained log/config evidence"));
    }

    private void collectCodeEvidence(Context context) {
        Optional<DeploymentInfo> latest = safeOptional(context, "Jenkins deployed revision",
                () -> jenkins.getLatestDeployment(context.service, context.environment));
        latest.ifPresent(item -> recordLatestDeploymentAttempt(context, item));
        if (context.latestDeployment == null) {
            List<DeploymentInfo> builds = safeList(context, "Jenkins successful deployment history",
                    () -> jenkins.getLastBuilds(context.service, context.environment, 10));
            resolveLastSuccessfulDeployment(context, builds);
        }
        String sha = deployedSha(context);
        if (sha == null) throw new DeployedRevisionUnavailableException(
                "Exact Git SHA for a successful deployment is unavailable; no source code was inspected");
        collectCodeFiles(context, sha);
        collectRecentChanges(context);
    }

    private void collectCodeFiles(Context context, String sha) {
        if (sha == null) return;
        List<String> tree = safeList(context, "GitLab repository tree",
                () -> gitLab.getRepositoryTree(context.service, sha, "", 250));
        String corpus = state.snapshotWithEvidence(context.id).getEvidenceItems().stream()
                .map(item -> item.getSanitizedContent() + " " + item.getSummary())
                .reduce("", (left, right) -> bounded(left + " " + right, 100_000)).toLowerCase(Locale.ROOT);
        Set<String> namedClasses = stackClasses(corpus);
        List<String> relevant = tree.stream().filter(this::isInspectableCodePath)
                .sorted(Comparator.comparingInt(path -> relevance(path, corpus, namedClasses)))
                .limit(limits.getMaxCodeFiles()).toList();
        for (String path : relevant) {
            checkLimits(context);
            safeOptional(context, "GitLab source file",
                    () -> gitLab.getFileContent(context.service, sha, path, 40_000))
                    .ifPresent(content -> append(context, mapper.sourceFile(context.service,
                            context.investigation.getEnvironment(), sha, path, content)));
        }
        state.note(context.id, InvestigationEventType.EVIDENCE_COLLECTED,
                "Inspected " + relevant.size() + " bounded file(s) at deployed SHA " + shortSha(sha));
    }

    private String deployedSha(Context context) {
        if (context.latestDeployment == null || !isSuccessfulDeployment(context.latestDeployment)) return null;
        return validatedRevision(context.latestDeployment.commitSha()).orElse(null);
    }

    private void recordLatestDeploymentAttempt(Context context, DeploymentInfo deployment) {
        context.latestDeploymentAttempt = deployment;
        append(context, mapper.deployment(deployment));
        if (isSuccessfulDeployment(deployment)) {
            context.latestDeployment = deployment;
        } else {
            context.latestDeployment = null;
            state.note(context.id, InvestigationEventType.ANALYSIS,
                    "Latest Jenkins build was not successful and was not treated as the deployed revision");
        }
    }

    private DeploymentInfo resolveLastSuccessfulDeployment(Context context, List<DeploymentInfo> builds) {
        if (context.latestDeployment != null) return context.latestDeployment;
        Optional<DeploymentInfo> resolved = successfulDeployments(builds).stream().findFirst();
        if (resolved.isEmpty()) return null;
        context.latestDeployment = resolved.get();
        if (context.latestDeploymentAttempt == null || !sameBuild(context.latestDeploymentAttempt, resolved.get())) {
            append(context, mapper.deployment(resolved.get()));
            state.note(context.id, InvestigationEventType.ANALYSIS,
                    "Resolved the last successful deployed revision from Jenkins history at SHA "
                            + shortSha(validatedRevision(resolved.get().commitSha()).orElse(null)));
        }
        return resolved.get();
    }

    private boolean isSuccessfulDeployment(DeploymentInfo deployment) {
        if (deployment == null || !"SUCCESS".equalsIgnoreCase(deployment.result())) return false;
        return !"false".equalsIgnoreCase(deployment.metadata().get("deployed"));
    }

    private List<DeploymentInfo> successfulDeployments(List<DeploymentInfo> builds) {
        return builds.stream().filter(this::isSuccessfulDeployment)
                .sorted(Comparator.comparingLong(DeploymentInfo::buildNumber).reversed())
                .toList();
    }

    private boolean sameBuild(DeploymentInfo left, DeploymentInfo right) {
        return left != null && right != null && left.buildNumber() == right.buildNumber()
                && java.util.Objects.equals(left.jobName(), right.jobName());
    }

    private Optional<String> validatedRevision(String revision) {
        try {
            return Optional.of(ConnectorInputValidator.revision(revision));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private SplunkSearchResult splunkSearch(
            Context context, Function<SplunkSearchPermit, SplunkConnectorResult> action) {
        checkLimits(context);
        SplunkSearchPermit permit = () -> {
            checkLimits(context);
            if (context.splunkSearches >= limits.getMaxSplunkSearches()) return false;
            boolean reserved = state.tryReserveSplunkSearch(context.id, limits.getMaxSplunkSearches());
            if (reserved) context.splunkSearches++;
            return reserved;
        };
        try {
            SplunkConnectorResult value = action.apply(permit);
            if (value == null) {
                context.lastSplunkOutcome = SplunkSearchOutcome.REMOTE_FAILURE;
                recordSplunkOutcome(context, SplunkSearchOutcome.REMOTE_FAILURE);
                return emptySplunkResult();
            }
            if (value.outcome() == SplunkSearchOutcome.LIMIT_REACHED) {
                context.lastSplunkOutcome = value.outcome();
                throw new LimitReachedException("Maximum Splunk searches reached");
            }
            context.lastSplunkOutcome = value.outcome();
            recordSplunkOutcome(context, value.outcome());
            return value.result();
        } catch (LimitReachedException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            context.lastSplunkOutcome = SplunkSearchOutcome.REMOTE_FAILURE;
            state.note(context.id, InvestigationEventType.ERROR, "Splunk evidence could not be collected safely");
            context.addLimitation("Splunk evidence collection failed safely");
            return emptySplunkResult();
        }
    }

    private void recordSplunkOutcome(Context context, SplunkSearchOutcome outcome) {
        switch (outcome) {
            case SUCCESS -> { }
            case PARTIAL_PARSE -> context.addLimitation("Some Splunk results could not be parsed");
            case NO_DATA -> state.note(context.id, InvestigationEventType.NOTE,
                    "Splunk search completed with no matching events");
            case LIMIT_REACHED -> throw new LimitReachedException("Maximum Splunk searches reached");
            case UNCONFIGURED -> context.addLimitation("Splunk is not fully configured for this search");
            case UNAUTHORIZED, FORBIDDEN -> {
                context.addLimitation("Splunk rejected the configured read-only credentials");
                state.note(context.id, InvestigationEventType.ERROR,
                        "Splunk authentication or authorization prevented evidence collection");
            }
            case REDIRECT_REJECTED -> {
                context.addLimitation("Splunk returned a redirect that was rejected by the connector safety policy");
                state.note(context.id, InvestigationEventType.ERROR,
                        "Splunk evidence endpoint redirected outside the approved request flow");
            }
            case TIMEOUT -> context.addLimitation("Splunk evidence collection timed out");
            case TLS_FAILURE -> context.addLimitation("Splunk TLS certificate validation failed");
            case REMOTE_FAILURE -> context.addLimitation("Splunk returned an unavailable response");
            case PARSE_FAILURE -> context.addLimitation("Splunk returned data in an unsupported response format");
        }
    }

    private static SplunkSearchResult emptySplunkResult() {
        return new SplunkSearchResult(List.of(), List.of(), 0, false);
    }

    private <T> List<T> safeList(Context context, String operation, Supplier<List<T>> action) {
        checkLimits(context);
        try {
            List<T> value = action.get();
            return value == null ? List.of() : value;
        } catch (RuntimeException ex) {
            state.note(context.id, InvestigationEventType.ERROR, operation + " was unavailable");
            context.addLimitation(operation + " was unavailable");
            return List.of();
        }
    }

    private <T> Optional<T> safeOptional(Context context, String operation, Supplier<Optional<T>> action) {
        checkLimits(context);
        try {
            Optional<T> value = action.get();
            return value == null ? Optional.empty() : value;
        } catch (RuntimeException ex) {
            state.note(context.id, InvestigationEventType.ERROR, operation + " was unavailable");
            context.addLimitation(operation + " was unavailable");
            return Optional.empty();
        }
    }

    private void appendConnectorEvidence(Context context, Collection<ConnectorEvidence> evidence, String label) {
        int before = context.evidenceCount;
        for (ConnectorEvidence item : evidence) append(context, mapper.map(item));
        int collected = context.evidenceCount - before;
        if (collected > 0) state.note(context.id, InvestigationEventType.EVIDENCE_COLLECTED,
                label + ": collected " + collected + " bounded item(s)");
    }

    private void appendSplunkEvidence(Context context, SplunkSearchResult result, String label) {
        appendConnectorEvidence(context, result.evidence(), label);
        if (result.truncated()) {
            String limitation = label + " results were truncated to configured row or content bounds";
            context.addLimitation(limitation);
            state.note(context.id, InvestigationEventType.NOTE, limitation);
        }
    }

    private void append(Context context, EvidenceDraft draft) {
        checkLimits(context);
        if (context.evidenceCount >= limits.getMaxEvidenceItems()) {
            throw new LimitReachedException("Maximum evidence items reached");
        }
        evidenceStore.append(context.id, draft);
        context.evidenceCount++;
    }

    private void checkLimits(Context context) {
        if (Instant.now().isAfter(context.deadline)) throw new LimitReachedException("Maximum wall-clock duration reached");
        if (context.evidenceCount >= limits.getMaxEvidenceItems()) throw new LimitReachedException("Maximum evidence items reached");
    }

    private void completeAtLimit(Context context, String reason) {
        context.addLimitation(reason);
        state.note(context.id, InvestigationEventType.LIMIT_REACHED, reason);
        Investigation snapshot = state.snapshotWithEvidence(context.id);
        ReasoningDecision best = heuristics.bestEffort(snapshot.getEvidenceItems(), reason);
        state.complete(context.id, best, context.limitation());
    }

    private boolean isFailure(TraceEvent event) {
        if (event.httpStatus() != null && event.httpStatus() >= 500) return true;
        String outcome = event.outcome() == null ? "" : event.outcome().toUpperCase(Locale.ROOT);
        return outcome.contains("FAIL") || outcome.contains("ERROR");
    }

    private String summarizePath(List<TraceEvent> trace) {
        LinkedHashSet<String> nodes = new LinkedHashSet<>();
        for (TraceEvent event : trace) {
            String service = validatedServiceIdentifier(event.service());
            String downstream = validatedServiceIdentifier(event.downstreamService());
            if (service != null) nodes.add(service + (isFailure(event) ? " ✕" : " ✓"));
            if (downstream != null && isFailure(event)) nodes.add(downstream + " ✕");
        }
        return String.join(" → ", nodes);
    }

    private void allowService(Context context, String service) {
        String normalized = validatedServiceIdentifier(service);
        if (normalized != null) context.allowedServices.add(normalized);
    }

    private List<TraceEvent> normalizeTraceEvents(Context context, List<TraceEvent> events) {
        List<TraceEvent> normalized = new ArrayList<>(events.size());
        boolean invalidIdentifier = false;
        for (TraceEvent event : events) {
            String service = validatedServiceIdentifier(event.service());
            String downstream = validatedServiceIdentifier(event.downstreamService());
            invalidIdentifier |= hasInvalidIdentifier(event.service(), service)
                    || hasInvalidIdentifier(event.downstreamService(), downstream);
            normalized.add(new TraceEvent(event.timestamp(), event.trackingId(), service, event.operation(),
                    event.outcome(), event.httpStatus(), downstream, event.summary(), event.sourceUrl(), event.metadata()));
        }
        if (invalidIdentifier) recordInvalidTraceIdentifier(context);
        return List.copyOf(normalized);
    }

    private void recordInvalidTraceIdentifier(Context context) {
        String limitation = "Invalid service identifiers in tracking evidence were ignored";
        if (!context.limitations.contains(limitation)) {
            context.addLimitation(limitation);
            state.note(context.id, InvestigationEventType.NOTE, limitation);
        }
    }

    private static boolean hasInvalidIdentifier(String raw, String normalized) {
        return raw != null && !raw.isBlank() && normalized == null;
    }

    private static String validatedServiceIdentifier(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ConnectorInputValidator.service(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String approvedRequestedService(Context context, String requested) {
        if (requested == null || requested.isBlank()) return context.service;
        String normalized = requested.trim().toLowerCase(Locale.ROOT);
        if (context.allowedServices.contains(normalized)) return normalized;
        return serviceRegistry.resolve(normalized).filter(def -> context.allowedServices.contains(def.service()))
                .map(ServiceDefinition::service).orElse(null);
    }

    private DependencyType inferDependencyType(List<EvidenceItem> items) {
        String corpus = items.stream().map(item -> item.getSummary() + " " + item.getSanitizedContent())
                .reduce("", (left, right) -> left + " " + right).toLowerCase(Locale.ROOT);
        if (corpus.contains("kafka")) return DependencyType.KAFKA;
        if (corpus.contains("sqs")) return DependencyType.SQS;
        if (corpus.contains("ibm mq") || corpus.contains("mqje")) return DependencyType.IBM_MQ;
        if (corpus.contains("postgres") || corpus.contains("jdbc") || corpus.contains("database")) return DependencyType.POSTGRESQL;
        return DependencyType.DOWNSTREAM_API;
    }

    private boolean evidenceIndicatesDependency(List<EvidenceItem> items) {
        String corpus = items.stream().map(item -> item.getSummary() + " " + item.getSanitizedContent())
                .reduce("", (left, right) -> left + " " + right).toLowerCase(Locale.ROOT);
        return corpus.contains("connection refused") || corpus.contains("connection timeout")
                || corpus.contains("psqlexception") || corpus.contains("kafkaexception")
                || corpus.contains("sqs") && corpus.contains("error")
                || corpus.contains("webclientresponseexception")
                || corpus.contains("downstream api returned 500")
                || corpus.contains("returned http 5");
    }

    private boolean usesTas(Context context) {
        return usesTas(context.definition, context.investigation.getEnvironment());
    }

    private boolean usesTas(String service, DeploymentEnvironment environment) {
        return serviceRegistry.resolve(service)
                .map(definition -> usesTas(definition, environment))
                .orElse(false);
    }

    private static boolean usesTas(ServiceDefinition definition, DeploymentEnvironment environment) {
        if (definition == null) return false;
        Optional<String> configured = definition.attributeForEnvironment("runtime.platform", environment);
        if (configured.isPresent()) {
            String platform = configured.get().trim().toUpperCase(Locale.ROOT);
            if (platform.equals("TAS") || platform.equals("CF")) return true;
            if (platform.equals("KUBERNETES") || platform.equals("EKS")) return false;
        }
        return definition.attributeValue("tas.appPattern").isPresent()
                && definition.attributeForEnvironment("eks.namespace", environment).isEmpty();
    }

    private static Instant explicitFailureTime(String problem) {
        if (problem == null) return null;
        Matcher matcher = ISO_TIMESTAMP.matcher(problem);
        if (!matcher.find()) return null;
        try {
            return OffsetDateTime.parse(matcher.group()).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private EvidenceQuery recentQuery(Context context) {
        Instant end = Instant.now();
        return new EvidenceQuery(end.minus(Duration.ofMinutes(30)), end,
                Math.min(limits.getMaxLogEvents(), 500), 120_000);
    }

    private EvidenceQuery around(Instant timestamp) {
        return new EvidenceQuery(timestamp.minus(Duration.ofMinutes(10)), timestamp.plus(Duration.ofMinutes(10)),
                Math.min(limits.getMaxLogEvents(), 500), 120_000);
    }

    private Set<String> stackClasses(String corpus) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = STACK_CLASS.matcher(corpus);
        while (matcher.find() && result.size() < 30) {
            String name = matcher.group(1);
            result.add(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private boolean isInspectableCodePath(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        return value.endsWith(".java") || value.endsWith(".yml") || value.endsWith(".yaml")
                || value.endsWith(".properties");
    }

    private int relevance(String path, String corpus, Set<String> classes) {
        String lower = path.toLowerCase(Locale.ROOT);
        String filename = lower.substring(lower.lastIndexOf('/') + 1).replace(".java", "");
        if (classes.contains(filename)) return 0;
        if (corpus.contains(filename) && filename.length() > 3) return 1;
        if (lower.contains("application") || lower.contains("config")) return 2;
        return 3;
    }

    private ReasoningDecision unknownDecision(String summary) {
        return new ReasoningDecision(ReasoningStatus.COMPLETE, summary, List.of(), List.of(),
                RootCauseCategory.UNKNOWN,
                List.of("Confirm the tracking ID and retry within the configured tracking search window."));
    }

    private String shortSha(String sha) {
        return sha == null || sha.length() <= 10 ? sha : sha.substring(0, 10);
    }

    private String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(value.length() - maximum);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            return "{}";
        }
    }

    private static final class Context {
        final UUID id;
        final Investigation investigation;
        final Environment environment;
        final Instant deadline;
        final List<TraceEvent> traceEvents = new ArrayList<>();
        final Set<String> allowedServices = new LinkedHashSet<>();
        final List<String> history = new ArrayList<>();
        final List<String> limitations = new ArrayList<>();
        int evidenceCount;
        int splunkSearches;
        SplunkSearchOutcome lastSplunkOutcome;
        String service;
        Instant failureAt;
        ServiceDefinition definition;
        DeploymentInfo latestDeployment;
        DeploymentInfo latestDeploymentAttempt;

        Context(Investigation investigation, InvestigationLimitsProperties limits) {
            this.id = investigation.getId();
            this.investigation = investigation;
            this.environment = Environment.valueOf(investigation.getEnvironment().name());
            this.deadline = Instant.now().plus(limits.getMaxWallClock());
            this.evidenceCount = investigation.getEvidenceItems().size();
            this.splunkSearches = investigation.getSplunkSearchCount();
            this.service = investigation.getService();
            this.failureAt = explicitFailureTime(investigation.getUserProblem());
            if (service != null) allowedServices.add(service.toLowerCase(Locale.ROOT));
        }

        void addLimitation(String value) {
            if (value != null && !value.isBlank() && !limitations.contains(value)) limitations.add(value);
        }

        String limitation() {
            return limitations.isEmpty() ? null : String.join("; ", limitations);
        }
    }

    private static final class LimitReachedException extends RuntimeException {
        LimitReachedException(String message) { super(message); }
    }

    private static final class DeployedRevisionUnavailableException extends IllegalStateException {
        DeployedRevisionUnavailableException(String message) { super(message); }
    }
}
