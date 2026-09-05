package com.jmopsagent.splunk;

import java.util.List;
import java.util.Locale;

enum SplunkCanonicalField {
    TIME("_time", List.of("_time")),
    TRACKING_ID("trackingId", List.of("trackingId", "tracking_id", "X-TrackingId")),
    SERVICE("service", List.of("service", "service_name", "app", "cf_app_name", "apigee_proxy.name")),
    DOWNSTREAM_SERVICE("downstreamService", List.of("downstreamService", "downstream_service", "target_service")),
    HTTP_STATUS("httpStatus", List.of(
            "httpStatus", "http_status", "http.status_code", "status", "statusCode", "status_code")),
    OUTCOME("outcome", List.of("outcome")),
    SEVERITY("severity", List.of("severity", "level")),
    MESSAGE("message", List.of("message", "exception", "stack_trace")),
    OPERATION("operation", List.of("operation", "proxyName", "proxy.name", "apigee_proxy.name")),
    HTTP_METHOD("httpMethod", List.of("httpMethod", "method")),
    REQUEST_URI("requestUri", List.of("requestUri", "uri")),
    TARGET_URL("targetUrl", List.of("targetUrl", "targetURL", "target_url")),
    EXECUTION_TIME("executionTime", List.of(
            "executionTime", "duration_ms", "totalLatency(ms)", "totalLatencyMs", "total_latency_ms")),
    ROUTER_REQUEST_ID("routerRequestId", List.of("routerRequestId", "request_id"));

    private final String outputName;
    private final List<String> defaultPaths;

    SplunkCanonicalField(String outputName, List<String> defaultPaths) {
        this.outputName = outputName;
        this.defaultPaths = defaultPaths;
    }

    String outputName() {
        return outputName;
    }

    List<String> defaultPaths() {
        return defaultPaths;
    }

    static SplunkCanonicalField parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Splunk canonical field name is required");
        }
        String normalized = value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        if (normalized.equals("STATUS")) normalized = "HTTP_STATUS";
        if (normalized.equals("TRACKINGID")) normalized = "TRACKING_ID";
        if (normalized.equals("DOWNSTREAMSERVICE")) normalized = "DOWNSTREAM_SERVICE";
        if (normalized.equals("HTTPSTATUS")) normalized = "HTTP_STATUS";
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported Splunk canonical field: " + value);
        }
    }
}
