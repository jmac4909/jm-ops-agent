package com.jmopsagent.splunk;

import static com.jmopsagent.connector.mock.MockFixtures.DEPLOYMENT_TIME;
import static com.jmopsagent.connector.mock.MockFixtures.FAILING_SERVICE;
import static com.jmopsagent.connector.mock.MockFixtures.REQUEST_TIME;
import static com.jmopsagent.connector.mock.MockFixtures.TRACKING_ID;
import static com.jmopsagent.connector.mock.MockFixtures.evidence;
import static com.jmopsagent.connector.mock.MockFixtures.scenario;

import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.EvidenceSource;
import com.jmopsagent.connector.EvidenceType;
import com.jmopsagent.connector.SplunkSearchResult;
import com.jmopsagent.connector.TraceEvent;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local-mock")
public class MockSplunkConnector implements SplunkConnector {
    @Override
    public SplunkSearchResult searchByTrackingId(String trackingId, Environment environment, EvidenceQuery query) {
        String safeTrackingId = ConnectorInputValidator.trackingId(trackingId);
        if (!TRACKING_ID.equalsIgnoreCase(safeTrackingId)) {
            return new SplunkSearchResult(List.of(), List.of(), 0, false);
        }
        List<TraceEvent> trace = List.of(
                trace(REQUEST_TIME, "edge-gateway", "POST /catalog/query", "SUCCESS", 200,
                        "identity-service", "Gateway called Identity Service; downstream returned 200"),
                trace(REQUEST_TIME.plusMillis(240), "identity-service", "validateIdentity", "SUCCESS", 200,
                        null, "Identity Service completed successfully"),
                trace(REQUEST_TIME.plusMillis(410), "edge-gateway", "GET /catalog/items", "FAILURE", 500,
                        FAILING_SERVICE, "Catalog Service returned 500 INTERNAL_SERVER_ERROR"),
                trace(REQUEST_TIME.plusMillis(515), FAILING_SERVICE, "lookupItem", "FAILURE", 500,
                        null, "Request failed while resolving database configuration"));
        List<ConnectorEvidence> items = trace.stream().limit(query.maxResults()).map(event -> evidence(
                "splunk-trace-" + (trace.indexOf(event) + 1), EvidenceSource.SPLUNK, EvidenceType.TRACE_EVENT,
                event.timestamp(), event.service(), environment, event.summary(),
                "trackingId=" + TRACKING_ID + " operation=" + event.operation() + " outcome=" + event.outcome()
                        + " httpStatus=" + event.httpStatus(),
                Map.of("trackingId", TRACKING_ID, "outcome", event.outcome(), "httpStatus",
                        Integer.toString(event.httpStatus())), 0.98)).toList();
        return new SplunkSearchResult(items, trace, 4, query.maxResults() < 4);
    }

    @Override
    public SplunkSearchResult searchErrorsForService(String service, Environment environment, EvidenceQuery query) {
        String safeService = ConnectorInputValidator.service(service);
        List<ConnectorEvidence> errors = new ArrayList<>();
        switch (scenario(safeService)) {
            case "bad-config" -> {
                errors.add(evidence("splunk-config-error", EvidenceSource.SPLUNK, EvidenceType.ERROR_PATTERN,
                        REQUEST_TIME.plusMillis(500), safeService, environment,
                        "Parameter resolution errors began after deployment",
                        "ParameterResolutionException: Could not resolve parameter /demo/catalog/db-url",
                        Map.of("severity", "ERROR", "frequency", "18", "stackTraceGroup", "parameter-resolution"), 0.97));
                errors.add(evidence("splunk-http-500", EvidenceSource.SPLUNK, EvidenceType.APPLICATION_LOG,
                        REQUEST_TIME.plusMillis(515), safeService, environment, "HTTP 500 response",
                        "lookupItem failed with INTERNAL_SERVER_ERROR after database URL configuration was unavailable",
                        Map.of("severity", "ERROR", "frequency", "18", "trackingId", TRACKING_ID), 0.96));
            }
            case "readiness-failure" -> errors.add(evidence("splunk-readiness-error", EvidenceSource.SPLUNK,
                    EvidenceType.ERROR_PATTERN, REQUEST_TIME, safeService, environment, "Readiness probe errors",
                    "Health check reported DOWN because required dependency initialization did not complete",
                    Map.of("severity", "ERROR", "frequency", "9"), 0.9));
            case "deployment-failure" -> errors.add(evidence("splunk-old-version", EvidenceSource.SPLUNK,
                    EvidenceType.APPLICATION_LOG, DEPLOYMENT_TIME, safeService, environment,
                    "No logs from the candidate deployment", "Existing instances continue serving the previous revision",
                    Map.of("severity", "INFO", "frequency", "1"), 0.85));
            case "downstream-500" -> errors.add(evidence("splunk-downstream-500", EvidenceSource.SPLUNK,
                    EvidenceType.ERROR_PATTERN, REQUEST_TIME, safeService, environment, "Downstream API returned 500",
                    "WebClientResponseException.InternalServerError from inventory-api /availability",
                    Map.of("severity", "ERROR", "frequency", "12", "dependency", "inventory-api"), 0.94));
            case "database-error" -> errors.add(evidence("splunk-database-error", EvidenceSource.SPLUNK,
                    EvidenceType.ERROR_PATTERN, REQUEST_TIME, safeService, environment, "Database connection refused",
                    "org.postgresql.util.PSQLException: Connection to database endpoint refused",
                    Map.of("severity", "ERROR", "frequency", "22", "dependency", "POSTGRESQL"), 0.96));
            default -> { }
        }
        List<ConnectorEvidence> bounded = errors.stream().limit(query.maxResults()).map(item -> truncate(item, query)).toList();
        return new SplunkSearchResult(bounded, List.of(), errors.size(), bounded.size() < errors.size());
    }

