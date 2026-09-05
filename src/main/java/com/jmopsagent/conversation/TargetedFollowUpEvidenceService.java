package com.jmopsagent.conversation;

import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationEventType;
import com.jmopsagent.kubernetes.KubernetesConnector;
import com.jmopsagent.orchestration.ConnectorEvidenceMapper;
import com.jmopsagent.orchestration.InvestigationLimitsProperties;
import com.jmopsagent.orchestration.InvestigationStateService;
import com.jmopsagent.persistence.SanitizedEvidenceStore;
import com.jmopsagent.registry.ServiceDefinition;
import com.jmopsagent.registry.ServiceRegistry;
import com.jmopsagent.splunk.SplunkConnector;
import com.jmopsagent.splunk.SplunkConnectorResult;
import com.jmopsagent.splunk.SplunkSearchPermit;
import com.jmopsagent.splunk.SplunkSearchOutcome;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Performs the one supported live follow-up refresh. It deliberately exposes no generic connector dispatch:
 * an explicit recent-requests question can collect one bounded, read-only traffic sample for the localized service.
 */
@Service
public class TargetedFollowUpEvidenceService {
    private static final Duration RECENT_WINDOW = Duration.ofMinutes(30);
    private static final int MAX_RECENT_ITEMS = 20;
    private static final int MAX_RECENT_CONTENT_CHARACTERS = 50_000;

    private final InvestigationStateService state;
    private final SanitizedEvidenceStore evidenceStore;
    private final ConnectorEvidenceMapper mapper;
    private final ServiceRegistry registry;
    private final SplunkConnector splunk;
    private final KubernetesConnector kubernetes;
    private final InvestigationLimitsProperties limits;

    public TargetedFollowUpEvidenceService(InvestigationStateService state,
                                           SanitizedEvidenceStore evidenceStore,
                                           ConnectorEvidenceMapper mapper,
                                           ServiceRegistry registry,
                                           SplunkConnector splunk,
                                           KubernetesConnector kubernetes,
                                           InvestigationLimitsProperties limits) {
        this.state = state;
        this.evidenceStore = evidenceStore;
        this.mapper = mapper;
        this.registry = registry;
        this.splunk = splunk;
        this.kubernetes = kubernetes;
        this.limits = limits;
    }

    public CollectionResult collectRecentBusinessCalls(Investigation supplied) {
        Investigation investigation = state.snapshotWithEvidence(supplied.getId());
        String service = investigation.getService();
        if (service == null || service.isBlank()) {
            return CollectionResult.skipped("No localized service is available for a targeted refresh");
        }
        int remaining = limits.getMaxEvidenceItems() - investigation.getEvidenceItems().size();
        if (remaining <= 0) {
            state.note(investigation.getId(), InvestigationEventType.LIMIT_REACHED,
                    "Skipped the targeted follow-up refresh because the evidence-item limit was reached");
            return CollectionResult.skipped("The evidence-item limit was already reached");
        }

        int maximumItems = Math.min(Math.min(MAX_RECENT_ITEMS, limits.getMaxLogEvents()), remaining);
        Instant end = Instant.now();
        EvidenceQuery query = new EvidenceQuery(end.minus(RECENT_WINDOW), end, maximumItems,
                MAX_RECENT_CONTENT_CHARACTERS);
        Environment environment = Environment.valueOf(investigation.getEnvironment().name());

        state.note(investigation.getId(), InvestigationEventType.NOTE,
                "Collecting one bounded read-only recent-request sample for the follow-up");
        CollectionResult result = usesTas(service, investigation.getEnvironment())
                ? collectTas(investigation, service, environment, query, maximumItems)
                : collectKubernetes(investigation, service, environment, query, maximumItems);
        state.note(investigation.getId(), result.collectedItems() > 0
                        ? InvestigationEventType.EVIDENCE_COLLECTED : InvestigationEventType.NOTE,
                result.collectedItems() > 0
                        ? "Targeted follow-up refresh stored " + result.collectedItems() + " sanitized item(s)"
                        : "Targeted follow-up refresh completed without new evidence");
        return result;
    }

