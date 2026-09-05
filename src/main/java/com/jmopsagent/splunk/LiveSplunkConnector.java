package com.jmopsagent.splunk;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.jmopsagent.connector.ConnectorEndpointValidator;
import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.EvidenceSource;
import com.jmopsagent.connector.EvidenceType;
import com.jmopsagent.connector.SplunkSearchResult;
import com.jmopsagent.connector.TraceEvent;
import com.jmopsagent.registry.ServiceDefinition;
import com.jmopsagent.registry.ServiceRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** Read-only, bounded Splunk REST connector. */
@Component
@Profile("local-live")
public class LiveSplunkConnector implements SplunkConnector {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveSplunkConnector.class);
    private static final Pattern INDEX = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Pattern RUNTIME_IDENTITY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,159}");
    private static final Pattern STATUS = Pattern.compile("(?:^|\\D)([1-5]\\d{2})(?:\\D|$)");
    private static final Pattern NUMBERS = Pattern.compile("\\b\\d+\\b");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final SplunkSearchResult EMPTY = new SplunkSearchResult(List.of(), List.of(), 0, false);

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final Map<Environment, List<String>> indexes;
    private final Map<Environment, List<String>> gatewayIndexes;
    private final ServiceRegistry serviceRegistry;
    private final java.net.URI searchUiBase;
    private final java.net.URI searchExportEndpoint;
    private final SplunkFieldNormalizer normalizer;
    private final SplunkQueryBuilder queryBuilder;
    private final Duration requestTimeout;
    private final boolean configured;

    public LiveSplunkConnector(WebClient.Builder builder, ObjectMapper objectMapper, ServiceRegistry serviceRegistry,
            SplunkProperties properties) {
        String safeBaseUrl;
        SplunkProperties.Credentials credentials;
        List<SplunkFieldProfile.Validated> fieldProfiles;
        Map<Environment, List<String>> configuredIndexes;
        Map<Environment, List<String>> configuredGatewayIndexes;
        Duration configuredRequestTimeout;
        boolean configurationValid = true;
        try {
            safeBaseUrl = ConnectorEndpointValidator.optionalHttpsBaseUrl(properties.getBaseUrl(), "Splunk");
            credentials = properties.validatedCredentials();
            safeBaseUrl = normalizeBaseUrl(safeBaseUrl, credentials.mode());
            fieldProfiles = properties.validatedFieldProfiles();
            configuredIndexes = Map.of(Environment.DEV, parseIndexes(properties.getDevIndexes()),
                    Environment.TEST, parseIndexes(properties.getTestIndexes()));
            configuredGatewayIndexes = Map.of(
                    Environment.DEV, parseIndexes(properties.getDevGatewayIndexes()),
                    Environment.TEST, parseIndexes(properties.getTestGatewayIndexes()));
            configuredRequestTimeout = properties.validatedRequestTimeout();
        } catch (IllegalArgumentException ignored) {
            // External settings may be repaired after startup. Keep this bean inert and unauthenticated.
            safeBaseUrl = "";
            credentials = SplunkProperties.Credentials.token(SplunkAuthMode.BEARER_TOKEN, "");
            fieldProfiles = List.of();
            configuredIndexes = Map.of(Environment.DEV, List.of(), Environment.TEST, List.of());
            configuredGatewayIndexes = Map.of(Environment.DEV, List.of(), Environment.TEST, List.of());
            configuredRequestTimeout = SplunkProperties.DEFAULT_REQUEST_TIMEOUT;
            configurationValid = false;
        }
        this.normalizer = new SplunkFieldNormalizer(fieldProfiles);
        this.queryBuilder = new SplunkQueryBuilder(normalizer);
        this.objectMapper = objectMapper;
        this.serviceRegistry = serviceRegistry;
        this.indexes = configuredIndexes;
        this.gatewayIndexes = configuredGatewayIndexes;
        this.requestTimeout = configuredRequestTimeout;
        this.configured = configurationValid && !safeBaseUrl.isBlank() && credentials.present();
        this.searchUiBase = safeBaseUrl.isBlank() ? null : java.net.URI.create(safeBaseUrl);
        this.searchExportEndpoint = safeBaseUrl.isBlank() ? null
                : java.net.URI.create(safeBaseUrl + "/services/search/jobs/export");

        WebClient.Builder configuredBuilder = builder.clone();
        if (!safeBaseUrl.isBlank()) {
            configuredBuilder.baseUrl(safeBaseUrl);
            if (credentials.present()) {
                configuredBuilder.filter(new SplunkAuthenticationFilter(searchUiBase, credentials));
            }
        }
        // Authentication cannot be replayed to a redirect because redirects are always disabled.
        configuredBuilder.clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                .followRedirect(false).responseTimeout(requestTimeout)));
        this.client = configuredBuilder.build();
    }

    @Override
    public SplunkSearchResult searchByTrackingId(String trackingId, Environment environment, EvidenceQuery query) {
        return searchByTrackingIdDetailed(trackingId, environment, query).result();
    }

    @Override
    public SplunkConnectorResult searchByTrackingIdDetailed(
            String trackingId, Environment environment, EvidenceQuery query) {
        return searchByTrackingIdDetailed(trackingId, environment, query, SplunkSearchPermit.unlimited());
    }

    @Override
    public SplunkConnectorResult searchByTrackingIdDetailed(
            String trackingId, Environment environment, EvidenceQuery query, SplunkSearchPermit permit) {
        String safeTrackingId = ConnectorInputValidator.trackingId(trackingId);
        validate(environment, query);
        List<String> preferredGatewayIndexes = gatewayIndexesForTracking(environment);
        if (ready(preferredGatewayIndexes)) {
            SplunkConnectorResult gatewayResult = search(permit, environment, query,
                    queryBuilder.gatewayTracking(preferredGatewayIndexes, safeTrackingId, query.maxResults()),
                    SearchKind.TRACKING);
            if (gatewayResult.outcome() != SplunkSearchOutcome.NO_DATA) return gatewayResult;
        }
        List<String> selectedIndexes = indexesForTracking(environment).stream()
                .filter(index -> !preferredGatewayIndexes.contains(index)).toList();
        if (!ready(selectedIndexes)) {
            return preferredGatewayIndexes.isEmpty() ? unconfigured()
                    : new SplunkConnectorResult(EMPTY, SplunkSearchOutcome.NO_DATA);
        }
        List<String> applicationProfiles = applicationFieldProfileNames(environment);
        List<String> applicationIdentities = applicationIdentities(environment);
        if (applicationIdentities.isEmpty()) {
            return new SplunkConnectorResult(EMPTY, SplunkSearchOutcome.NO_DATA);
        }
        SplunkConnectorResult applicationResult = search(permit, environment, query,
                queryBuilder.trackingApplicationLogs(selectedIndexes, safeTrackingId, applicationIdentities,
                        applicationProfiles, query.maxResults()),
                SearchKind.TRACKING);
        return applicationResult;
    }

    @Override
    public SplunkSearchResult searchErrorsForService(String service, Environment environment, EvidenceQuery query) {
        return searchErrorsForServiceDetailed(service, environment, query).result();
    }

    @Override
    public SplunkConnectorResult searchErrorsForServiceDetailed(
            String service, Environment environment, EvidenceQuery query) {
        return serviceSearch(service, environment, query, SearchKind.ERRORS, SplunkSearchPermit.unlimited());
    }

    @Override
    public SplunkConnectorResult searchErrorsForServiceDetailed(
            String service, Environment environment, EvidenceQuery query, SplunkSearchPermit permit) {
        return serviceSearch(service, environment, query, SearchKind.ERRORS, permit);
    }

    @Override
    public SplunkSearchResult searchAroundTimestamp(String service, Environment environment, Instant timestamp,
            EvidenceQuery query) {
        return searchAroundTimestampDetailed(service, environment, timestamp, query).result();
    }

    @Override
    public SplunkConnectorResult searchAroundTimestampDetailed(
            String service, Environment environment, Instant timestamp, EvidenceQuery query) {
        return searchAroundTimestampDetailed(service, environment, timestamp, query, SplunkSearchPermit.unlimited());
    }

    @Override
    public SplunkConnectorResult searchAroundTimestampDetailed(
            String service, Environment environment, Instant timestamp, EvidenceQuery query,
            SplunkSearchPermit permit) {
        if (timestamp == null) throw new IllegalArgumentException("timestamp is required");
        return serviceSearch(service, environment, query, SearchKind.EVENTS, permit);
    }

    @Override
    public SplunkSearchResult searchServiceEvents(String service, Environment environment, EvidenceQuery query) {
        return searchServiceEventsDetailed(service, environment, query).result();
    }

    @Override
    public SplunkConnectorResult searchServiceEventsDetailed(
            String service, Environment environment, EvidenceQuery query) {
        return serviceSearch(service, environment, query, SearchKind.EVENTS, SplunkSearchPermit.unlimited());
    }

    @Override
    public SplunkConnectorResult searchServiceEventsDetailed(
            String service, Environment environment, EvidenceQuery query, SplunkSearchPermit permit) {
        return serviceSearch(service, environment, query, SearchKind.EVENTS, permit);
    }

    @Override
    public SplunkSearchResult searchRecentActivity(String service, Environment environment, EvidenceQuery query) {
        return searchRecentActivityDetailed(service, environment, query).result();
    }

    @Override
    public SplunkConnectorResult searchRecentActivityDetailed(
            String service, Environment environment, EvidenceQuery query) {
        return serviceSearch(service, environment, query, SearchKind.ACTIVITY, SplunkSearchPermit.unlimited());
    }

    @Override
    public SplunkConnectorResult searchRecentActivityDetailed(
            String service, Environment environment, EvidenceQuery query, SplunkSearchPermit permit) {
        return serviceSearch(service, environment, query, SearchKind.ACTIVITY, permit);
    }

    @Override
    public SplunkSearchResult searchRecentBusinessCalls(
            String service, Environment environment, EvidenceQuery query) {
        return searchRecentBusinessCallsDetailed(service, environment, query).result();
    }

    @Override
    public SplunkConnectorResult searchRecentBusinessCallsDetailed(
            String service, Environment environment, EvidenceQuery query) {
        return serviceSearch(service, environment, query, SearchKind.BUSINESS_CALLS,
                SplunkSearchPermit.unlimited());
    }

    @Override
    public SplunkConnectorResult searchRecentBusinessCallsDetailed(
            String service, Environment environment, EvidenceQuery query, SplunkSearchPermit permit) {
        return serviceSearch(service, environment, query, SearchKind.BUSINESS_CALLS, permit);
    }

    @Override
    public SplunkSearchResult getErrorPatterns(String service, Environment environment, EvidenceQuery query) {
        return getErrorPatternsDetailed(service, environment, query).result();
    }

    @Override
    public SplunkConnectorResult getErrorPatternsDetailed(
            String service, Environment environment, EvidenceQuery query) {
        return serviceSearch(service, environment, query, SearchKind.ERRORS, SplunkSearchPermit.unlimited());
    }

    @Override
    public SplunkConnectorResult getErrorPatternsDetailed(
            String service, Environment environment, EvidenceQuery query, SplunkSearchPermit permit) {
        return serviceSearch(service, environment, query, SearchKind.ERRORS, permit);
    }

    private SplunkConnectorResult serviceSearch(String service, Environment environment, EvidenceQuery query,
            SearchKind kind, SplunkSearchPermit permit) {
        String safeService = ConnectorInputValidator.service(service);
        validate(environment, query);
        if (isKubernetesService(safeService, environment)) return unconfigured();
        List<String> selectedIndexes = indexesForService(safeService, environment);
        if (!ready(selectedIndexes)) return unconfigured();
        List<String> identities = serviceIdentities(safeService, environment);
        if (identities.isEmpty()) return unconfigured();
        List<String> profileNames = serviceFieldProfileNames(safeService, environment);
        String spl = switch (kind) {
            case ERRORS -> queryBuilder.serviceErrors(selectedIndexes, identities, profileNames, query.maxResults());
            case ACTIVITY -> queryBuilder.recentActivity(selectedIndexes, identities, profileNames, query.maxResults());
            case BUSINESS_CALLS -> queryBuilder.recentBusinessCalls(
                    selectedIndexes, identities, profileNames, query.maxResults());
            default -> queryBuilder.serviceEvents(selectedIndexes, identities, profileNames, query.maxResults());
        };
        SplunkConnectorResult result = search(permit, environment, query, spl, kind);
        if (kind == SearchKind.BUSINESS_CALLS && result.outcome() == SplunkSearchOutcome.NO_DATA) {
            return search(permit, environment, query,
                    queryBuilder.recentHttpCalls(selectedIndexes, identities, query.maxResults()), kind);
        }
        return result;
    }

    private SplunkConnectorResult search(SplunkSearchPermit permit, Environment environment, EvidenceQuery query,
            String spl, SearchKind kind) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("search", spl);
        form.add("output_mode", "json");
        form.add("earliest_time", query.from().toString());
        form.add("latest_time", query.to().toString());
        if (permit == null || !permit.tryAcquire()) return SplunkConnectorResult.limitReached();
        try {
            HttpResult response = client.post().uri(searchExportEndpoint)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .exchangeToMono(value -> {
                        HttpStatusCode status = value.statusCode();
                        if (status.is2xxSuccessful()) {
                            return value.bodyToMono(String.class).defaultIfEmpty("")
                                    .map(body -> new HttpResult(SplunkSearchOutcome.SUCCESS, body));
                        }
                        SplunkSearchOutcome outcome;
                        if (status.value() == 401) outcome = SplunkSearchOutcome.UNAUTHORIZED;
                        else if (status.value() == 403) outcome = SplunkSearchOutcome.FORBIDDEN;
                        else if (status.is3xxRedirection()) outcome = SplunkSearchOutcome.REDIRECT_REJECTED;
                        else outcome = SplunkSearchOutcome.REMOTE_FAILURE;
                        return value.releaseBody().thenReturn(new HttpResult(outcome, ""));
                    })
                    .block(requestTimeout);
            if (response == null) return outcome(kind, SplunkSearchOutcome.REMOTE_FAILURE);
            if (response.outcome() != SplunkSearchOutcome.SUCCESS) return outcome(kind, response.outcome());
            return parse(response.body(), environment, query, kind);
        } catch (RuntimeException ex) {
            return outcome(kind, classifyException(ex));
        }
    }

    SplunkConnectorResult parse(String body, Environment environment, EvidenceQuery query, SearchKind kind) {
        if (body == null || body.isBlank()) return new SplunkConnectorResult(EMPTY, SplunkSearchOutcome.NO_DATA);
        List<JsonNode> rawResults = new ArrayList<>();
        int[] malformed = {0};
        body.lines().filter(line -> !line.isBlank()).limit(query.maxResults() + 1L).forEach(line -> {
            try {
                JsonNode envelope = objectMapper.readTree(line);
                if (envelope.has("results") && envelope.path("results").isArray()) {
                    envelope.path("results").forEach(result -> {
                        if (rawResults.size() <= query.maxResults() && result.isObject() && !result.isEmpty()) {
                            rawResults.add(result);
                        }
                    });
                } else {
                    JsonNode result = envelope.has("result") ? envelope.path("result") : envelope;
                    if (rawResults.size() <= query.maxResults() && result.isObject()
                            && !result.isEmpty() && !result.has("messages")) {
                        rawResults.add(result);
                    }
                }
            } catch (Exception ignored) {
                malformed[0]++;
            }
        });
        if (rawResults.isEmpty()) {
            SplunkSearchOutcome outcome = malformed[0] > 0
                    ? SplunkSearchOutcome.PARSE_FAILURE : SplunkSearchOutcome.NO_DATA;
            if (outcome == SplunkSearchOutcome.PARSE_FAILURE) logOutcome(kind, outcome);
            return new SplunkConnectorResult(EMPTY, outcome);
        }

        int rawCount = rawResults.size();
        // Business-call extraction intentionally caps raw events before deduplication. Even when fewer
        // rows survive, completeness cannot be proven, so expose the scan cap truthfully.
        boolean truncated = rawCount > query.maxResults() || kind == SearchKind.BUSINESS_CALLS;
        List<JsonNode> bounded = rawResults.stream().limit(query.maxResults()).toList();
        Map<String, Group> groups = new LinkedHashMap<>();
        List<TraceEvent> traceEvents = new ArrayList<>();
        Map<String, String> canonicalServices = serviceIdentityMap(environment);
        for (JsonNode result : bounded) {
            String message = message(result);
            String fingerprint = kind == SearchKind.BUSINESS_CALLS ? businessSignature(result)
                    : kind == SearchKind.ACTIVITY ? activitySignature(result, message) : signature(message);
            groups.compute(fingerprint, (key, group) -> group == null ? new Group(result, message, 1)
                    : new Group(group.sample(), group.message(), group.count() + 1));
            if (kind == SearchKind.TRACKING) traceEvents.add(toTraceEvent(result, message, canonicalServices));
        }

        List<ConnectorEvidence> evidence = new ArrayList<>();
        int index = 0;
        int remainingCharacters = query.maxContentCharacters();
        for (Map.Entry<String, Group> entry : groups.entrySet()) {
            if (remainingCharacters <= 0) {
                truncated = true;
                break;
            }
            Group group = entry.getValue();
            JsonNode sample = group.sample();
            int permittedCharacters = Math.min(4_000, remainingCharacters);
            String content = group.message();
            String service = canonicalService(normalizer.value(sample, SplunkCanonicalField.SERVICE), canonicalServices);
            String severity = normalizer.value(sample, SplunkCanonicalField.SEVERITY);
            Integer status = parseStatus(normalizer.value(sample, SplunkCanonicalField.HTTP_STATUS));
            Instant timestamp = parseInstant(normalizer.value(sample, SplunkCanonicalField.TIME));
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("frequency", Integer.toString(group.count()));
            metadata.put("signature", entry.getKey());
            if (!severity.isBlank()) metadata.put("severity", severity);
            if (status != null) metadata.put("httpStatusClass", (status / 100) + "xx");
            Long trafficEventCount = positiveLong(sample.get("trafficEventCount"));
            if (kind == SearchKind.ACTIVITY && trafficEventCount != null) {
                metadata.put("trafficEventCount", Long.toString(trafficEventCount));
                metadata.put("bucketDuration", "5m");
                metadata.put("countSemantics", "matching-log-events");
            }
            if (kind == SearchKind.BUSINESS_CALLS) {
                String sourceFormat = text(sample, "jmopsSourceFormat");
                if (!"http-access".equals(sourceFormat)) {
                    addIfPresent(metadata, "trackingId",
                            normalizer.value(sample, SplunkCanonicalField.TRACKING_ID));
                }
                addIfPresent(metadata, "httpStatus", normalizer.value(sample, SplunkCanonicalField.HTTP_STATUS));
                addIfPresent(metadata, "operation", normalizer.value(sample, SplunkCanonicalField.OPERATION));
                addIfPresent(metadata, "httpMethod", normalizer.value(sample, SplunkCanonicalField.HTTP_METHOD));
                addIfPresent(metadata, "requestUri", normalizer.value(sample, SplunkCanonicalField.REQUEST_URI));
                addIfPresent(metadata, "executionTime", normalizer.value(sample,
                        SplunkCanonicalField.EXECUTION_TIME));
                addIfPresent(metadata, "routerRequestId",
                        normalizer.value(sample, SplunkCanonicalField.ROUTER_REQUEST_ID));
                metadata.put("sourceFormat", sourceFormat.isBlank() ? "application-log" : sourceFormat);
                metadata.put("scanCapped", "true");
                metadata.put("bodyIncluded", "false");
            }
            boolean error = kind == SearchKind.ERRORS || isError(severity, status);
            EvidenceType type = kind == SearchKind.BUSINESS_CALLS ? EvidenceType.RECENT_BUSINESS_CALLS
                    : kind == SearchKind.ACTIVITY ? EvidenceType.RECENT_ACTIVITY
                    : error && group.count() > 1 ? EvidenceType.ERROR_PATTERN
                    : EvidenceType.APPLICATION_LOG;
            String summary;
            if (kind == SearchKind.ACTIVITY) {
                String operation = normalizer.value(sample, SplunkCanonicalField.OPERATION);
                summary = "Successful HTTP activity in 5-minute bucket"
                        + (operation.isBlank() || "unknown".equals(operation) ? "" : " for " + summarize(operation))
                        + (trafficEventCount == null ? "" : " (" + trafficEventCount + " matching log events)");
                content = summary;
            } else if (kind == SearchKind.BUSINESS_CALLS) {
                String operation = businessOperation(sample);
                String trackingId = normalizer.value(sample, SplunkCanonicalField.TRACKING_ID);
                String executionTime = normalizer.value(sample, SplunkCanonicalField.EXECUTION_TIME);
                summary = "Recent HTTP call"
                        + (operation.isBlank() ? "" : " for " + summarize(operation))
                        + (status == null ? "" : " returned " + status);
                content = "trackingId=" + (trackingId.isBlank() ? "unavailable" : trackingId)
                        + " status=" + (status == null ? "unavailable" : status)
                        + " operation=" + (operation.isBlank() ? "unavailable" : operation)
                        + " executionTime=" + (executionTime.isBlank() ? "unavailable" : executionTime)
                        + " bodyIncluded=false";
            } else {
                summary = group.count() > 1
                        ? (error ? "Repeated error pattern" : "Repeated log pattern")
                                + " (" + group.count() + " occurrences)"
                        : summarize(content);
            }
            boolean sampleTruncated = content.length() > permittedCharacters;
            if (sampleTruncated) {
                content = content.substring(0, permittedCharacters);
                metadata.put("contentTruncated", "true");
                truncated = true;
            }
            remainingCharacters -= content.length();
            evidence.add(new ConnectorEvidence("splunk-result-" + (++index), EvidenceSource.SPLUNK, type,
                    timestamp, service, environment, summary, content, searchUiBase, metadata, 0.9));
        }
        traceEvents.sort(Comparator.comparing(TraceEvent::timestamp,
                Comparator.nullsLast(Comparator.naturalOrder())));
        SplunkSearchResult result = new SplunkSearchResult(evidence, traceEvents, rawCount, truncated);
        SplunkSearchOutcome outcome = malformed[0] > 0
                ? SplunkSearchOutcome.PARTIAL_PARSE : SplunkSearchOutcome.SUCCESS;
        if (outcome == SplunkSearchOutcome.PARTIAL_PARSE) logOutcome(kind, outcome);
        return new SplunkConnectorResult(result, outcome);
    }

    private TraceEvent toTraceEvent(JsonNode result, String message, Map<String, String> canonicalServices) {
        String trackingId = normalizer.value(result, SplunkCanonicalField.TRACKING_ID);
        String service = canonicalService(normalizer.value(result, SplunkCanonicalField.SERVICE), canonicalServices);
        String downstream = canonicalService(
                normalizer.value(result, SplunkCanonicalField.DOWNSTREAM_SERVICE), canonicalServices);
        Integer status = parseStatus(normalizer.value(result, SplunkCanonicalField.HTTP_STATUS));
        String outcome = normalizer.value(result, SplunkCanonicalField.OUTCOME);
        if (outcome.isBlank()) outcome = status != null && status >= 400 ? "FAILURE" : "SUCCESS";
        Map<String, String> metadata = new LinkedHashMap<>();
        addIfPresent(metadata, "targetUrl", normalizer.value(result, SplunkCanonicalField.TARGET_URL));
        addIfPresent(metadata, "executionTime", normalizer.value(result, SplunkCanonicalField.EXECUTION_TIME));
        return new TraceEvent(parseInstant(normalizer.value(result, SplunkCanonicalField.TIME)), trackingId, service,
                normalizer.value(result, SplunkCanonicalField.OPERATION), outcome, status, downstream,
                summarize(message), searchUiBase, metadata);
    }

    private List<String> indexesForTracking(Environment environment) {
        LinkedHashSet<String> result = new LinkedHashSet<>(indexes.getOrDefault(environment, List.of()));
        serviceRegistry.all().forEach(definition -> addRegisteredIndexes(result, definition,
                "splunk.indexes.application", "splunk.indexes.tas"));
        return List.copyOf(result);
    }

    private List<String> gatewayIndexesForTracking(Environment environment) {
        LinkedHashSet<String> result = new LinkedHashSet<>(gatewayIndexes.getOrDefault(environment, List.of()));
        serviceRegistry.all().forEach(definition -> addRegisteredIndexes(result, definition,
                "splunk.indexes.gateway", "splunk.indexes.apigee"));
        return List.copyOf(result);
    }

    private List<String> indexesForService(String service, Environment environment) {
        LinkedHashSet<String> result = new LinkedHashSet<>(indexes.getOrDefault(environment, List.of()));
        serviceRegistry.resolve(service).ifPresent(definition -> addRegisteredIndexes(result, definition,
                "splunk.indexes.application", "splunk.indexes.tas"));
        return List.copyOf(result);
    }

    private static void addRegisteredIndexes(LinkedHashSet<String> result, ServiceDefinition definition,
            String... paths) {
        for (String path : paths) {
            confirmedAttributeValues(definition, path).forEach(index -> {
                if (!INDEX.matcher(index).matches()) throw new IllegalArgumentException("Invalid registry Splunk index");
                result.add(index);
            });
        }
    }

    private List<String> serviceIdentities(String service, Environment environment) {
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        serviceRegistry.resolve(service).ifPresent(definition ->
                identities.addAll(confirmedAttributeValues(
                        definition, "splunk.appNames." + environment.name())));
        identities.forEach(identity -> {
            if (!RUNTIME_IDENTITY.matcher(identity).matches()) {
                throw new IllegalArgumentException("Invalid registry Splunk application identity");
            }
        });
        return List.copyOf(identities);
    }

    private List<String> serviceFieldProfileNames(String service, Environment environment) {
        return serviceRegistry.resolve(service).map(definition -> fieldProfileNames(definition, environment))
                .orElseGet(List::of);
    }

    private List<String> applicationFieldProfileNames(Environment environment) {
        return serviceRegistry.all().stream().filter(definition -> !isKubernetesDefinition(definition, environment))
                .flatMap(definition -> fieldProfileNames(definition, environment).stream())
                .distinct().toList();
    }

    private List<String> applicationIdentities(Environment environment) {
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        serviceRegistry.all().stream().filter(definition -> !isKubernetesDefinition(definition, environment))
                .forEach(definition -> identities.addAll(confirmedAttributeValues(
                        definition, "splunk.appNames." + environment.name())));
        identities.forEach(identity -> {
            if (!RUNTIME_IDENTITY.matcher(identity).matches()) {
                throw new IllegalArgumentException("Invalid registry Splunk application identity");
            }
        });
        return List.copyOf(identities);
    }

    private static List<String> fieldProfileNames(ServiceDefinition definition, Environment environment) {
        return environmentThenGeneric(definition, "splunk.fieldProfiles", environment);
    }

    private static List<String> environmentThenGeneric(
            ServiceDefinition definition, String path, Environment environment) {
        List<String> environmentValues = confirmedAttributeValues(
                definition, path + "." + environment.name());
        return environmentValues.isEmpty() ? confirmedAttributeValues(definition, path) : environmentValues;
    }

    private boolean isKubernetesService(String service, Environment environment) {
        return serviceRegistry.resolve(service).map(definition -> isKubernetesDefinition(definition, environment))
                .orElse(false);
    }

    private static boolean isKubernetesDefinition(ServiceDefinition definition, Environment environment) {
        return definition.attributeForEnvironment("runtime.platform",
                        com.jmopsagent.domain.DeploymentEnvironment.valueOf(environment.name()))
                .map(platform -> platform.equalsIgnoreCase("EKS") || platform.equalsIgnoreCase("KUBERNETES"))
                .orElse(false);
    }

    private Map<String, String> serviceIdentityMap(Environment environment) {
        Map<String, String> result = new LinkedHashMap<>();
        for (ServiceDefinition definition : serviceRegistry.all()) {
            LinkedHashSet<String> identities = new LinkedHashSet<>();
            identities.add(definition.service());
            identities.addAll(confirmedAttributeValues(
                    definition, "splunk.appNames." + environment.name()));
            identities.addAll(confirmedAttributeValues(
                    definition, "splunk.gatewayNames." + environment.name()));
            for (String identity : identities) {
                if (!RUNTIME_IDENTITY.matcher(identity).matches()) {
                    throw new IllegalArgumentException("Invalid registry Splunk application identity");
                }
                String normalized = identity.toLowerCase(Locale.ROOT);
                String previous = result.putIfAbsent(normalized, definition.service());
                if (previous != null && !previous.equals(definition.service())) {
                    throw new IllegalArgumentException("Splunk application identity is assigned to multiple services");
                }
            }
        }
        return Map.copyOf(result);
    }

    private static List<String> confirmedAttributeValues(ServiceDefinition definition, String path) {
        return definition.attribute(path)
                .filter(value -> value.confirmed())
                .map(value -> value.values())
                .orElseGet(List::of);
    }

    private String message(JsonNode result) {
        String value = normalizer.value(result, SplunkCanonicalField.MESSAGE);
        return value.isBlank() ? "Log payload omitted at source; normalized event metadata was retained" : value;
    }

    static String signature(String value) {
        String firstMeaningfulLine = value.lines().filter(line -> !line.isBlank()).findFirst().orElse(value);
        String normalized = UUID.matcher(firstMeaningfulLine).replaceAll("<uuid>");
        normalized = NUMBERS.matcher(normalized).replaceAll("<n>");
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String activitySignature(JsonNode result, String message) {
        return signature(message + "|" + normalizer.value(result, SplunkCanonicalField.TIME)
                + "|" + normalizer.value(result, SplunkCanonicalField.SERVICE)
                + "|" + normalizer.value(result, SplunkCanonicalField.OPERATION)
                + "|" + normalizer.value(result, SplunkCanonicalField.HTTP_STATUS));
    }

    private String businessSignature(JsonNode result) {
        return signature(normalizer.value(result, SplunkCanonicalField.TRACKING_ID)
                + "|" + normalizer.value(result, SplunkCanonicalField.ROUTER_REQUEST_ID)
                + "|" + normalizer.value(result, SplunkCanonicalField.TIME)
                + "|" + normalizer.value(result, SplunkCanonicalField.SERVICE)
                + "|" + normalizer.value(result, SplunkCanonicalField.OPERATION)
                + "|" + normalizer.value(result, SplunkCanonicalField.HTTP_METHOD)
                + "|" + normalizer.value(result, SplunkCanonicalField.REQUEST_URI)
                + "|" + normalizer.value(result, SplunkCanonicalField.HTTP_STATUS));
    }

    private String businessOperation(JsonNode result) {
        String operation = normalizer.value(result, SplunkCanonicalField.OPERATION);
        if (!operation.isBlank()) return operation;
        String method = normalizer.value(result, SplunkCanonicalField.HTTP_METHOD);
        String uri = normalizer.value(result, SplunkCanonicalField.REQUEST_URI);
        return (method + " " + uri).trim();
    }

    private static String text(JsonNode result, String field) {
        JsonNode value = result == null ? null : result.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static Long positiveLong(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            long value = node.isNumber() ? node.longValue() : Long.parseLong(node.asText());
            return value > 0 && value <= 1_000_000_000L ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private SplunkConnectorResult unconfigured() {
        return new SplunkConnectorResult(EMPTY, SplunkSearchOutcome.UNCONFIGURED);
    }

    private boolean ready(List<String> selectedIndexes) {
        return configured && selectedIndexes != null && !selectedIndexes.isEmpty();
    }

    private static void validate(Environment environment, EvidenceQuery query) {
        if (environment == null) throw new IllegalArgumentException("Environment is required");
        if (query == null) throw new IllegalArgumentException("Evidence query is required");
    }

    private SplunkConnectorResult outcome(SearchKind kind, SplunkSearchOutcome outcome) {
        logOutcome(kind, outcome);
        return new SplunkConnectorResult(EMPTY, outcome);
    }

    private void logOutcome(SearchKind kind, SplunkSearchOutcome outcome) {
        if (outcome != SplunkSearchOutcome.NO_DATA && outcome != SplunkSearchOutcome.UNCONFIGURED) {
            LOGGER.warn("Splunk operation={} outcome={}", kind.logName(), outcome);
        }
    }

    private static SplunkSearchOutcome classifyException(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof javax.net.ssl.SSLException) {
                return SplunkSearchOutcome.TLS_FAILURE;
            }
            if (current instanceof TimeoutException
                    || current.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("timeout")) {
                return SplunkSearchOutcome.TIMEOUT;
            }
            current = current.getCause();
        }
        return SplunkSearchOutcome.REMOTE_FAILURE;
    }

    private static boolean isError(String severity, Integer status) {
        return "ERROR".equalsIgnoreCase(severity) || (status != null && status >= 500);
    }

    private static String canonicalService(String raw, Map<String, String> aliases) {
        if (raw == null || raw.isBlank()) return "";
        String trimmed = raw.trim();
        return aliases.getOrDefault(trimmed.toLowerCase(Locale.ROOT), trimmed);
    }

    private static List<String> parseIndexes(String value) {
        if (value == null || value.isBlank()) return List.of();
        return value.lines().flatMap(line -> java.util.Arrays.stream(line.split(","))).map(String::trim)
                .filter(item -> !item.isBlank()).peek(item -> {
                    if (!INDEX.matcher(item).matches()) throw new IllegalArgumentException("Invalid configured Splunk index");
                }).distinct().toList();
    }

    private static Integer parseStatus(String value) {
        if (value == null || value.isBlank()) return null;
        Matcher matcher = STATUS.matcher(value);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? Instant.EPOCH : Instant.parse(value);
        } catch (DateTimeParseException ex) {
            return Instant.EPOCH;
        }
    }

    private static String summarize(String value) {
        String line = value.lines().filter(item -> !item.isBlank()).findFirst().orElse("Splunk event").trim();
        return line.substring(0, Math.min(line.length(), 240));
    }

    static String normalizeBaseUrl(String safeBaseUrl, SplunkAuthMode authMode) {
        if (safeBaseUrl == null || safeBaseUrl.isBlank() || authMode != SplunkAuthMode.SESSION_CSRF) {
            return safeBaseUrl == null ? "" : safeBaseUrl;
        }
        java.net.URI uri = java.net.URI.create(safeBaseUrl);
        String requiredPath = "/en-US/splunkd/__raw";
        if (uri.getPath().endsWith(requiredPath)) return safeBaseUrl;
        if (uri.getPath().endsWith("/en-US")) return safeBaseUrl + "/splunkd/__raw";
        if (uri.getPath().endsWith("/en-US/splunkd")) return safeBaseUrl + "/__raw";
        return safeBaseUrl + requiredPath;
    }

    private static void addIfPresent(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) metadata.put(key, value);
    }

    enum SearchKind {
        TRACKING("tracking-trace"), ERRORS("service-errors"), EVENTS("service-events"),
        ACTIVITY("recent-activity"), BUSINESS_CALLS("recent-business-calls");

        private final String logName;
        SearchKind(String logName) { this.logName = logName; }
        String logName() { return logName; }
    }

    private record Group(JsonNode sample, String message, int count) {}
    private record HttpResult(SplunkSearchOutcome outcome, String body) {}
}