    @Override
    public SplunkSearchResult searchAroundTimestamp(String service, Environment environment, Instant timestamp,
            EvidenceQuery query) {
        if (timestamp == null) throw new IllegalArgumentException("timestamp is required");
        return searchErrorsForService(service, environment, query);
    }

    @Override
    public SplunkSearchResult searchServiceEvents(String service, Environment environment, EvidenceQuery query) {
        return searchErrorsForService(service, environment, query);
    }

    @Override
    public SplunkSearchResult searchRecentActivity(String service, Environment environment, EvidenceQuery query) {
        String safeService = ConnectorInputValidator.service(service);
        List<ConnectorEvidence> activity = List.of(evidence("splunk-recent-activity", EvidenceSource.SPLUNK,
                EvidenceType.RECENT_ACTIVITY, REQUEST_TIME, safeService, environment,
                "Recent successful HTTP activity",
                "operation=GET /catalog/items statusClass=2xx trafficEventCount=24",
                Map.of("operation", "GET /catalog/items", "statusClass", "2xx", "trafficEventCount", "24",
                        "countSemantics", "synthetic-request-events"), 0.9));
        return new SplunkSearchResult(activity.stream().limit(query.maxResults()).toList(), List.of(), 1, false);
    }

    @Override
    public SplunkSearchResult searchRecentBusinessCalls(
            String service, Environment environment, EvidenceQuery query) {
        String safeService = ConnectorInputValidator.service(service);
        List<ConnectorEvidence> calls = List.of(evidence("splunk-recent-business-call", EvidenceSource.SPLUNK,
                EvidenceType.RECENT_BUSINESS_CALLS, REQUEST_TIME, safeService, environment,
                "Recent HTTP call for GET /catalog/items returned 200",
                "trackingId=DEMO-CALL-001 status=200 operation=GET /catalog/items executionTime=42ms bodyIncluded=false",
                Map.of("trackingId", "DEMO-CALL-001", "httpStatus", "200", "operation", "GET /catalog/items",
                        "executionTime", "42ms", "bodyIncluded", "false", "scanCapped", "true"), 0.9));
        return new SplunkSearchResult(calls.stream().limit(query.maxResults()).toList(), List.of(), 1, true);
    }

    @Override
    public SplunkSearchResult getErrorPatterns(String service, Environment environment, EvidenceQuery query) {
        return searchErrorsForService(service, environment, query);
    }

    private static TraceEvent trace(Instant timestamp, String service, String operation, String outcome,
            int status, String downstream, String summary) {
        return new TraceEvent(timestamp, TRACKING_ID, service, operation, outcome, status, downstream, summary,
                URI.create("https://example.invalid/mock/splunk/" + TRACKING_ID), Map.of("environment", "TEST"));
    }

    private static ConnectorEvidence truncate(ConnectorEvidence item, EvidenceQuery query) {
        String content = item.content();
        if (content.length() <= query.maxContentCharacters()) return item;
        return new ConnectorEvidence(item.externalId(), item.source(), item.type(), item.timestamp(), item.service(),
                item.environment(), item.summary(), content.substring(0, query.maxContentCharacters()), item.sourceUrl(),
                item.metadata(), item.reliability());
    }
}