    private CollectionResult collectTas(Investigation investigation, String service, Environment environment,
                                         EvidenceQuery query, int maximumItems) {
        AtomicBoolean acquired = new AtomicBoolean();
        SplunkSearchPermit permit = () -> {
            boolean reserved = state.tryReserveSplunkSearch(
                    investigation.getId(), limits.getMaxSplunkSearches());
            if (reserved) acquired.set(true);
            return reserved;
        };
        try {
            SplunkConnectorResult response = splunk.searchRecentBusinessCallsDetailed(
                    service, environment, query, permit);
            if (response == null) return CollectionResult.attempted(0, "The traffic connector returned no result");
            if (response.outcome() == SplunkSearchOutcome.LIMIT_REACHED) {
                state.note(investigation.getId(), InvestigationEventType.LIMIT_REACHED,
                        "The targeted follow-up stopped at the investigation Splunk-search limit");
                String description = "The investigation Splunk-search limit was reached";
                return acquired.get() ? CollectionResult.attempted(0, description)
                        : CollectionResult.skipped(description);
            }
            if (!isUsable(response.outcome())) {
                return CollectionResult.attempted(0, outcomeDescription(response.outcome()));
            }
            int collected = persist(investigation, response.result().evidence(), maximumItems);
            if (collected == 0) {
                return CollectionResult.attempted(0, response.outcome() == SplunkSearchOutcome.PARTIAL_PARSE
                        ? "Recent request search returned no normalized metadata and reported partial parsing"
                        : "Recent request search completed with no matching metadata");
            }
            String description = response.result().truncated()
                    ? "Recent request metadata was sampled from a scan-capped result"
                    : "Recent request metadata was refreshed";
            return CollectionResult.attempted(collected, description);
        } catch (RuntimeException ignored) {
            return CollectionResult.attempted(0, "Recent request metadata was unavailable");
        }
    }

    private CollectionResult collectKubernetes(Investigation investigation, String service, Environment environment,
                                                EvidenceQuery query, int maximumItems) {
        try {
            List<ConnectorEvidence> logs = kubernetes.getRecentPodLogs(service, environment, query);
            int collected = persist(investigation, logs, maximumItems);
            return CollectionResult.attempted(collected, collected == 0
                    ? "Recent Kubernetes logs contained no bounded sample"
                    : "A bounded Kubernetes pod-log sample was refreshed; it is not guaranteed to represent complete business-call traffic");
        } catch (RuntimeException ignored) {
            return CollectionResult.attempted(0, "Recent Kubernetes logs were unavailable");
        }
    }

    private int persist(Investigation investigation, List<ConnectorEvidence> evidence, int maximumItems) {
        if (evidence == null || evidence.isEmpty()) return 0;
        int collected = 0;
        for (ConnectorEvidence item : evidence) {
            if (collected >= maximumItems) break;
            evidenceStore.append(investigation.getId(), mapper.map(item));
            collected++;
        }
        return collected;
    }

    private boolean usesTas(String service, DeploymentEnvironment environment) {
        Optional<ServiceDefinition> definition = registry.resolve(service);
        if (definition.isEmpty()) return false;
        Optional<String> configured = definition.get().attributeForEnvironment("runtime.platform", environment);
        if (configured.isPresent()) {
            String platform = configured.get().trim().toUpperCase(Locale.ROOT);
            if (platform.equals("TAS") || platform.equals("CF")) return true;
            if (platform.equals("KUBERNETES") || platform.equals("EKS")) return false;
        }
        return definition.get().attributeValue("tas.appPattern").isPresent()
                && definition.get().attributeForEnvironment("eks.namespace", environment).isEmpty();
    }

    private static boolean isUsable(SplunkSearchOutcome outcome) {
        return outcome == SplunkSearchOutcome.SUCCESS
                || outcome == SplunkSearchOutcome.PARTIAL_PARSE
                || outcome == SplunkSearchOutcome.NO_DATA;
    }

    private static String outcomeDescription(SplunkSearchOutcome outcome) {
        return switch (outcome) {
            case LIMIT_REACHED -> "The investigation Splunk-search limit was reached";
            case UNCONFIGURED -> "Recent request search is not configured for this service";
            case UNAUTHORIZED, FORBIDDEN -> "Recent request search was not authorized";
            case TIMEOUT -> "Recent request search timed out within its configured bound";
            case TLS_FAILURE -> "Recent request search failed TLS validation";
            case REDIRECT_REJECTED -> "Recent request search rejected an unexpected redirect";
            case REMOTE_FAILURE, PARSE_FAILURE -> "Recent request search was unavailable";
            case SUCCESS, PARTIAL_PARSE, NO_DATA -> "Recent request search completed";
        };
    }

    public record CollectionResult(boolean attempted, int collectedItems, String description) {
        static CollectionResult skipped(String description) {
            return new CollectionResult(false, 0, description);
        }

        static CollectionResult attempted(int items, String description) {
            return new CollectionResult(true, Math.max(0, items), description);
        }
    }
}
