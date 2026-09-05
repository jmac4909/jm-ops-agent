package com.jmopsagent.orchestration;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.jmopsagent.connector.CommitChange;
import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.DeploymentInfo;
import com.jmopsagent.connector.TraceEvent;
import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.domain.EvidenceReliability;
import com.jmopsagent.domain.EvidenceSource;
import com.jmopsagent.domain.EvidenceType;
import com.jmopsagent.sanitization.EvidenceDraft;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ConnectorEvidenceMapper {
    private final ObjectMapper objectMapper;

    public ConnectorEvidenceMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EvidenceDraft map(ConnectorEvidence value) {
        Map<String, Object> metadata = new LinkedHashMap<>(value.metadata());
        if (value.externalId() != null) metadata.put("externalId", value.externalId());
        return new EvidenceDraft(
                EvidenceSource.valueOf(value.source().name()),
                mapType(value.type()),
                value.timestamp(),
                value.service(),
                DeploymentEnvironment.valueOf(value.environment().name()),
                value.summary(),
                value.content(),
                value.sourceUrl() == null ? null : value.sourceUrl().toString(),
                json(metadata),
                reliability(value.reliability()));
    }

    public EvidenceDraft trace(TraceEvent value, DeploymentEnvironment environment) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("trackingId", value.trackingId());
        metadata.put("operation", value.operation());
        metadata.put("outcome", value.outcome());
        metadata.put("httpStatus", value.httpStatus());
        metadata.put("downstreamService", value.downstreamService());
        metadata.putAll(value.metadata());
        return new EvidenceDraft(EvidenceSource.SPLUNK, EvidenceType.CALL_CHAIN, value.timestamp(), value.service(),
                environment, value.summary() == null ? "Tracking event for " + value.service() : value.summary(),
                value.summary(), value.sourceUrl() == null ? null : value.sourceUrl().toString(), json(metadata),
                EvidenceReliability.HIGH);
    }

    public EvidenceDraft deployment(DeploymentInfo value) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("jobName", value.jobName());
        metadata.put("buildNumber", value.buildNumber());
        metadata.put("result", value.result());
        metadata.put("commitSha", value.commitSha());
        metadata.put("changes", value.changes());
        metadata.put("failedStages", value.failedStages());
        metadata.put("consoleErrors", value.consoleErrors());
        metadata.putAll(value.metadata());
        String summary = "Jenkins build " + value.jobName() + " #" + value.buildNumber() + " was " + value.result();
        String content = "Commit: " + value.commitSha() + "\nChanges: " + String.join("; ", value.changes())
                + "\nFailed stages: " + String.join("; ", value.failedStages())
                + "\nConsole errors: " + String.join("; ", value.consoleErrors());
        return new EvidenceDraft(EvidenceSource.JENKINS, EvidenceType.DEPLOYMENT_METADATA, value.timestamp(),
                value.service(), DeploymentEnvironment.valueOf(value.environment().name()), summary, content,
                value.sourceUrl() == null ? null : value.sourceUrl().toString(), json(metadata), EvidenceReliability.HIGH);
    }

    public EvidenceDraft change(CommitChange value, String service, DeploymentEnvironment environment) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("commitSha", value.commitSha());
        metadata.put("author", value.author());
        metadata.put("changedPaths", value.changedPaths());
        String content = value.title() + "\nPaths: " + String.join(", ", value.changedPaths()) + "\n" + value.boundedDiff();
        return new EvidenceDraft(EvidenceSource.GITLAB, EvidenceType.COMMIT_DIFF, value.committedAt(), service,
                environment, "Recent change " + value.commitSha() + ": " + value.title(), content, null,
                json(metadata), EvidenceReliability.HIGH);
    }

    public EvidenceDraft sourceFile(String service, DeploymentEnvironment environment, String revision,
                                    String path, String content) {
        return new EvidenceDraft(EvidenceSource.GITLAB, EvidenceType.SOURCE_CODE, null, service, environment,
                "Read-only source inspection: " + path, content, null,
                json(Map.of("revision", revision, "path", path)), EvidenceReliability.HIGH);
    }

    private EvidenceType mapType(com.jmopsagent.connector.EvidenceType type) {
        return switch (type) {
            case TRACE_EVENT -> EvidenceType.TRACKING_EVENT;
            case WORKLOAD_HEALTH -> EvidenceType.WORKLOAD_HEALTH;
            case POD_EVENT -> EvidenceType.POD_EVENT;
            case APPLICATION_LOG -> EvidenceType.POD_LOG;
            case DEPLOYMENT -> EvidenceType.DEPLOYMENT_METADATA;
            case CONFIGURATION -> EvidenceType.CONFIGURATION;
            case NETWORK -> EvidenceType.ROUTE;
            case BUILD -> EvidenceType.BUILD;
            case SOURCE_CHANGE -> EvidenceType.COMMIT_DIFF;
            case SOURCE_FILE -> EvidenceType.SOURCE_CODE;
            case ERROR_PATTERN -> EvidenceType.LOG_PATTERN;
            case RECENT_ACTIVITY -> EvidenceType.TRAFFIC_SUMMARY;
            case RECENT_BUSINESS_CALLS -> EvidenceType.RECENT_BUSINESS_CALLS;
            case DEPENDENCY_HEALTH -> EvidenceType.DEPENDENCY_STATUS;
            case RUNBOOK -> EvidenceType.RUNBOOK_EXCERPT;
        };
    }

    private EvidenceReliability reliability(double value) {
        if (value >= .8) return EvidenceReliability.HIGH;
        if (value >= .5) return EvidenceReliability.MEDIUM;
        if (value > 0) return EvidenceReliability.LOW;
        return EvidenceReliability.UNKNOWN;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            return "{}";
        }
    }
}
