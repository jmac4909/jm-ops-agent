package com.jmopsagent.ui;

import com.jmopsagent.domain.EvidenceItem;
import com.jmopsagent.domain.EvidenceType;
import com.jmopsagent.domain.InvestigationEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class InvestigationViewAssembler {
    private final ObjectMapper objectMapper;

    public InvestigationViewAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ServiceChainNode> serviceChain(List<EvidenceItem> evidence) {
        Map<String, MutableNode> nodes = new LinkedHashMap<>();
        evidence.stream().filter(item -> item.getEvidenceType() == EvidenceType.CALL_CHAIN)
                .sorted(Comparator.comparing(EvidenceItem::getOccurredAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(item -> addTrace(nodes, item));
        return nodes.values().stream().map(MutableNode::freeze).toList();
    }

    public List<TimelineEntry> timeline(List<InvestigationEvent> events, List<EvidenceItem> evidence) {
        List<TimelineEntry> result = new ArrayList<>();
        for (EvidenceItem item : evidence) {
            if (item.getOccurredAt() != null && isTimelineEvidence(item.getEvidenceType())) {
                result.add(new TimelineEntry(item.getOccurredAt(), item.getSummary(),
                        item.getSourceSystem().name(), item.getEvidenceType().name()));
            }
        }
        for (InvestigationEvent event : events) {
            result.add(new TimelineEntry(event.getOccurredAt(), event.getMessage(),
                    event.getStatus() == null ? null : event.getStatus().name(), event.getType().name()));
        }
        return result.stream().sorted(Comparator.comparing(TimelineEntry::occurredAt,
                Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    private void addTrace(Map<String, MutableNode> nodes, EvidenceItem item) {
        JsonNode metadata = parse(item.getMetadataJson());
        String service = normalize(item.getService());
        String downstream = text(metadata, "downstreamService");
        Integer statusCode = metadata != null && metadata.has("httpStatus") && !metadata.get("httpStatus").isNull()
                ? metadata.get("httpStatus").asInt() : null;
        String outcome = text(metadata, "outcome");
        boolean failed = statusCode != null && statusCode >= 500
                || outcome != null && (outcome.toUpperCase(Locale.ROOT).contains("FAIL")
                || outcome.toUpperCase(Locale.ROOT).contains("ERROR"));
        if (service != null) {
            MutableNode caller = nodes.computeIfAbsent(service, MutableNode::new);
            if (downstream == null) caller.observe(failed, statusCode, item.getSummary());
            else if (caller.status.equals("UNKNOWN")) caller.observe(false, null, "Call observed");
        }
        if (downstream != null) {
            nodes.computeIfAbsent(normalize(downstream), MutableNode::new)
                    .observe(failed, statusCode, item.getSummary());
        }
    }

    private boolean isTimelineEvidence(EvidenceType type) {
        return switch (type) {
            case DEPLOYMENT_METADATA, BUILD, BUILD_CHANGE, WORKLOAD_HEALTH, POD_EVENT, ERROR_LOG, POD_LOG,
                    LOG_PATTERN, CONFIGURATION, DEPENDENCY_STATUS, TRACKING_EVENT, CALL_CHAIN -> true;
            default -> false;
        };
    }

    private JsonNode parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException ex) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        String value = node.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class MutableNode {
        final String service;
        String status = "UNKNOWN";
        Integer statusCode;
        String detail;

        MutableNode(String service) { this.service = service; }

        void observe(boolean failed, Integer code, String summary) {
            if (failed || !status.equals("FAILURE")) {
                status = failed ? "FAILURE" : "SUCCESS";
                statusCode = code;
                detail = summary;
            }
        }

        ServiceChainNode freeze() { return new ServiceChainNode(service, status, statusCode, detail); }
    }
}
