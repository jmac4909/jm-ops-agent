package com.jmopsagent.splunk;

import java.util.List;
import java.util.regex.Pattern;

/** Generates only fixed SPL templates populated by strictly validated identifiers and values. */
final class SplunkQueryBuilder {
    private static final Pattern INDEX = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Pattern SERVICE_IDENTITY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,159}");
    private static final String PROJECTION = " | fields - _raw"
            + " | table _time trackingId service downstreamService httpStatus outcome severity message operation"
            + " targetUrl executionTime";
    private static final String ACTIVITY_PROJECTION = " | fields - _raw"
            + " | table _time service httpStatus operation trafficEventCount message";
    private static final String BUSINESS_CALL_PROJECTION = " | fields - _raw"
            + " | table _time trackingId service httpStatus operation httpMethod requestUri executionTime"
            + " routerRequestId jmopsSourceFormat";

    private final SplunkFieldNormalizer normalizer;

    SplunkQueryBuilder(SplunkFieldNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    String tracking(List<String> indexes, String trackingId, int maxResults) {
        return tracking(indexes, trackingId, List.of(), maxResults);
    }

    String tracking(List<String> indexes, String trackingId, List<String> profileNames, int maxResults) {
        return base(indexes, " \"" + trackingId + "\"")
                + pipeline(profileNames)
                + " | search trackingId=\"" + trackingId + "\""
                + finish(maxResults);
    }

    String gatewayTracking(List<String> indexes, String trackingId, int maxResults) {
        return base(indexes, " \"" + trackingId + "\"")
                + " | eval trackingId=coalesce('X-TrackingId','trackingId','tracking_id')"
                + " | eval service=coalesce('service','service_name','proxyName','proxy.name','apigee_proxy.name')"
                + " | eval downstreamService=coalesce('downstreamService','downstream_service','targetService')"
                + " | eval httpStatus=coalesce('httpStatus','http_status','http.status_code','status_code')"
                + " | eval outcome=coalesce('outcome',null())"
                + " | eval severity=coalesce('severity','level')"
                + " | eval message=coalesce('message','detail')"
                + " | eval operation=coalesce('operation','proxyName','proxy.name','apigee_proxy.name')"
                + " | eval targetUrl=coalesce('targetUrl','targetURL','target_url')"
                + " | eval executionTime=coalesce('executionTime','totalLatency(ms)',"
                + "'totalLatencyMs','total_latency_ms')"
                + " | search trackingId=\"" + trackingId + "\""
                + finish(maxResults);
    }

    String trackingApplicationLogs(List<String> indexes, String trackingId, List<String> serviceIdentities,
            List<String> profileNames, int maxResults) {
        return base(indexes, applicationLogPredicate(serviceIdentities) + " \"" + trackingId + "\"")
                + pipeline(profileNames)
                + serviceSearch(serviceIdentities)
                + " | search trackingId=\"" + trackingId + "\""
                + finish(maxResults);
    }

    String serviceErrors(List<String> indexes, List<String> serviceIdentities, int maxResults) {
        return serviceErrors(indexes, serviceIdentities, List.of(), maxResults);
    }

    String serviceErrors(List<String> indexes, List<String> serviceIdentities,
            List<String> profileNames, int maxResults) {
        return base(indexes, applicationLogPredicate(serviceIdentities)
                + " (ERROR OR Exception OR status>=500)") + pipeline(profileNames)
                + serviceSearch(serviceIdentities)
                + " | search (severity=\"ERROR\" OR httpStatus>=500 OR message=\"*Exception*\")"
                + finish(maxResults);
    }

    String serviceEvents(List<String> indexes, List<String> serviceIdentities, int maxResults) {
        return serviceEvents(indexes, serviceIdentities, List.of(), maxResults);
    }

    String serviceEvents(List<String> indexes, List<String> serviceIdentities,
            List<String> profileNames, int maxResults) {
        return base(indexes, applicationLogPredicate(serviceIdentities)) + pipeline(profileNames)
                + serviceSearch(serviceIdentities)
                + finish(maxResults);
    }

    String recentActivity(List<String> indexes, List<String> serviceIdentities, int maxResults) {
        return recentActivity(indexes, serviceIdentities, List.of(), maxResults);
    }

    String recentActivity(List<String> indexes, List<String> serviceIdentities,
            List<String> profileNames, int maxResults) {
        return base(indexes, applicationLogPredicate(serviceIdentities) + " "
                + normalizer.businessCallRawPredicate(profileNames)) + pipeline(profileNames)
                + serviceSearch(serviceIdentities)
                // Only classify a record as successful HTTP activity when both request-like operation
                // metadata and a concrete 2xx/3xx status are present. Unknown log records are excluded.
                + " | where isnotnull(operation) AND isnotnull(httpStatus)"
                + " AND tonumber(httpStatus)>=200 AND tonumber(httpStatus)<400"
                + " AND (isnull(severity) OR (upper(severity)!=\"ERROR\""
                + " AND upper(severity)!=\"FATAL\" AND upper(severity)!=\"SEVERE\"))"
                + " | bin _time span=5m"
                + " | stats count AS trafficEventCount BY _time service operation httpStatus"
                + " | eval message=\"Successful HTTP activity summary\""
                + finish(maxResults, ACTIVITY_PROJECTION);
    }

    String recentBusinessCalls(List<String> indexes, List<String> serviceIdentities, int maxResults) {
        return recentBusinessCalls(indexes, serviceIdentities, List.of(), maxResults);
    }

    String recentBusinessCalls(List<String> indexes, List<String> serviceIdentities,
            List<String> profileNames, int maxResults) {
        validateMaxResults(maxResults);
        return base(indexes, applicationLogPredicate(serviceIdentities)
                + " " + normalizer.businessCallRawPredicate(profileNames))
                // Bound the expensive field extraction as well as the final response.
                + " | head " + (maxResults + 1)
                + pipeline(profileNames)
                + serviceSearch(serviceIdentities)
                + " | where isnotnull(trackingId) AND isnotnull(httpStatus)"
                + " | dedup trackingId"
                + " | eval jmopsSourceFormat=\"application-log\""
                + finish(maxResults, BUSINESS_CALL_PROJECTION);
    }

    String recentHttpCalls(List<String> indexes, List<String> serviceIdentities, int maxResults) {
        return base(indexes, httpAccessPredicate(serviceIdentities)
                + " NOT \"actuator\" NOT \"eureka\"")
                + normalizer.pipeline(List.of())
                + serviceSearch(serviceIdentities)
                + " | where isnotnull(httpStatus)"
                + " | eval trackingId=null(),jmopsSourceFormat=\"http-access\""
                + finish(maxResults, BUSINESS_CALL_PROJECTION);
    }

    private static String base(List<String> indexes, String initialPredicate) {
        if (indexes == null || indexes.isEmpty()) throw new IllegalArgumentException("Splunk index is required");
        String indexClause = indexes.stream().map(index -> {
            if (!INDEX.matcher(index).matches()) throw new IllegalArgumentException("Invalid Splunk index");
            return "index=\"" + index + "\"";
        }).collect(java.util.stream.Collectors.joining(" OR ", "(", ")"));
        return "search " + indexClause + initialPredicate;
    }

    private static String serviceSearch(List<String> identities) {
        return exactFieldPredicate("service", identities, " | search (", ")");
    }

    private static String applicationLogPredicate(List<String> identities) {
        return " sourcetype=\"cf:logmessage\" "
                + exactFieldPredicate("cf_app_name", identities, "(", ")");
    }

    private static String httpAccessPredicate(List<String> identities) {
        return " sourcetype=\"cf:httpstartstop\" "
                + exactFieldPredicate("cf_app_name", identities, "(", ")");
    }

    private static String exactFieldPredicate(String field, List<String> identities, String prefix, String suffix) {
        if (identities == null || identities.isEmpty()) {
            throw new IllegalArgumentException("At least one Splunk service identity is required");
        }
        String clause = identities.stream().distinct().map(identity -> {
            if (!SERVICE_IDENTITY.matcher(identity).matches()) {
                throw new IllegalArgumentException("Invalid Splunk service identity");
            }
            return field + "=\"" + identity + "\"";
        }).collect(java.util.stream.Collectors.joining(" OR ", prefix, suffix));
        return clause;
    }

    private static String finish(int maxResults) {
        return finish(maxResults, PROJECTION);
    }

    private static String finish(int maxResults, String projection) {
        validateMaxResults(maxResults);
        // Ask for one sentinel row so the connector can truthfully report truncation.
        return " | head " + (maxResults + 1) + projection;
    }

    private String pipeline(List<String> profileNames) {
        return profileNames == null || profileNames.isEmpty()
                ? normalizer.pipeline() : normalizer.pipeline(profileNames);
    }

    private static void validateMaxResults(int maxResults) {
        if (maxResults < 1 || maxResults > 1_000) {
            throw new IllegalArgumentException("Splunk result limit is invalid");
        }
    }
}
