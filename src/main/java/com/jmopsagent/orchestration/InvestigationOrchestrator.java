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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final InvestigationClues clues;
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
                                     ObjectMapper objectMapper,
                                     InvestigationClues clues) {
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
        this.clues = clues;
    }

    public void investigate(UUID investigationId) {
        Investigation initial = state.snapshotWithEvidence(investigationId);
        if (initial.getStatus().isTerminal()) return;
        Context context = new Context(initial, limits);
        context.explicitCurrent = clues.currentRequested(initial.getUserProblem()) && context.window == null;
        try {
            state.transition(investigationId, InvestigationStatus.DISCOVERING,
                    initial.getType() == InvestigationType.TRACKING_ID
                            ? "Searching for the first meaningful failure in the tracking path"
                            : "Resolving service identity and investigation scope");

            if (clues.latestTrackingRequested(initial.getUserProblem())) {
                if (context.service == null) {
                    state.complete(context.id, unknownDecision("Which service should I find the latest tracking ID for?"), null);
                    return;
                }
                String answer = collectLatestTrackingId(context, context.service, context.window, clues.mentionsQa(initial.getUserProblem()));
                if (!clues.diagnosisRequested(initial.getUserProblem())) {
                    state.complete(context.id, new ReasoningDecision(ReasoningStatus.COMPLETE, answer, List.of(), List.of(),
                            RootCauseCategory.UNKNOWN, List.of()), context.limitation());
                    return;
                }
                if (context.lookupSelectedAt != null && (broadPeriod(context)
                        || context.window == null && context.lookupSelectedAt.isBefore(Instant.now().minusSeconds(1800)))) {
                    adoptDiscoveredWindow(context, InvestigationClues.around(context.lookupSelectedAt, Instant.now()),
                            "Following the request selected by the latest tracking-ID lookup at " + context.lookupSelectedAt);
                }
            }
            if (initial.getService() != null) {
                resolveService(context, initial.getService());
            }
            if (context.trackingId == null) narrowRequestedPeriod(context);
            if (context.trackingId != null) discoverTrackingPath(context);
            if (context.service == null) {
                ReasoningDecision unknown = context.window == null
                        ? unknownDecision("The tracking evidence did not identify a failing service.")
                        : retrospectiveUnknown("Retained tracking evidence did not identify a failing service in the supplied incident window");
                state.complete(investigationId, unknown, context.limitation());
                return;
            }

            state.transition(investigationId, InvestigationStatus.COLLECTING_EVIDENCE,
                    "Collecting bounded evidence for " + context.service);
            if (context.window != null) recordRetrospectiveScope(context);
            collectOperationalEvidence(context);
            discoverObservedTracking(context);
            collectDownstreamServices(context);
            collectHistoricalEvidence(context);
            if (context.window != null && context.limitation() != null) {
                append(context, new EvidenceDraft(EvidenceSource.SERVICE_REGISTRY, EvidenceType.OTHER, null,
                        context.service, initial.getEnvironment(), "Historical evidence coverage gaps",
                        context.limitation(), null, null, EvidenceReliability.HIGH));
            }
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
        restoreHistoricalContext(context, snapshot);
        if (context.service == null) {
            state.completeCodeInvestigationWithLimitation(investigationId, "A localized service was unavailable");
            return;
        }
        try {
            state.note(investigationId, InvestigationEventType.NOTE,
                    context.window == null ? "Inspecting the exact deployed revision through read-only GitLab APIs"
                            : "Inspecting the historical Jenkins revision candidate through read-only GitLab APIs");
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

    /** Same semantic dispatcher for conversational evidence requests; preserves the completed diagnosis. */
    public FollowUpCollection collectFollowUpEvidence(UUID id, String question, List<NextEvidenceRequest> requests, Instant deadline, EvidenceCollectionBudget budget, boolean explicitCurrent) {
        Investigation snapshot = state.snapshotWithEvidence(id);
        Context context = new Context(snapshot, deadline, budget);
        context.explicitCurrent = explicitCurrent;
        restoreHistoricalContext(context, snapshot);
        int before = context.evidenceCount;
        String directAnswer = null;
        try {
            context.definition = context.service == null ? null : serviceRegistry.resolve(context.service).orElse(null);
            boolean scopeChanged = false;
            if (question != null) {
                var window = clues.window(question, Instant.now());
                context.explicitCurrent = window.isEmpty() && clues.currentRequested(question);
                boolean retarget = clues.latestTrackingRequested(question) || clues.targetChangeRequested(question);
                String targetService = retarget ? clues.service(question).orElse(context.service) : context.service;
                var targetEnvironment = environmentPolicy.requireAllowed(retarget
                        ? clues.environment(question).orElse(context.environment.name()) : context.environment.name());
                if (!java.util.Objects.equals(targetService, context.service) || !targetEnvironment.name().equals(context.environment.name())) {
                    state.resolveTarget(context.id, targetService, targetEnvironment);
                    context.investigation = state.snapshotWithEvidence(context.id);
                    context.service = targetService;
                    context.environment = Environment.valueOf(targetEnvironment.name());
                    context.window = null;
                    context.trackingId = null;
                    context.failureAt = null;
                    context.latestDeployment = null;
                    context.latestDeploymentAttempt = null;
                    context.historicalBuilds.clear();
                    context.historicalChangesCollected.clear();
                    context.historicalRepositoryCollected.clear();
                    context.attemptedCodeFiles.clear();
                    context.allowedServices.clear();
                    if (targetService != null) allowService(context, targetService);
                    context.definition = targetService == null ? null : serviceRegistry.resolve(targetService).orElse(null);
                    context.scopeReferenceTime = Instant.now();
                    scopeChanged = true;
                }
                if (clues.latestTrackingRequested(question)) {
                    var lookupWindow = window.orElse(question.toLowerCase(Locale.ROOT).contains("incident") ? context.window : null);
                    String answer = context.service == null ? "Which service should I find the latest tracking ID for?"
                            : collectLatestTrackingId(context, context.service, lookupWindow, clues.mentionsQa(question));
                    if (!clues.diagnosisRequested(question)) return new FollowUpCollection(context.evidenceCount - before, context.limitation(), answer, context.explicitCurrent);
                    if (context.lookupSelectedAt != null && window.isPresent()) {
                        window = Optional.of(InvestigationClues.around(context.lookupSelectedAt, Instant.now()));
                        scopeChanged = true;
                    }
                }
                if (window.isPresent() && !window.get().equals(context.window)) {
                    adoptDiscoveredWindow(context, window.get(), "Resolved incident timing from the follow-up in UTC; earlier-scope evidence is retained separately");
                    if (context.lookupSelectedAt == null && !context.investigation.isTrackingIdUserSupplied()) {
                        context.trackingId = null;
                        state.resolveTrackingId(context.id, null);
                    }
                    scopeChanged = true;
                } else if (window.isEmpty() && clues.currentRequested(question) && context.window != null) {
                    adoptDiscoveredWindow(context, null, "The follow-up explicitly asks about current conditions; earlier-scope evidence is retained separately");
                    context.trackingId = null;
                    state.resolveTrackingId(context.id, null);
                    scopeChanged = true;
                }
                if (scopeChanged && context.window != null) {
                    if (context.trackingId == null && clues.trackingId(question).isEmpty()) narrowRequestedPeriod(context);
                }
                var tracking = clues.trackingId(question);
                if (tracking.isPresent() && tracking.get().equals(context.trackingId)) {
                    state.resolveTrackingId(context.id, context.trackingId, true);
                }
                if (window.isEmpty() && tracking.isEmpty() && clues.diagnosisRequested(question)) {
                    var current = state.snapshotWithEvidence(context.id);
                    var selected = current.getEvidenceItems().stream()
                            .filter(item -> item.getId().equals(current.getActiveLookupEvidenceId()))
                            .filter(item -> item.getEvidenceType() == EvidenceType.RECENT_BUSINESS_CALLS && item.getOccurredAt() != null)
                            .findFirst();
                    if (selected.isPresent()) {
                        state.setActiveLookup(context.id, null);
                        tracking = applicationTrackingId(selected.get());
                        Instant observed = selected.get().getOccurredAt();
                        var targetWindow = observed.isBefore(Instant.now().minusSeconds(1800))
                                ? InvestigationClues.around(observed, Instant.now()) : null;
                        if (!java.util.Objects.equals(targetWindow, context.window)) {
                            adoptDiscoveredWindow(context, targetWindow, "Following the request selected by the latest tracking-ID lookup at " + observed);
                        }
                        scopeChanged = true;
                    }
                }
                if ((tracking.isPresent() && !tracking.get().equals(context.trackingId)) || (scopeChanged && context.trackingId != null)) {
                    context.trackingId = tracking.orElse(context.trackingId);
                    state.resolveTrackingId(context.id, context.trackingId, clues.trackingId(question).isPresent());
                    discoverTrackingPath(context);
                    scopeChanged = true;
                }
            }
            if (scopeChanged && context.service != null) {
                if (context.window != null) recordRetrospectiveScope(context);
                collectOperationalEvidence(context);
                discoverObservedTracking(context);
                collectDownstreamServices(context);
            }
            for (NextEvidenceRequest request : requests) collectRequestedEvidence(context, request);
            if (!requests.isEmpty()) discoverObservedTracking(context);
        } catch (LimitReachedException ex) {
            context.addLimitation(ex.getMessage());
        } catch (IllegalArgumentException ex) {
            directAnswer = ex.getMessage();
        } catch (RuntimeException ex) {
            context.addLimitation("Additional evidence was unavailable; the prior diagnosis was retained");
        }
        return new FollowUpCollection(context.evidenceCount - before, context.limitation(), directAnswer, context.explicitCurrent);
    }

    public record FollowUpCollection(int collectedItems, String limitation, String directAnswer, boolean explicitCurrent) {}

    private String collectLatestTrackingId(Context context, String service, com.jmopsagent.domain.IncidentWindow window, boolean qa) {
        state.beginTrackingLookup(context.id);
        context.trackingId = null;
        checkLimits(context);
        Instant end = window == null ? Instant.now() : window.end();
        EvidenceQuery query = new EvidenceQuery(window == null ? end.minus(limits.getTrackingSearchWindow()) : window.start(), end, 5, 12_000);
        SplunkSearchResult result = latestTrackingSearch(context, service, query);
        if (window == null && context.lastSplunkOutcome == SplunkSearchOutcome.NO_DATA
                && limits.getHistoryDiscoveryWindow().compareTo(limits.getTrackingSearchWindow()) > 0
                && context.splunkSearches < context.budget.searches()) {
            query = new EvidenceQuery(end.minus(limits.getHistoryDiscoveryWindow()), query.from(), 5, 12_000);
            result = latestTrackingSearch(context, service, query);
        }
        EvidenceQuery searched = query;
        var latest = result.evidence().stream()
                .filter(item -> service.equals(item.service()) && item.environment() == context.environment)
                .filter(item -> item.source() == com.jmopsagent.connector.EvidenceSource.SPLUNK)
                .filter(item -> item.type() == com.jmopsagent.connector.EvidenceType.RECENT_BUSINESS_CALLS)
                .filter(item -> item.timestamp() != null && !item.timestamp().isBefore(searched.from()) && item.timestamp().isBefore(searched.to()))
                .filter(item -> "application-log".equals(item.metadata().get("sourceFormat")))
                .filter(item -> item.metadata().getOrDefault("httpStatus", "").matches("[1-5][0-9]{2}"))
                .filter(item -> validatedTrackingId(item.metadata().get("trackingId")).isPresent())
                .sorted(Comparator.comparing(ConnectorEvidence::timestamp).reversed()
                        .thenComparing(item -> item.metadata().get("trackingId"))).findFirst();
        String scope = service + " in " + context.environment + " between " + searched.from() + " and " + end + " UTC";
        String qualification = qa ? " QA was interpreted as the TEST request environment; the logs do not identify who ran a QA test." : "";
        if (qa && context.environment != Environment.TEST) qualification = " QA-run ownership is not established by these request logs.";
        String answer;
        if (latest.isPresent()) {
            ConnectorEvidence selected = latest.get();
            context.trackingId = ConnectorInputValidator.trackingId(selected.metadata().get("trackingId"));
            context.lookupSelectedAt = selected.timestamp();
            state.resolveTrackingId(context.id, context.trackingId);
            answer = "Most recent observed application tracking ID: " + context.trackingId + ", at " + selected.timestamp()
                    + ", service " + service + ", environment " + context.environment + ". Searched " + scope + "." + qualification;
            if (result.truncated() || context.lastSplunkOutcome == SplunkSearchOutcome.PARTIAL_PARSE)
                answer += " The returned evidence is bounded or partially parsed; completeness is not guaranteed.";
            Map<String, Object> metadata = new java.util.LinkedHashMap<>(selected.metadata());
            metadata.put("lookupSelected", true);
            metadata.put("queryFrom", searched.from().toString());
            metadata.put("queryTo", end.toString());
            UUID lookupEvidence = append(context, new EvidenceDraft(EvidenceSource.SPLUNK, EvidenceType.RECENT_BUSINESS_CALLS, selected.timestamp(),
                    service, context.investigation.getEnvironment(), "Latest observed tracking ID", answer,
                    selected.sourceUrl() == null ? null : selected.sourceUrl().toString(), json(metadata), EvidenceReliability.HIGH));
            state.setActiveLookup(context.id, lookupEvidence);
        } else {
            answer = (context.lastSplunkOutcome == SplunkSearchOutcome.NO_DATA || context.lastSplunkOutcome == SplunkSearchOutcome.SUCCESS
                    ? "No qualifying application tracking ID was found for " : "Tracking-ID evidence was unavailable for ") + scope + "." + qualification;
            append(context, new EvidenceDraft(EvidenceSource.SPLUNK, EvidenceType.OTHER, null, service, context.investigation.getEnvironment(),
                    "Tracking lookup coverage", answer, null, null, EvidenceReliability.HIGH));
        }
        return answer;
    }

    private Optional<String> validatedTrackingId(String value) {
        if (value == null || Set.of("unknown", "unavailable", "null", "missing").contains(value.toLowerCase(Locale.ROOT))) return Optional.empty();
        try { return Optional.of(ConnectorInputValidator.trackingId(value)); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    private SplunkSearchResult latestTrackingSearch(Context context, String service, EvidenceQuery query) {
        return splunkSearch(context, permit -> splunk.searchLatestTrackingIdDetailed(service, context.environment, query, permit));
    }

    private void discoverTrackingPath(Context context) {
        checkLimits(context);
        if (context.trackingSearched || context.trackingId == null) return;
        context.trackingSearched = true;
        Instant searchEnd = Instant.now();
        Duration maximumWindow = limits.getTrackingSearchWindow();
        if (context.explicitCurrent && maximumWindow.compareTo(Duration.ofMinutes(30)) > 0) maximumWindow = Duration.ofMinutes(30);
        Duration initialWindow = maximumWindow.compareTo(Duration.ofHours(4)) < 0
                ? maximumWindow : Duration.ofHours(4);
        EvidenceQuery query = context.window != null ? incidentQuery(context) : new EvidenceQuery(searchEnd.minus(initialWindow), searchEnd,
                Math.min(limits.getMaxLogEvents(), 500), 200_000);
        SplunkSearchResult result = splunkSearch(context,
                permit -> splunk.searchByTrackingIdDetailed(
                        context.trackingId, context.environment, query, permit));
        Instant searchedFrom = query.from();
        if (context.window == null && context.lastSplunkOutcome == SplunkSearchOutcome.NO_DATA
                && maximumWindow.compareTo(initialWindow) > 0) {
            state.note(context.id, InvestigationEventType.NOTE,
                    "No tracking events were found in the initial window; expanding to the configured maximum");
            EvidenceQuery expanded = new EvidenceQuery(searchEnd.minus(maximumWindow), searchEnd,
                    Math.min(limits.getMaxLogEvents(), 500), 200_000);
            result = splunkSearch(context, permit -> splunk.searchByTrackingIdDetailed(
                    context.trackingId, context.environment, expanded, permit));
            searchedFrom = expanded.from();
        }
        if (!context.explicitCurrent && context.window == null && context.lastSplunkOutcome == SplunkSearchOutcome.NO_DATA
                && limits.getHistoryDiscoveryWindow().compareTo(maximumWindow) > 0
                && context.splunkSearches < context.budget.searches() - 1) {
            EvidenceQuery earlier = new EvidenceQuery(searchEnd.minus(limits.getHistoryDiscoveryWindow()), searchEnd,
                    Math.min(limits.getMaxLogEvents(), 500), 200_000);
            result = splunkSearch(context, permit -> splunk.searchByTrackingIdDetailed(context.trackingId, context.environment, earlier, permit));
            searchedFrom = earlier.from();
        }
        if (context.window != null) {
            result = new SplunkSearchResult(result.evidence().stream().filter(item -> inIncident(context, item.timestamp())).toList(),
                    result.traceEvents().stream().filter(event -> inIncident(context, event.timestamp())).toList(),
                    result.rawResultCount(), result.truncated());
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
            if (context.service == null) trace.stream().map(TraceEvent::service).filter(value -> validatedServiceIdentifier(value) != null)
                    .filter(value -> serviceRegistry.resolve(value).isPresent()).findFirst().ifPresent(value -> resolveService(context, value));
        }
        if ((context.window == null || broadPeriod(context)) && firstFailure.isPresent() && firstFailure.get().timestamp() != null) {
            Instant time = firstFailure.get().timestamp();
            if (!context.explicitCurrent && !time.isBefore(searchedFrom) && (broadPeriod(context) || time.isBefore(context.scopeReferenceTime.minusSeconds(1800)))) {
                String requested = context.window == null ? "" : "Requested historical period " + context.window.start() + " to " + context.window.end() + ". ";
                adoptDiscoveredWindow(context, InvestigationClues.around(time, context.scopeReferenceTime),
                        requested + "Anchored investigation to the older failure found in the tracking path at " + time);
            }
        }
        persistRepresentativeTrackingEvidence(context, result.evidence(), trace, firstFailure.orElse(null));
    }

    private void discoverObservedTracking(Context context) {
        if (context.service == null || context.trackingSearched || context.splunkSearches >= Math.max(1, context.budget.searches() - 2)) return;
        var candidate = state.snapshotWithEvidence(context.id).getEvidenceItems().stream()
                .filter(item -> !item.isSupersededByScopeChange() && context.service.equals(item.getService()))
                .filter(item -> item.getEvidenceType() == EvidenceType.POD_LOG || item.getEvidenceType() == EvidenceType.ERROR_LOG
                        || item.getEvidenceType() == EvidenceType.LOG_PATTERN || item.getEvidenceType() == EvidenceType.RECENT_BUSINESS_CALLS)
                .filter(item -> HistoricalFailureEvidence.hasSignal(item.getSanitizedContent() + " " + item.getMetadataJson()))
                .map(this::applicationTrackingId)
                .flatMap(Optional::stream).findFirst();
        if (candidate.isEmpty()) return;
        context.trackingId = candidate.get();
        state.resolveTrackingId(context.id, context.trackingId);
        state.note(context.id, InvestigationEventType.ANALYSIS, "Following the tracking ID found in failure evidence");
        String before = context.service;
        var beforeWindow = context.window;
        discoverTrackingPath(context);
        if (!before.equals(context.service) || !java.util.Objects.equals(beforeWindow, context.window)) {
            if (!java.util.Objects.equals(beforeWindow, context.window)) recordRetrospectiveScope(context);
            collectOperationalEvidence(context);
        }
    }

    private Optional<String> applicationTrackingId(EvidenceItem item) {
        try {
            var metadata = objectMapper.readTree(item.getMetadataJson() == null ? "{}" : item.getMetadataJson());
            if (metadata.hasNonNull("routerRequestId") || "http-access".equalsIgnoreCase(metadata.path("sourceFormat").asText())) {
                return Optional.empty();
            }
            String canonical = metadata.path("trackingId").asText("");
            if (!canonical.isBlank()) return Optional.of(ConnectorInputValidator.trackingId(canonical));
        } catch (RuntimeException ignored) { /* Raw application text remains usable. */ }
        return clues.trackingId(item.getSanitizedContent());
    }

    private void persistRepresentativeTrackingEvidence(Context context, List<ConnectorEvidence> connectorEvidence,
            List<TraceEvent> trace, TraceEvent firstFailure) {
        int available = context.budget.evidenceItems() - context.evidenceCount;
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
        String resolvedService = definition.map(ServiceDefinition::service).orElse(normalized);
        if (!java.util.Objects.equals(context.service, resolvedService)) {
            context.latestDeployment = null;
            context.latestDeploymentAttempt = null;
        }
        context.definition = definition.orElse(null);
        context.service = resolvedService;
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

    private EvidenceQuery incidentQuery(Context context) {
        return new EvidenceQuery(context.window.start(), context.window.end(),
                Math.min(limits.getMaxLogEvents(), 500), 120_000);
    }

    private static boolean inIncident(Context context, Instant time) {
        return time != null && !time.isBefore(context.window.start()) && time.isBefore(context.window.end());
    }

    private void recordRetrospectiveScope(Context context) {
        String scope = "Retrospective investigation: " + context.window.start() + " <= event time < "
                + context.window.end() + ". Recovery comparison ends at "
                + context.window.recoveryEnd(context.scopeReferenceTime) + ".\n"
                + "No previous agent run is required. Current health, API/database probes, runtime config and recent CLI logs "
                + "cannot reconstruct past conditions and are excluded. Retained history may be incomplete or expired; "
                + "an empty search does not disprove the reported incident. Current registry mappings may differ from historical mappings. "
                + "Subsequent changes are possible fixes, not confirmed remediation. Jenkins build completion and branch history "
                + "do not attest the runtime revision, especially after platform restages.";
        append(context, new EvidenceDraft(EvidenceSource.SERVICE_REGISTRY, EvidenceType.OTHER, null,
                context.service, context.investigation.getEnvironment(), "Retrospective investigation scope",
                scope, null, json(Map.of("incidentStart", context.window.start(), "incidentEnd", context.window.end())),
                EvidenceReliability.HIGH));
        context.addLimitation("Historical reconstruction depends on source retention; present health is not evidence of past conditions");
    }

    private void collectRetrospectiveService(Context context, String service) {
        collectRetrospectiveLogs(context, service, null);
        collectRetrospectiveEvents(context, service);
        collectRetrospectiveChanges(context, service);
        collectRetrospectiveRepository(context, service);
    }

    private void collectRetrospectiveLogs(Context context, String service, EvidenceRequestType type) {
        List<ConnectorEvidence> discovered = context.discoveredLogs.remove(service);
        if (discovered != null) {
            appendConnectorEvidence(context, discovered, "Discovered incident logs");
            return;
        }
        if (!usesTas(service, context.investigation.getEnvironment())) {
            context.addLimitation("Historical Kubernetes application logs are not supported by the current CF-specific Splunk connector: " + service);
            return;
        }
        if (context.splunkSearches >= context.budget.searches()) {
            context.addLimitation("Historical log search budget exhausted for " + service);
            return;
        }
        int remaining = context.budget.evidenceItems() - context.evidenceCount;
        // Keep capacity for each service's delivery/config history and registered dependencies.
        int sampleSize = Math.min(Math.min(limits.getMaxLogEvents(), 5), Math.max(1, remaining / 4));
        EvidenceQuery query = new EvidenceQuery(context.window.start(), context.window.end(), sampleSize, 20_000);
        SplunkSearchResult result = splunkSearch(context, permit -> type == null
                ? splunk.searchErrorsForServiceDetailed(service, context.environment, query, permit) : switch (type) {
            case RECENT_BUSINESS_CALLS -> splunk.searchRecentBusinessCallsDetailed(service, context.environment, query, permit);
            case RECENT_ACTIVITY -> splunk.searchRecentActivityDetailed(service, context.environment, query, permit);
            case ERROR_PATTERNS -> splunk.getErrorPatternsDetailed(service, context.environment, query, permit);
            default -> splunk.searchServiceEventsDetailed(service, context.environment, query, permit);
        });
        List<ConnectorEvidence> incidentItems = result.evidence().stream()
                .filter(item -> inIncident(context, item.timestamp())).limit(query.maxResults()).toList();
        appendConnectorEvidence(context, incidentItems, "Retained incident logs for " + service);
        if (incidentItems.isEmpty()) context.addLimitation("No timestamped incident logs were retained or returned for " + service);
        if (incidentItems.size() < result.evidence().size()) context.addLimitation("Historical log records were omitted by timestamp or sample bounds for " + service);
        if (result.truncated()) context.addLimitation("Historical log results were truncated for " + service);
        // Error time, not the time of collection or the first successful call, anchors the Jenkins candidate.
        state.snapshotWithEvidence(context.id).getEvidenceItems().stream()
                .filter(item -> HistoricalFailureEvidence.belongsTo(item, context.service))
                .filter(item -> HistoricalFailureEvidence.isFailure(item, context.window)).map(EvidenceItem::getOccurredAt)
                .min(Comparator.naturalOrder()).ifPresent(time -> context.failureAt = time);
    }

    private void collectRetrospectiveEvents(Context context, String service) {
        if (!usesTas(service, context.investigation.getEnvironment())) return;
        EvidenceQuery query = new EvidenceQuery(context.window.start().minus(limits.getCommitSearchWindow()),
                context.window.recoveryEnd(context.scopeReferenceTime), 10, 20_000);
        List<ConnectorEvidence> events = safeList(context, "Retained TAS events",
                () -> tas.getEvents(service, context.environment, query));
        for (ConnectorEvidence event : events.stream().limit(10).toList()) {
            append(context, labeled(mapper.map(event), "Retained CF event listing (coverage unverified)",
                    "Collected now. cf events returns retained events and does not apply the incident time filter. "
                            + "Use timestamps inside each event; do not interpret the collection timestamp as deployment time. "));
        }
        if (events.isEmpty()) context.addLimitation("No retained platform events were available for " + service);
    }

    private List<DeploymentInfo> historicalBuilds(Context context, String service) {
        if (!context.historicalBuilds.containsKey(service)) {
            List<DeploymentInfo> builds = safeList(context, "Retained Jenkins history",
                    () -> jenkins.getDeploymentHistory(service, context.environment, historicalAnchor(context, service))).stream().limit(100).toList();
            context.historicalBuilds.put(service, builds);
            context.addLimitation("Jenkins history for " + service + " is a bounded sample of at most 100 retained builds near the incident anchor; deployment time and runtime revision remain unverified");
            builds.stream().map(build -> build.metadata().get("coverage")).filter(java.util.Objects::nonNull).distinct().forEach(context::addLimitation);
        }
        return context.historicalBuilds.get(service);
    }

    private void restoreHistoricalContext(Context context, Investigation snapshot) {
        // Only application-created registry edges can restore an expanded code-inspection scope.
        for (int depth = 0; depth < limits.getMaxDependencyServices(); depth++) {
            for (EvidenceItem item : snapshot.getEvidenceItems()) {
                if (item.isSupersededByScopeChange()) continue;
                if (item.getSourceSystem() != EvidenceSource.SERVICE_REGISTRY || item.getEvidenceType() != EvidenceType.CALL_CHAIN
                        || !context.allowedServices.contains(item.getService())) continue;
                try {
                    String dependency = objectMapper.readTree(item.getMetadataJson()).path("downstreamService").asText();
                    serviceRegistry.resolve(dependency).ifPresent(definition -> allowService(context, definition.service()));
                } catch (RuntimeException ignored) { /* Missing provenance never expands scope. */ }
            }
        }
        for (EvidenceItem item : snapshot.getEvidenceItems()) {
                if (item.isSupersededByScopeChange()) continue;
            if (item.getEvidenceType() == EvidenceType.SOURCE_CODE) {
                try {
                    var metadata = objectMapper.readTree(item.getMetadataJson());
                    context.attemptedCodeFiles.add(item.getService() + "|" + metadata.path("revision").asText() + "|" + metadata.path("path").asText());
                } catch (RuntimeException ignored) { /* The persisted count still bounds unavailable provenance. */ }
            }
            if (item.getSourceSystem() != EvidenceSource.JENKINS || item.getEvidenceType() != EvidenceType.DEPLOYMENT_METADATA
                    || !context.allowedServices.contains(item.getService())) continue;
            try {
                for (var metadata : objectMapper.readTree(item.getMetadataJson()).path("historicalDeployments")) {
                    String completedAt = metadata.path("completedAt").asText();
                    Instant.parse(completedAt);
                    String sha = validatedRevision(metadata.path("commitSha").asText()).orElse("");
                    var build = new DeploymentInfo(item.getService(), context.environment, metadata.path("jobName").asText(),
                            metadata.path("buildNumber").asLong(), metadata.path("result").asText(),
                            Instant.parse(metadata.path("startedAt").asText()), sha,
                            null, List.of(), List.of(), List.of(), Map.of("completedAt", completedAt,
                                    "deployed", metadata.path("deployed").asText("unverified")));
                    context.historicalBuilds.computeIfAbsent(item.getService(), ignored -> new ArrayList<>()).add(build);
                    context.historicalChangesCollected.add(item.getService());
                }
            } catch (RuntimeException ignored) { /* Unsupported metadata cannot become a revision candidate. */ }
        }
    }

    private Instant historicalAnchor(Context context, String service) {
        return state.snapshotWithEvidence(context.id).getEvidenceItems().stream()
                .filter(item -> HistoricalFailureEvidence.belongsTo(item, service))
                .filter(item -> HistoricalFailureEvidence.isFailure(item, context.window))
                .map(EvidenceItem::getOccurredAt).min(Comparator.naturalOrder())
                .orElse(service.equals(context.service) && context.failureAt != null ? context.failureAt : context.window.start());
    }

    private void collectRetrospectiveChanges(Context context, String service) {
        List<DeploymentInfo> builds = historicalBuilds(context, service);
        Instant anchor = historicalAnchor(context, service);
        Optional<DeploymentInfo> candidate = HistoricalDeploymentSelection.atOrBefore(builds, anchor);
        if (service.equals(context.service)) context.latestDeployment = candidate.orElse(null);
        if (!context.historicalChangesCollected.add(service)) return;
        LinkedHashSet<DeploymentInfo> selected = new LinkedHashSet<>();
        HistoricalDeploymentSelection.atOrBefore(builds, context.window.start()).ifPresent(selected::add);
        candidate.ifPresent(selected::add);
        List<DeploymentInfo> inWindow = builds.stream().filter(build -> HistoricalDeploymentSelection.completedAt(build)
                        .map(time -> !time.isBefore(context.window.start())
                                && time.isBefore(context.window.recoveryEnd(context.scopeReferenceTime)))
                        .orElse(false))
                .sorted(Comparator.comparing(build -> HistoricalDeploymentSelection.completedAt(build).orElseThrow())).toList();
        selected.addAll(representativeItems(inWindow, 6, -1));
        if (inWindow.size() > 6) context.addLimitation("Deployment timeline was sampled for " + service);
        StringBuilder timeline = new StringBuilder("Historical revision candidates only; runtime deployment and platform restages are unverified.\n");
        List<Map<String, Object>> persistedBuilds = new ArrayList<>();
        for (DeploymentInfo build : selected) {
            Instant completed = HistoricalDeploymentSelection.completedAt(build).orElseThrow();
            timeline.append(HistoricalDeploymentSelection.phase(completed, context.window))
                    .append(": Jenkins build #").append(build.buildNumber()).append(" ").append(build.result())
                    .append(" completed at ").append(completed).append("; started at ").append(build.timestamp())
                    .append("; revision ").append(build.commitSha()).append('\n');
            persistedBuilds.add(Map.of("jobName", build.jobName(), "buildNumber", build.buildNumber(),
                    "result", build.result(), "startedAt", build.timestamp(), "completedAt", completed,
                    "commitSha", build.commitSha() == null ? "" : build.commitSha(),
                    "deployed", build.metadata().getOrDefault("deployed", "unverified")));
        }
        if (!selected.isEmpty()) {
            append(context, new EvidenceDraft(EvidenceSource.JENKINS, EvidenceType.DEPLOYMENT_METADATA, null,
                    service, context.investigation.getEnvironment(), "Historical deployment timeline for " + service,
                    timeline.toString(), null, json(Map.of("historicalDeployments", persistedBuilds)), EvidenceReliability.MEDIUM));
        }
        if (candidate.isEmpty() || validatedRevision(candidate.get().commitSha()).isEmpty()) {
            context.addLimitation("No historical Jenkins revision candidate at or before " + anchor + " for " + service
                    + "; current latest revision was not substituted");
            return;
        }
        String sha = validatedRevision(candidate.get().commitSha()).orElseThrow();
        List<CommitChange> changes = safeList(context, "Source history at historical Jenkins candidate",
                () -> gitLab.getCommits(service, sha, 5));
        appendCommitSample(context, service, changes, "Source history at historical Jenkins candidate " + shortSha(sha),
                "Candidate selected at or before " + anchor + ". These commits do not prove the revision running during the incident.");
        Optional<DeploymentInfo> recovery = inWindow.stream().filter(this::isSuccessfulDeployment)
                .filter(build -> HistoricalDeploymentSelection.completedAt(build).orElseThrow().compareTo(context.window.end()) >= 0)
                .filter(build -> validatedRevision(build.commitSha()).filter(value -> !sha.equals(value)).isPresent()).findFirst();
        recovery.ifPresent(build -> {
            List<CommitChange> fixChanges = safeList(context, "Historical candidate to possible recovery comparison",
                    () -> gitLab.compareRevisions(service, sha, build.commitSha(), 30_000));
            appendCommitSample(context, service, fixChanges, "After incident: possible recovery changes for " + service,
                    "Compared historical Jenkins candidate " + sha + " to later candidate " + build.commitSha()
                            + ". A subsequent deployment is not proof of recovery or causation.");
        });
    }

    private void appendCommitSample(Context context, String service, List<CommitChange> commits, String label, String caveat) {
        if (commits.isEmpty()) return;
        String content = caveat + "\n" + commits.stream().limit(10).map(commit -> commit.committedAt() + " "
                        + commit.commitSha() + " " + commit.title() + "\n" + commit.boundedDiff())
                .collect(java.util.stream.Collectors.joining("\n"));
        append(context, new EvidenceDraft(EvidenceSource.GITLAB, EvidenceType.COMMIT_DIFF, null, service,
                context.investigation.getEnvironment(), label, content.substring(0, Math.min(content.length(), 30_000)),
                null, json(Map.of("deployed", "unverified", "sampleSize", Math.min(commits.size(), 10))), EvidenceReliability.MEDIUM));
    }

    private void collectRetrospectiveRepository(Context context, String service) {
        if (!context.historicalRepositoryCollected.add(service)) return;
        EvidenceQuery before = new EvidenceQuery(context.window.start().minus(limits.getCommitSearchWindow()),
                context.window.end(), 10, 30_000);
        List<String> keywords = serviceRegistry.resolve(service)
                .map(definition -> definition.attributeValues("gitlab.commitKeywords")).orElse(List.of());
        appendCommitSample(context, service, safeList(context, "Historical advisory branch history",
                () -> gitLab.searchCommits(service, keywords, before)), "Before/during incident: advisory branch history for " + service,
                "Branch history from " + before.from() + " to " + before.to() + "; does not establish which commits were deployed.");
        Instant recoveryEnd = context.window.recoveryEnd(context.scopeReferenceTime);
        if (context.window.end().isBefore(recoveryEnd)) {
            EvidenceQuery after = new EvidenceQuery(context.window.end(), recoveryEnd, 10, 20_000);
            appendCommitSample(context, service, safeList(context, "Possible recovery branch history",
                    () -> gitLab.searchCommits(service, List.of(), after)), "After incident: advisory possible fix history for " + service,
                    "Branch history from " + after.from() + " to " + after.to() + "; a later commit does not prove remediation.");
        }
        List<ConnectorEvidence> config = safeList(context, "Historical repository configuration",
                () -> gitLab.getRepositoryConfigurationAt(service, context.environment, context.window.start(), 20_000));
        appendConnectorEvidence(context, config, "Historical repository configuration");
        if (config.isEmpty()) context.addLimitation("Historical config-server file was unavailable or unconfigured for " + service);
    }

    private void collectRetrospectiveRequest(Context context, String service, EvidenceRequestType type) {
        switch (type) {
            case RECENT_LOGS, SERVICE_EVENTS, RECENT_ACTIVITY, RECENT_BUSINESS_CALLS, ERROR_PATTERNS ->
                    collectRetrospectiveLogs(context, service, type);
            case LATEST_DEPLOYMENT, RECENT_CHANGES -> {
                collectRetrospectiveChanges(context, service);
                collectRetrospectiveRepository(context, service);
            }
            case RECENT_RUNTIME_EVENTS -> collectRetrospectiveEvents(context, service);
            case EFFECTIVE_CONFIGURATION -> collectRetrospectiveRepository(context, service);
            case HISTORICAL_INCIDENTS -> collectHistoricalEvidence(context);
            case RELEVANT_CODE_FILES -> {
                collectAutomaticCode(context, service);
            }
            default -> {
                String limitation = "Requested " + type + " cannot reconstruct historical runtime conditions for " + service;
                context.addLimitation(limitation);
                state.note(context.id, InvestigationEventType.NOTE, limitation);
            }
        }
    }

    private EvidenceDraft labeled(EvidenceDraft draft, String label, String caveat) {
        return new EvidenceDraft(draft.sourceSystem(), draft.evidenceType(), draft.occurredAt(), draft.service(),
                draft.environment(), label + ": " + draft.summary(), caveat + "\n" + draft.content(),
                draft.sourceUrl(), draft.metadataJson(), EvidenceReliability.MEDIUM);
    }

    private EvidenceDraft sourceFileDraft(Context context, String service, String sha, String path, String content) {
        EvidenceDraft draft = mapper.sourceFile(service, context.investigation.getEnvironment(), sha, path, content);
        return context.window == null ? draft : labeled(draft, "Historical Jenkins candidate " + shortSha(sha),
                "Historical candidate source; the revision running at the incident time is unverified.");
    }

    private ReasoningDecision fallback(Context context, Investigation snapshot, String reason) {
        return context.window == null ? heuristics.bestEffort(snapshot.getEvidenceItems(), reason)
                : retrospectiveUnknown(reason);
    }

    private ReasoningDecision retrospectiveUnknown(String reason) {
        return new ReasoningDecision(ReasoningStatus.COMPLETE,
                "Historical root cause remains inconclusive. " + reason
                        + ". Missing retained evidence does not disprove the reported incident; current health cannot establish past conditions.",
                List.of(), List.of(), RootCauseCategory.UNKNOWN,
                List.of("Check retained incident logs and deployment history, or narrow the incident window.",
                        "Confirm the fix and its deployment time with the incident record from the team that resolved it."));
    }

    private void collectOperationalEvidence(Context context) {
        if (context.window != null) {
            collectRetrospectiveService(context, context.service);
            return;
        }
        checkLimits(context);
        if (usesTas(context)) {
            collectAdaptiveTas(context);
            return;
        }
        appendConnectorEvidence(context, safeList(context, "Kubernetes workload health",
                () -> kubernetes.getWorkloadHealth(context.service, context.environment)), "Kubernetes health");
        appendConnectorEvidence(context, safeList(context, "Kubernetes pod events",
                () -> kubernetes.getRecentPodEvents(context.service, context.environment, recentQuery(context))), "Kubernetes events");
        safeOptional(context, "Jenkins latest deployment", () -> jenkins.getLatestDeployment(context.service, context.environment))
                .ifPresent(deployment -> recordLatestDeploymentAttempt(context, deployment));
        List<ConnectorEvidence> logs = safeList(context, "Kubernetes application logs",
                () -> kubernetes.getRecentPodLogs(context.service, context.environment,
                        context.failureAt == null ? recentQuery(context) : around(context.failureAt)));
        appendConnectorEvidence(context, logs, "Kubernetes application logs");
        if (context.failureAt == null) logs.stream().filter(this::hasFailureSignal).map(ConnectorEvidence::timestamp)
                .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).ifPresent(time -> context.failureAt = time);
        if (evidenceIndicatesDependency(state.snapshotWithEvidence(context.id).getEvidenceItems())) collectDependencyEvidence(context, context.service);
        appendConnectorEvidence(context, safeList(context, "Kubernetes effective configuration",
                () -> kubernetes.getEffectiveConfiguration(context.service, context.environment)), "Kubernetes configuration");
        collectRecentChanges(context);
        collectRepositoryEvidence(context, context.service);
        collectApiProbeEvidence(context, context.service);
    }

    private boolean hasFailureSignal(ConnectorEvidence item) {
        return HistoricalFailureEvidence.hasSignal(item.summary() + " " + item.content() + " " + item.metadata());
    }

    private void collectAdaptiveTas(Context context) {
        // CF --recent is an unbounded listing timestamped at collection. Only scoped Splunk errors establish recency.
        // Delay persistence of current snapshots until the evidence establishes which period is relevant.
        List<ConnectorEvidence> health = safeList(context, "TAS application status",
                () -> tas.getApplicationStatus(context.service, context.environment));
        List<ConnectorEvidence> runtimeLogs = safeList(context, "TAS recent logs",
                () -> tas.getRecentLogs(context.service, context.environment, recentQuery(context)));
        SplunkSearchResult recent = context.failureAt == null
                ? splunkSearch(context, permit -> splunk.searchErrorsForServiceDetailed(context.service, context.environment, recentQuery(context), permit))
                : splunkSearch(context, permit -> splunk.searchAroundTimestampDetailed(context.service, context.environment,
                        context.failureAt, around(context.failureAt), permit));
        if (!context.explicitCurrent && recent.evidence().stream().noneMatch(this::hasFailureSignal)
                && (context.lastSplunkOutcome == SplunkSearchOutcome.SUCCESS || context.lastSplunkOutcome == SplunkSearchOutcome.NO_DATA
                    || context.lastSplunkOutcome == SplunkSearchOutcome.PARTIAL_PARSE)) {
            if (discoverEarlierFailure(context)) {
                recordRetrospectiveScope(context);
                collectRetrospectiveService(context, context.service);
                return;
            }
        }
        appendConnectorEvidence(context, health, "TAS runtime");
        appendConnectorEvidence(context, runtimeLogs, "TAS logs");
        appendConnectorEvidence(context, recent.evidence(), "Splunk service errors");
        if (recent.truncated()) context.addLimitation("Service log results were truncated");
        appendConnectorEvidence(context, safeList(context, "TAS deployment events",
                () -> tas.getEvents(context.service, context.environment, trackingQuery(context))), "TAS deployment events");
        safeOptional(context, "Jenkins latest deployment", () -> jenkins.getLatestDeployment(context.service, context.environment))
                .ifPresent(deployment -> recordLatestDeploymentAttempt(context, deployment));
        if (context.failureAt == null) recent.evidence().stream().filter(this::hasFailureSignal).map(ConnectorEvidence::timestamp)
                .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).ifPresent(time -> context.failureAt = time);
        if (evidenceIndicatesDependency(state.snapshotWithEvidence(context.id).getEvidenceItems())) collectDependencyEvidence(context, context.service);
        appendConnectorEvidence(context, safeList(context, "TAS environment metadata",
                () -> tas.getEnvironmentMetadata(context.service, context.environment)), "TAS configuration");
        collectRecentChanges(context);
        collectRepositoryEvidence(context, context.service);
        collectApiProbeEvidence(context, context.service);
    }

    private boolean discoverEarlierFailure(Context context) {
        if (context.explicitCurrent) {
            context.addLimitation("The question explicitly targets current conditions; an earlier failure cannot replace the current scope");
            return false;
        }
        Instant end = context.scopeReferenceTime;
        Instant upper = end.minus(Duration.ofMinutes(30));
        List<Duration> windows = java.util.stream.Stream.of(limits.getTrackingSearchWindow(), limits.getHistoryDiscoveryWindow())
                .filter(value -> value.compareTo(Duration.ofMinutes(30)) > 0).distinct().sorted().toList();
        int reservedDependencies = Math.min(2, limits.getMaxSplunkSearches() - 1);
        for (Duration duration : windows) {
            if (context.splunkSearches >= context.budget.searches() - reservedDependencies) break;
            Instant lower = end.minus(duration);
            if (!lower.isBefore(upper)) continue;
            EvidenceQuery query = new EvidenceQuery(lower, upper, 5, 20_000);
            state.note(context.id, InvestigationEventType.ANALYSIS, "Searching earlier retained failures from " + lower + " to " + upper);
            SplunkSearchResult result = splunkSearch(context, permit -> splunk.searchErrorsForServiceDetailed(
                    context.service, context.environment, query, permit));
            Optional<Instant> failure = result.evidence().stream().filter(item -> context.service.equals(item.service())).filter(this::hasFailureSignal)
                    .map(ConnectorEvidence::timestamp).filter(java.util.Objects::nonNull)
                    .filter(time -> !time.isBefore(query.from()) && time.isBefore(query.to())).max(Comparator.naturalOrder());
            if (failure.isPresent()) {
                adoptDiscoveredWindow(context, InvestigationClues.around(failure.get(), end),
                        "Found a candidate earlier failure at " + failure.get() + "; narrowed the evidence window automatically. "
                                + "This is a sampled candidate and may not be the reported incident.");
                context.discoveredLogs.put(context.service, result.evidence().stream()
                        .filter(item -> inIncident(context, item.timestamp())).toList());
                return true;
            }
            if (context.lastSplunkOutcome != SplunkSearchOutcome.NO_DATA && context.lastSplunkOutcome != SplunkSearchOutcome.SUCCESS
                    && context.lastSplunkOutcome != SplunkSearchOutcome.PARTIAL_PARSE) break;
            upper = lower;
        }
        context.addLimitation("Automatic retained-history discovery found no timestamped failure within its search budget; add a date or tracking clue in the conversation if available");
        return false;
    }

    private static boolean broadPeriod(Context context) {
        return context.window != null && Duration.between(context.window.start(), context.window.end()).compareTo(Duration.ofDays(7)) > 0;
    }

    private void narrowRequestedPeriod(Context context) {
        if (!broadPeriod(context) || context.service == null || !usesTas(context.service, context.investigation.getEnvironment())) return;
        var requested = context.window;
        var query = new EvidenceQuery(requested.start(), requested.end(), 5, 20_000);
        SplunkSearchResult result = splunkSearch(context, permit -> splunk.searchErrorsForServiceDetailed(
                context.service, context.environment, query, permit));
        var candidate = result.evidence().stream()
                .filter(item -> context.service.equals(item.service()) && context.environment == item.environment())
                .filter(this::hasFailureSignal).map(ConnectorEvidence::timestamp).filter(java.util.Objects::nonNull)
                .filter(time -> !time.isBefore(requested.start()) && time.isBefore(requested.end())).max(Comparator.naturalOrder());
        String coverage = "Requested historical period " + requested.start() + " to " + requested.end()
                + ". Searched a bounded sample of retained failures; this does not cover every incident in the period.";
        if (candidate.isPresent()) {
            var around = InvestigationClues.around(candidate.get(), context.scopeReferenceTime);
            var narrowed = new com.jmopsagent.domain.IncidentWindow(
                    around.start().isBefore(requested.start()) ? requested.start() : around.start(),
                    around.end().isAfter(requested.end()) ? requested.end() : around.end());
            adoptDiscoveredWindow(context, narrowed, coverage + " Following a sampled failure at " + candidate.get() + ".");
        } else context.addLimitation(coverage + " No timestamped failure was returned; the reported incident remains unverified.");
        context.discoveredLogs.put(context.service, result.evidence().stream()
                .filter(item -> context.service.equals(item.service()) && context.environment == item.environment())
                .filter(item -> inIncident(context, item.timestamp())).toList());
        append(context, new EvidenceDraft(EvidenceSource.SERVICE_REGISTRY, EvidenceType.OTHER, null, context.service,
                context.investigation.getEnvironment(), "Requested historical period", coverage,
                null, json(Map.of("requestedStart", requested.start(), "requestedEnd", requested.end())), EvidenceReliability.HIGH));
    }

    private void adoptDiscoveredWindow(Context context, com.jmopsagent.domain.IncidentWindow window, String reason) {
        state.resolveIncidentWindow(context.id, window, reason);
        context.window = window;
        context.scopeReferenceTime = state.snapshotWithEvidence(context.id).getEvidenceScopeResolvedAt();
        if (context.failureAt != null && (window == null || !inIncident(context, context.failureAt))) context.failureAt = null;
        context.discoveredLogs.clear();
        context.attemptedCodeFiles.clear();
        context.latestDeployment = null;
        context.latestDeploymentAttempt = null;
        context.historicalBuilds.clear();
        context.historicalChangesCollected.clear();
        context.historicalRepositoryCollected.clear();
        context.addLimitation(reason);
    }

    private void collectDownstreamServices(Context context) {
        var resolver = new com.jmopsagent.registry.ServiceDependencyResolver(serviceRegistry);
        var pending = new java.util.ArrayDeque<String>();
        Set<String> visited = new LinkedHashSet<>();
        visited.add(context.service);
        pending.add(context.service);
        int collected = 0;
        while (!pending.isEmpty()) {
            String parent = pending.removeFirst();
            List<String> logs = state.snapshotWithEvidence(context.id).getEvidenceItems().stream()
                    .filter(item -> !item.isSupersededByScopeChange() && parent.equals(item.getService()))
                    .filter(item -> item.getEvidenceType() == EvidenceType.ERROR_LOG
                            || item.getEvidenceType() == EvidenceType.POD_LOG
                            || item.getEvidenceType() == EvidenceType.LOG_PATTERN
                            || item.getEvidenceType() == EvidenceType.CALL_CHAIN)
                    .map(EvidenceItem::getSanitizedContent).toList();
            for (var edge : resolver.downstream(parent, context.investigation.getEnvironment(), logs).entrySet()) {
                String service = edge.getKey();
                if (visited.contains(service)) continue;
                if (collected >= limits.getMaxDependencyServices()) {
                    context.addLimitation("Registered dependency traversal reached its service limit");
                    return;
                }
                checkLimits(context);
                visited.add(service);
                collected++;
                allowService(context, service);
                append(context, new EvidenceDraft(EvidenceSource.SERVICE_REGISTRY, EvidenceType.CALL_CHAIN,
                        null, parent, context.investigation.getEnvironment(), parent + " → " + service,
                        "Downstream relationship from " + edge.getValue() + "; failure propagation is unverified.",
                        null, json(Map.of("downstreamService", service, "relationshipSource", edge.getValue())),
                        EvidenceReliability.MEDIUM));
                state.note(context.id, InvestigationEventType.ANALYSIS, "Checking downstream service " + service);
                collectDownstreamEvidence(context, service);
                pending.addLast(service);
            }
        }
    }

    private void collectDownstreamEvidence(Context context, String service) {
        if (context.window != null) {
            collectRetrospectiveService(context, service);
            return;
        }
        EvidenceQuery query = context.failureAt == null ? recentQuery(context) : around(context.failureAt);
        query = new EvidenceQuery(query.from(), query.to(), Math.min(query.maxResults(), 5), 20_000);
        final EvidenceQuery boundedQuery = query;
        if (usesTas(service, context.investigation.getEnvironment())) {
            appendConnectorEvidence(context, safeList(context, "Dependency TAS health",
                    () -> tas.getApplicationStatus(service, context.environment)), "Dependency TAS health");
            appendConnectorEvidence(context, safeList(context, "Dependency TAS logs",
                    () -> tas.getRecentLogs(service, context.environment, boundedQuery)), "Dependency TAS logs");
            appendConnectorEvidence(context, safeList(context, "Dependency TAS events",
                    () -> tas.getEvents(service, context.environment, trackingQuery(context))), "Dependency TAS events");
            if (context.splunkSearches < context.budget.searches()) {
                appendSplunkEvidence(context, splunkSearch(context, permit -> splunk.searchServiceEventsDetailed(
                        service, context.environment, boundedQuery, permit)), "Dependency Splunk logs");
            } else context.addLimitation("Dependency Splunk logs skipped because the search budget was exhausted");
        } else {
            appendConnectorEvidence(context, safeList(context, "Dependency Kubernetes health",
                    () -> kubernetes.getWorkloadHealth(service, context.environment)), "Dependency health");
            appendConnectorEvidence(context, safeList(context, "Dependency Kubernetes logs",
                    () -> kubernetes.getRecentPodLogs(service, context.environment, boundedQuery)), "Dependency logs");
            appendConnectorEvidence(context, safeList(context, "Dependency Kubernetes events",
                    () -> kubernetes.getRecentPodEvents(service, context.environment, boundedQuery)), "Dependency events");
        }
        collectScopedChanges(context, service);
        collectRepositoryEvidence(context, service);
        collectApiProbeEvidence(context, service);
    }

    private void collectScopedChanges(Context context, String service) {
        if (context.window != null) {
            collectRetrospectiveChanges(context, service);
            return;
        }
        if (service.equals(context.service)) {
            collectRecentChanges(context);
            return;
        }
        List<DeploymentInfo> builds = safeList(context, "Dependency Jenkins deployment history",
                () -> jenkins.getLastBuilds(service, context.environment, 10));
        safeOptional(context, "Dependency latest deployment", () -> jenkins.getLatestDeployment(service, context.environment))
                .ifPresent(deployment -> append(context, mapper.deployment(deployment)));
        // Keep dependency deployment state local; it must never become the primary service's code revision.
        List<DeploymentInfo> successful = successfulDeployments(builds);
        if (successful.isEmpty()) return;
        var sha = validatedRevision(successful.getFirst().commitSha());
        if (sha.isEmpty()) return;
        List<CommitChange> changes = safeList(context, "Dependency source history at Jenkins revision",
                () -> gitLab.getCommits(service, sha.get(), 5));
        if (!changes.isEmpty()) {
            append(context, new EvidenceDraft(EvidenceSource.GITLAB, EvidenceType.COMMIT_DIFF, null, service,
                    context.investigation.getEnvironment(), "Dependency history at Jenkins-reported revision " + shortSha(sha.get()),
                    changes.stream().map(change -> change.commitSha() + " " + change.title())
                            .collect(java.util.stream.Collectors.joining("\n")), null,
                    json(Map.of("revision", sha.get(), "revisionSource", "Jenkins", "platformRestageVerified", false)),
                    EvidenceReliability.MEDIUM));
        }
    }

    private void collectRepositoryEvidence(Context context, String service) {
        if (context.window != null) {
            collectRetrospectiveRepository(context, service);
            return;
        }
        Instant end = context.failureAt == null ? Instant.now() : context.failureAt;
        EvidenceQuery query = new EvidenceQuery(end.minus(limits.getCommitSearchWindow()), end, 10, 30_000);
        List<String> keywords = serviceRegistry.resolve(service)
                .map(definition -> definition.attributeValues("gitlab.commitKeywords")).orElse(List.of());
        List<CommitChange> commits = safeList(context, "GitLab advisory branch history",
                () -> gitLab.searchCommits(service, keywords, query));
        if (!commits.isEmpty()) {
            String history = commits.stream().limit(10).map(commit -> commit.committedAt() + " "
                    + commit.commitSha() + " " + commit.title()).collect(java.util.stream.Collectors.joining("\n"));
            append(context, new EvidenceDraft(EvidenceSource.GITLAB, EvidenceType.COMMIT_DIFF, null,
                    service, context.investigation.getEnvironment(), "Advisory branch commit history for " + service,
                    "Branch history does not establish that these commits were deployed.\n" + history, null,
                    json(Map.of("deployed", "unverified", "sampleSize", Math.min(commits.size(), 10),
                            "coverage", "bounded sample of at most 100 branch commits", "from", query.from(), "to", query.to())),
                    EvidenceReliability.MEDIUM));
        }
        appendConnectorEvidence(context, safeList(context, "GitLab repository configuration",
                () -> gitLab.getRepositoryConfiguration(service, context.environment, 20_000)), "Repository configuration");
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
                .filter(item -> !item.isSupersededByScopeChange())
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
        ReasoningDecision lastDecision = null;
        String stopReason = "Maximum Claude iterations reached";
        boolean exhaustedIterations = true;
        for (int iteration = 1; iteration <= limits.getMaxClaudeIterations(); iteration++) {
            checkLimits(context);
            state.transition(context.id, InvestigationStatus.ANALYZING,
                    "Analyzing sanitized evidence (iteration " + iteration + " of " + limits.getMaxClaudeIterations() + ")");
            Investigation snapshot = state.snapshotWithEvidence(context.id);
            List<ReasoningEvidence> evidence = reasoningEvidenceMapper.map(snapshot.getEvidenceItems());
            ClaudeReasoningRequest request = new ClaudeReasoningRequest(context.id,
                    (context.window != null ? "RETROSPECTIVE_" : "")
                            + (codeStage ? "CODE_INVESTIGATION" : snapshot.getType().name()), context.service,
                    snapshot.getEnvironment().name(), snapshot.getTrackingId(), snapshot.getUserProblem(), iteration,
                    evidence, context.history, snapshot.getClaudeSessionId(), iteration == limits.getMaxClaudeIterations());
            ClaudeInvocationResult invocation = claude.analyze(request);
            state.recordClaude(context.id, invocation);
            if (!invocation.successful()) {
                ReasoningDecision fallback = fallback(context, snapshot, "Claude Code was unavailable or returned an invalid response");
                state.complete(context.id, fallback, context.limitation());
                return;
            }
            lastDecision = invocation.decision();
            if (lastDecision.status() == ReasoningStatus.CODE_INVESTIGATION_RECOMMENDED && iteration < limits.getMaxClaudeIterations()) {
                int before = context.evidenceCount;
                if (lastDecision.nextEvidenceRequests().isEmpty()) collectAutomaticCode(context, context.service);
                else for (NextEvidenceRequest evidenceRequest : lastDecision.nextEvidenceRequests()) collectRequestedEvidence(context, evidenceRequest);
                if (context.evidenceCount > before) continue;
            }
            if (lastDecision.status() == ReasoningStatus.COMPLETE
                    || lastDecision.status() == ReasoningStatus.CODE_INVESTIGATION_RECOMMENDED) {
                if (context.window != null && snapshot.getEvidenceItems().stream()
                        .noneMatch(item -> HistoricalFailureEvidence.isFailure(item, context.window))) {
                    lastDecision = retrospectiveUnknown("Retained evidence did not establish a failure in the incident window");
                }
                state.complete(context.id, lastDecision, context.limitation());
                return;
            }
            if (iteration == limits.getMaxClaudeIterations()) break;
            state.transition(context.id, InvestigationStatus.NEEDS_MORE_EVIDENCE,
                    "Reasoning requested additional approved evidence");
            int before = context.evidenceCount;
            for (NextEvidenceRequest evidenceRequest : lastDecision.nextEvidenceRequests()) {
                collectRequestedEvidence(context, evidenceRequest);
            }
            if (context.evidenceCount == before) {
                stopReason = "No new approved evidence could be collected";
                exhaustedIterations = false;
                break;
            }
        }
        context.addLimitation(stopReason);
        ReasoningDecision conclusion = lastDecision == null
                ? fallback(context, state.snapshotWithEvidence(context.id), stopReason)
                : new ReasoningDecision(ReasoningStatus.COMPLETE, lastDecision.summary(), lastDecision.hypotheses(),
                        List.of(), lastDecision.rootCauseCategory(), lastDecision.recommendedActions());
        if (context.window != null && state.snapshotWithEvidence(context.id).getEvidenceItems().stream()
                .noneMatch(item -> HistoricalFailureEvidence.isFailure(item, context.window))) {
            conclusion = retrospectiveUnknown("Retained evidence did not establish a failure in the incident window");
        }
        state.note(context.id, exhaustedIterations ? InvestigationEventType.LIMIT_REACHED : InvestigationEventType.NOTE, stopReason);
        state.complete(context.id, conclusion, context.limitation());
    }

    private void collectRequestedEvidence(Context context, NextEvidenceRequest request) {
        checkLimits(context);
        if (request.type() == EvidenceRequestType.TRACKING_TRACE
                && (request.service() == null || approvedRequestedService(context, request.service()) != null)) {
            if (context.trackingId != null) {
                var previousWindow = context.window;
                String previousService = context.service;
                discoverTrackingPath(context);
                if (context.service != null && (!java.util.Objects.equals(previousService, context.service)
                        || !java.util.Objects.equals(previousWindow, context.window))) {
                    if (!java.util.Objects.equals(previousWindow, context.window)) recordRetrospectiveScope(context);
                    collectOperationalEvidence(context);
                    collectDownstreamServices(context);
                }
            } else discoverObservedTracking(context);
            return;
        }
        String service = approvedRequestedService(context, request.service());
        if (service == null) {
            state.note(context.id, InvestigationEventType.NOTE,
                    "Rejected an evidence request for a service outside the resolved tracking scope");
            return;
        }
        boolean tasWorkload = usesTas(service, context.investigation.getEnvironment());
        if (request.type() == EvidenceRequestType.LATEST_TRACKING_ID) {
            collectLatestTrackingId(context, service, null, false);
            return;
        }
        if (request.type() == EvidenceRequestType.EARLIER_FAILURES) {
            if (context.window != null) collectRetrospectiveLogs(context, service, null);
            else if (tasWorkload && service.equals(context.service) && discoverEarlierFailure(context)) {
                recordRetrospectiveScope(context);
                collectRetrospectiveService(context, service);
                collectDownstreamServices(context);
            }
            return;
        }
        if (context.window != null) {
            collectRetrospectiveRequest(context, service, request.type());
            return;
        }
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
                        ? safeList(context, "Requested TAS events",
                        () -> tas.getEvents(service, context.environment, trackingQuery(context)))
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
            case RECENT_CHANGES -> {
                collectScopedChanges(context, service);
                collectRepositoryEvidence(context, service);
            }
            case EFFECTIVE_CONFIGURATION -> {
                List<ConnectorEvidence> items = tasWorkload
                        ? safeList(context, "Requested TAS environment metadata",
                        () -> tas.getEnvironmentMetadata(service, context.environment))
                        : safeList(context, "Requested Kubernetes effective configuration",
                        () -> kubernetes.getEffectiveConfiguration(service, context.environment));
                appendConnectorEvidence(context, items, "Requested configuration");
                appendConnectorEvidence(context, safeList(context, "Requested repository configuration",
                        () -> gitLab.getRepositoryConfiguration(service, context.environment, 20_000)), "Repository configuration");
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
                                    service, context.environment, trackingQuery(context), permit));
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
                collectAutomaticCode(context, service);
            }
        }
    }

    private void collectDependencyEvidence(Context context, String service) {
        DependencyType type = inferDependencyType(state.snapshotWithEvidence(context.id).getEvidenceItems());
        boolean hasProbeEvidence = collectApiProbeEvidence(context, service);
        if (type == DependencyType.DOWNSTREAM_API && hasProbeEvidence) return;
        dependencies.stream().filter(connector -> !(connector instanceof com.jmopsagent.database.RegisteredApiProbeConnector))
                .filter(connector -> connector.supports(type)).findFirst().ifPresentOrElse(connector ->
                        appendConnectorEvidence(context, safeList(context, "Requested dependency evidence",
                                () -> connector.inspect(service, context.environment, type, recentQuery(context))),
                                "Dependency evidence"),
                () -> state.note(context.id, InvestigationEventType.NOTE,
                        "No live adapter is configured for the inferred " + type + " dependency; retained log/config evidence"));
    }

    private boolean collectApiProbeEvidence(Context context, String service) {
        if (context.probeResults.containsKey(service)) return context.probeResults.get(service);
        // A dedicated adapter supports only explicit registry fixtures. Ordinary dependency inference cannot supply URLs.
        boolean collected = false;
        for (DependencyConnector connector : dependencies) {
            if (!(connector instanceof com.jmopsagent.database.RegisteredApiProbeConnector)) continue;
            List<ConnectorEvidence> items = safeList(context, "Registered API probes",
                    () -> connector.inspect(service, context.environment, DependencyType.DOWNSTREAM_API, recentQuery(context)));
            appendConnectorEvidence(context, items, "Registered API probes");
            collected |= !items.isEmpty();
        }
        context.probeResults.put(service, collected);
        return collected;
    }

    private void collectCodeEvidence(Context context) {
        if (context.window != null) {
            collectRetrospectiveChanges(context, context.service);
            String historicalSha = deployedSha(context);
            if (historicalSha == null) throw new DeployedRevisionUnavailableException(
                    "No historical Jenkins revision candidate was retained; current source was not substituted");
            collectCodeFiles(context, historicalSha);
            return;
        }
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

    private void collectAutomaticCode(Context context, String service) {
        if (context.codeFilesRead >= context.budget.sourceFiles()) {
            context.addLimitation("Investigation source-file budget reached");
            return;
        }
        Optional<DeploymentInfo> candidate;
        if (context.window != null) {
            candidate = HistoricalDeploymentSelection.atOrBefore(historicalBuilds(context, service), historicalAnchor(context, service));
        } else if (service.equals(context.service) && context.latestDeployment != null) {
            candidate = Optional.of(context.latestDeployment);
        } else {
            List<DeploymentInfo> builds = safeList(context, "Source revision evidence",
                    () -> jenkins.getLastBuilds(service, context.environment, 10));
            candidate = successfulDeployments(builds).stream().findFirst();
        }
        Optional<String> sha = candidate.flatMap(build -> validatedRevision(build.commitSha()));
        if (sha.isEmpty()) {
            context.addLimitation("Source inspection could not establish an appropriate revision for " + service);
            return;
        }
        state.note(context.id, InvestigationEventType.ANALYSIS, "Inspecting relevant source for " + service + " at revision " + shortSha(sha.get()));
        collectCodeFiles(context, service, sha.get());
    }

    private void collectCodeFiles(Context context, String sha) {
        collectCodeFiles(context, context.service, sha);
    }

    private void collectCodeFiles(Context context, String service, String sha) {
        if (sha == null || context.codeFilesRead >= context.budget.sourceFiles()) return;
        List<String> tree = safeList(context, "GitLab repository tree",
                () -> gitLab.getRepositoryTree(service, sha, "", 250));
        String corpus = state.snapshotWithEvidence(context.id).getEvidenceItems().stream()
                .filter(item -> !item.isSupersededByScopeChange())
                .map(item -> item.getSanitizedContent() + " " + item.getSummary())
                .reduce("", (left, right) -> bounded(left + " " + right, 100_000)).toLowerCase(Locale.ROOT);
        Set<String> namedClasses = stackClasses(corpus);
        List<String> relevant = tree.stream().filter(this::isInspectableCodePath)
                .filter(path -> !context.attemptedCodeFiles.contains(service + "|" + sha + "|" + path))
                .sorted(Comparator.comparingInt(path -> relevance(path, corpus, namedClasses)))
                .limit(context.budget.sourceFiles() - context.codeFilesRead).toList();
        for (String path : relevant) {
            checkLimits(context);
            if (!state.tryReserveSourceFileRead(context.id, context.budget.sourceFiles())) {
                context.addLimitation("Investigation source-file budget reached");
                break;
            }
            context.attemptedCodeFiles.add(service + "|" + sha + "|" + path);
            context.codeFilesRead++;
            safeOptional(context, "GitLab source file",
                    () -> gitLab.getFileContent(service, sha, path, 40_000))
                    .ifPresent(content -> append(context, sourceFileDraft(context, service, sha, path, content)));
        }
        state.note(context.id, InvestigationEventType.EVIDENCE_COLLECTED,
                "Inspected " + relevant.size() + " bounded file(s) at "
                        + (context.window == null ? "deployed SHA " : "historical Jenkins candidate SHA ") + shortSha(sha));
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
            if (context.splunkSearches >= context.budget.searches()) return false;
            boolean reserved = state.tryReserveSplunkSearch(context.id, context.budget.searches());
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

    private UUID append(Context context, EvidenceDraft draft) {
        checkLimits(context);
        if (context.evidenceCount >= context.budget.evidenceItems()) {
            throw new LimitReachedException("Maximum evidence items reached");
        }
        UUID evidenceId = evidenceStore.append(context.id, draft).getId();
        context.evidenceCount++;
        return evidenceId;
    }

    private void checkLimits(Context context) {
        if (Instant.now().isAfter(context.deadline)) throw new LimitReachedException("Maximum wall-clock duration reached");
        if (context.evidenceCount >= context.budget.evidenceItems()) throw new LimitReachedException("Maximum evidence items reached");
    }

    private void completeAtLimit(Context context, String reason) {
        context.addLimitation(reason);
        state.note(context.id, InvestigationEventType.LIMIT_REACHED, reason);
        Investigation snapshot = state.snapshotWithEvidence(context.id);
        ReasoningDecision best = fallback(context, snapshot, reason);
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

    private EvidenceQuery recentQuery(Context context) {
        if (context.window != null) return incidentQuery(context);
        Instant end = Instant.now();
        return new EvidenceQuery(end.minus(Duration.ofMinutes(30)), end,
                Math.min(limits.getMaxLogEvents(), 500), 120_000);
    }

    private EvidenceQuery trackingQuery(Context context) {
        if (context.window != null) return incidentQuery(context);
        Instant end = Instant.now();
        return new EvidenceQuery(end.minus(limits.getTrackingSearchWindow()), end,
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
        Investigation investigation;
        Environment environment;
        final Instant deadline;
        final EvidenceCollectionBudget budget;
        com.jmopsagent.domain.IncidentWindow window;
        final Map<String, List<ConnectorEvidence>> discoveredLogs = new java.util.HashMap<>();
        final Set<String> attemptedCodeFiles = new LinkedHashSet<>();
        int codeFilesRead;
        boolean trackingSearched;
        boolean explicitCurrent;
        String trackingId;
        Instant scopeReferenceTime;
        Instant lookupSelectedAt;
        final Map<String, List<DeploymentInfo>> historicalBuilds = new java.util.HashMap<>();
        final Set<String> historicalChangesCollected = new LinkedHashSet<>();
        final Set<String> historicalRepositoryCollected = new LinkedHashSet<>();
        final List<TraceEvent> traceEvents = new ArrayList<>();
        final Set<String> allowedServices = new LinkedHashSet<>();
        final Map<String, Boolean> probeResults = new java.util.HashMap<>();
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
            this(investigation, Instant.now().plus(limits.getMaxWallClock()), EvidenceCollectionBudget.initial(limits));
        }

        Context(Investigation investigation, Instant deadline, EvidenceCollectionBudget budget) {
            this.budget = budget;
            this.id = investigation.getId();
            this.investigation = investigation;
            this.environment = Environment.valueOf(investigation.getEnvironment().name());
            this.deadline = deadline;
            this.evidenceCount = investigation.getEvidenceItems().size();
            this.splunkSearches = investigation.getSplunkSearchCount();
            this.service = investigation.getService();
            this.window = investigation.incidentWindow();
            this.scopeReferenceTime = window == null ? Instant.now() : investigation.getEvidenceScopeResolvedAt();
            this.trackingId = investigation.getTrackingId();
            this.codeFilesRead = Math.max(investigation.getSourceFileReadCount(), (int) investigation.getEvidenceItems().stream()
                    .filter(item -> item.getEvidenceType() == EvidenceType.SOURCE_CODE).count());
            this.failureAt = window == null ? null
                    : investigation.getEvidenceItems().stream()
                            .filter(item -> HistoricalFailureEvidence.belongsTo(item, service))
                            .filter(item -> HistoricalFailureEvidence.isFailure(item, window))
                            .map(EvidenceItem::getOccurredAt).min(Comparator.naturalOrder()).orElse(null);
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
