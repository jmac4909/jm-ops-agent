package com.jmopsagent.splunk;

import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.SplunkSearchResult;
import java.time.Instant;
import java.util.Objects;

public interface SplunkConnector {
    SplunkSearchResult searchByTrackingId(String trackingId, Environment environment, EvidenceQuery query);
    SplunkSearchResult searchErrorsForService(String service, Environment environment, EvidenceQuery query);
    SplunkSearchResult searchAroundTimestamp(String service, Environment environment, Instant timestamp, EvidenceQuery query);
    SplunkSearchResult searchServiceEvents(String service, Environment environment, EvidenceQuery query);
    SplunkSearchResult getErrorPatterns(String service, Environment environment, EvidenceQuery query);

    /** Bounded non-error traffic metadata. Existing implementations safely fall back to service events. */
    default SplunkSearchResult searchRecentActivity(String service, Environment environment, EvidenceQuery query) {
        return searchServiceEvents(service, environment, query);
    }

    /** Bounded request metadata (tracking ID, status, method and latency), never request/response bodies. */
    default SplunkSearchResult searchRecentBusinessCalls(
            String service, Environment environment, EvidenceQuery query) {
        return searchRecentActivity(service, environment, query);
    }

    default SplunkConnectorResult searchByTrackingIdDetailed(
            String trackingId, Environment environment, EvidenceQuery query) {
        return SplunkConnectorResult.fromLegacy(searchByTrackingId(trackingId, environment, query));
    }

    default SplunkConnectorResult searchByTrackingIdDetailed(
            String trackingId, Environment environment, EvidenceQuery query, SplunkSearchPermit permit) {
        return acquire(permit)
                ? searchByTrackingIdDetailed(trackingId, environment, query)
                : SplunkConnectorResult.limitReached();
    }

    default SplunkConnectorResult searchErrorsForServiceDetailed(
            String service, Environment environment, EvidenceQuery query) {
        return SplunkConnectorResult.fromLegacy(searchErrorsForService(service, environment, query));
    }

    default SplunkConnectorResult searchErrorsForServiceDetailed(
            String service, Environment environment, EvidenceQuery query, SplunkSearchPermit permit) {
        return acquire(permit)
                ? searchErrorsForServiceDetailed(service, environment, query)
                : SplunkConnectorResult.limitReached();
    }

    default SplunkConnectorResult searchAroundTimestampDetailed(
            String service, Environment environment, Instant timestamp, EvidenceQuery query) {
        return SplunkConnectorResult.fromLegacy(searchAroundTimestamp(service, environment, timestamp, query));
    }

    default SplunkConnectorResult searchAroundTimestampDetailed(
            String service, Environment environment, Instant timestamp, EvidenceQuery query,
            SplunkSearchPermit permit) {
        return acquire(permit)
                ? searchAroundTimestampDetailed(service, environment, timestamp, query)
                : SplunkConnectorResult.limitReached();
    }

    default SplunkConnectorResult searchServiceEventsDetailed(
            String service, Environment environment, EvidenceQuery query) {
        return SplunkConnectorResult.fromLegacy(searchServiceEvents(service, environment, query));
    }

    default SplunkConnectorResult searchServiceEventsDetailed(
            String service, Environment environment, EvidenceQuery query, SplunkSearchPermit permit) {
        return acquire(permit)
                ? searchServiceEventsDetailed(service, environment, query)
                : SplunkConnectorResult.limitReached();
    }

    default SplunkConnectorResult searchRecentActivityDetailed(
            String service, Environment environment, EvidenceQuery query) {
        return SplunkConnectorResult.fromLegacy(searchRecentActivity(service, environment, query));
    }

    default SplunkConnectorResult searchRecentActivityDetailed(
            String service, Environment environment, EvidenceQuery query, SplunkSearchPermit permit) {
        return acquire(permit)
                ? searchRecentActivityDetailed(service, environment, query)
                : SplunkConnectorResult.limitReached();
    }

    default SplunkConnectorResult searchRecentBusinessCallsDetailed(
            String service, Environment environment, EvidenceQuery query) {
        return SplunkConnectorResult.fromLegacy(searchRecentBusinessCalls(service, environment, query));
    }

    default SplunkConnectorResult searchRecentBusinessCallsDetailed(
            String service, Environment environment, EvidenceQuery query, SplunkSearchPermit permit) {
        return acquire(permit)
                ? searchRecentBusinessCallsDetailed(service, environment, query)
                : SplunkConnectorResult.limitReached();
    }

    default SplunkConnectorResult getErrorPatternsDetailed(
            String service, Environment environment, EvidenceQuery query) {
        return SplunkConnectorResult.fromLegacy(getErrorPatterns(service, environment, query));
    }

    default SplunkConnectorResult getErrorPatternsDetailed(
            String service, Environment environment, EvidenceQuery query, SplunkSearchPermit permit) {
        return acquire(permit)
                ? getErrorPatternsDetailed(service, environment, query)
                : SplunkConnectorResult.limitReached();
    }

    private static boolean acquire(SplunkSearchPermit permit) {
        return Objects.requireNonNull(permit, "permit").tryAcquire();
    }
}
