package com.jmopsagent.claude;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ClaudeResponseParser {
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,256}");
    private static final Pattern USAGE_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,200}");
    private final ObjectMapper objectMapper;

    public ClaudeResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ClaudeInvocationResult parse(String stdout, Instant startedAt, Instant completedAt,
        Set<String> allowedEvidenceIds) {
        try {
            JsonNode envelope = objectMapper.readTree(stripBom(stdout));
            if (envelope.path("is_error").asBoolean(false)) {
                return failed(startedAt, completedAt, text(envelope, "result", "Claude reported an error"));
            }
            JsonNode decisionNode = envelope.get("structured_output");
            if (decisionNode == null || decisionNode.isNull()) {
                String result = text(envelope, "result", null);
                if (result == null) {
                    return failed(startedAt, completedAt, "Claude response did not contain structured_output or result");
                }
                decisionNode = objectMapper.readTree(stripCodeFence(result));
            }
            JsonNode normalizedDecision = normalizeNextEvidenceRequests(decisionNode);
            validateDecisionStructure(normalizedDecision);
            ReasoningDecision decision = objectMapper.treeToValue(normalizedDecision, ReasoningDecision.class);
            validate(decision, allowedEvidenceIds == null ? Set.of() : allowedEvidenceIds);
            String sessionId = text(envelope, "session_id", null);
            if (sessionId != null && !SESSION_ID.matcher(sessionId).matches()) {
                throw new IllegalArgumentException("session_id is invalid");
            }
            Integer turns = envelope.has("num_turns") ? envelope.get("num_turns").asInt() : null;
            if (turns != null && (turns < 0 || turns > 1_000)) {
                throw new IllegalArgumentException("num_turns is outside the accepted bound");
            }
            BigDecimal cost = envelope.has("total_cost_usd") ? envelope.get("total_cost_usd").decimalValue() : null;
            if (cost != null && (cost.signum() < 0 || cost.compareTo(new BigDecimal("1000000")) > 0)) {
                throw new IllegalArgumentException("total_cost_usd is outside the accepted bound");
            }
            Map<String, Object> usage = boundedUsage(envelope.get("usage"));
            return new ClaudeInvocationResult(
                    sessionId,
                    decision,
                    startedAt,
                    completedAt,
                    Duration.between(startedAt, completedAt),
                    turns,
                    cost,
                    usage,
                    null,
                    false);
        } catch (RuntimeException ex) {
            return failed(startedAt, completedAt, "Invalid Claude structured response: " + ex.getMessage());
        }
    }

    /**
     * Enforces the same closed, required-field contract when Claude CLI schema mode is unavailable.
     * This runs on the JSON tree before record constructors can turn missing collections or primitive
     * values into apparently valid defaults.
     */
    private static void validateDecisionStructure(JsonNode decision) {
        requireObjectWithFields(decision,
                Set.of("status", "summary", "hypotheses", "nextEvidenceRequests",
                        "rootCauseCategory", "recommendedActions"),
                Set.of("status", "summary", "hypotheses", "nextEvidenceRequests",
                        "rootCauseCategory", "recommendedActions"));
        requireText(decision.get("status"));
        requireText(decision.get("summary"));
        requireText(decision.get("rootCauseCategory"));

        JsonNode hypotheses = requireArray(decision.get("hypotheses"));
        for (JsonNode hypothesis : hypotheses) {
            requireObjectWithFields(hypothesis,
                    Set.of("cause", "confidence", "evidenceIds"),
                    Set.of("cause", "confidence", "evidenceIds"));
            requireText(hypothesis.get("cause"));
            JsonNode confidence = hypothesis.get("confidence");
            if (confidence == null || !confidence.isNumber()) {
                throw new IllegalArgumentException("decision does not match the required JSON contract");
            }
            JsonNode evidenceIds = requireArray(hypothesis.get("evidenceIds"));
            for (JsonNode evidenceId : evidenceIds) requireText(evidenceId);
        }

        JsonNode requests = requireArray(decision.get("nextEvidenceRequests"));
        for (JsonNode request : requests) {
            requireObjectWithFields(request, Set.of("type", "service", "reason"), Set.of("type", "reason"));
            requireText(request.get("type"));
            requireText(request.get("reason"));
            JsonNode service = request.get("service");
            if (service != null && !service.isNull() && !service.isTextual()) {
                throw new IllegalArgumentException("decision does not match the required JSON contract");
            }
        }

        JsonNode actions = requireArray(decision.get("recommendedActions"));
        for (JsonNode action : actions) requireText(action);
    }

    private static void requireObjectWithFields(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("decision does not match the required JSON contract");
        }
        Set<String> actual = new HashSet<>();
        node.propertyNames().forEach(actual::add);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
            throw new IllegalArgumentException("decision does not match the required JSON contract");
        }
    }

    private static JsonNode requireArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("decision does not match the required JSON contract");
        }
        return node;
    }

    private static void requireText(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException("decision does not match the required JSON contract");
        }
    }

    private void validate(ReasoningDecision decision, Set<String> allowedEvidenceIds) {
        if (decision == null || decision.status() == null || decision.rootCauseCategory() == null
                || decision.summary() == null || decision.summary().isBlank()) {
            throw new IllegalArgumentException("required decision fields are missing");
        }
        if (decision.summary().length() > 8_000 || decision.hypotheses().size() > 10
                || decision.nextEvidenceRequests().size() > 8 || decision.recommendedActions().size() > 12) {
            throw new IllegalArgumentException("decision exceeds configured response bounds");
        }
        Set<String> references = new HashSet<>();
        for (Hypothesis hypothesis : decision.hypotheses()) {
            if (hypothesis == null || hypothesis.cause() == null || hypothesis.cause().isBlank()
                    || hypothesis.cause().length() > 2_000
                    || hypothesis.confidence() < 0 || hypothesis.confidence() > 1
                    || hypothesis.evidenceIds().size() > 30
                    || hypothesis.evidenceIds().stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 200)) {
                throw new IllegalArgumentException("hypothesis is invalid");
            }
            references.addAll(hypothesis.evidenceIds());
        }
        if (!allowedEvidenceIds.containsAll(references)) {
            references.removeAll(allowedEvidenceIds);
            throw new IllegalArgumentException("unknown evidence IDs: " + references);
        }
        for (NextEvidenceRequest request : decision.nextEvidenceRequests()) {
            if (request == null || request.type() == null || request.reason() == null || request.reason().isBlank()
                    || request.reason().length() > 1_000
                    || request.service() != null && request.service().length() > 200) {
                throw new IllegalArgumentException("unsupported or malformed next evidence request");
            }
        }
        if (decision.recommendedActions().stream().anyMatch(action -> action == null || action.isBlank()
                || action.length() > 1_000)) {
            throw new IllegalArgumentException("recommended action is invalid");
        }
        if (decision.status() == ReasoningStatus.NEEDS_MORE_EVIDENCE
                && decision.nextEvidenceRequests().isEmpty()) {
            throw new IllegalArgumentException("more-evidence decision did not include an approved request");
        }
        if (decision.rootCauseCategory() != com.jmopsagent.domain.RootCauseCategory.UNKNOWN
                && (decision.hypotheses().isEmpty()
                || decision.hypotheses().stream().anyMatch(hypothesis -> hypothesis.evidenceIds().isEmpty()))) {
            throw new IllegalArgumentException("a concrete diagnosis must cite supplied evidence");
        }
    }

    private Map<String, Object> boundedUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isNull()) return Map.of();
        if (!usageNode.isObject() || usageNode.toString().length() > 50_000) {
            throw new IllegalArgumentException("usage metadata is invalid or exceeds the accepted bound");
        }
        return numericUsageObject(usageNode, 0, new int[]{0});
    }

    private Map<String, Object> numericUsageObject(JsonNode node, int depth, int[] fieldCount) {
        if (depth > 5) throw new IllegalArgumentException("usage metadata nesting exceeds the accepted bound");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : node.propertyNames()) {
            if (!USAGE_KEY.matcher(key).matches() || ++fieldCount[0] > 200) {
                throw new IllegalArgumentException("usage metadata fields exceed the accepted bound");
            }
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) continue;
            if (value.isIntegralNumber()) result.put(key, value.longValue());
            else if (value.isFloatingPointNumber()) result.put(key, value.decimalValue());
            else if (value.isBoolean()) result.put(key, value.asBoolean());
            else if (value.isObject()) result.put(key, numericUsageObject(value, depth + 1, fieldCount));
            // Free-form strings and arrays are deliberately not persisted as usage metadata.
        }
        return result;
    }

    private ClaudeInvocationResult failed(Instant startedAt, Instant completedAt, String error) {
        return new ClaudeInvocationResult(null, null, startedAt, completedAt,
                Duration.between(startedAt, completedAt), null, (BigDecimal) null, Map.of(), error, false);
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? fallback : child.asText();
    }

    private String stripCodeFence(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            int newline = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (newline >= 0 && end > newline) {
                return trimmed.substring(newline + 1, end).trim();
            }
        }
        return trimmed;
    }

    private JsonNode normalizeNextEvidenceRequests(JsonNode decisionNode) {
        if (!(decisionNode instanceof ObjectNode object)) return decisionNode;
        JsonNode requests = object.get("nextEvidenceRequests");
        if (requests == null || !requests.isArray()) return decisionNode;
        ArrayNode normalized = objectMapper.createArrayNode();
        boolean changed = false;
        for (JsonNode request : requests) {
            if (!request.isTextual()) {
                normalized.add(request);
                continue;
            }
            String raw = request.asText().trim().toUpperCase(Locale.ROOT);
            EvidenceRequestType type;
            try {
                type = EvidenceRequestType.valueOf(raw);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("unsupported next evidence request", ex);
            }
            ObjectNode expanded = objectMapper.createObjectNode();
            expanded.put("type", type.name());
            expanded.putNull("service");
            expanded.put("reason", "Additional " + type.name().toLowerCase(Locale.ROOT)
                    .replace('_', ' ') + " evidence was requested");
            normalized.add(expanded);
            changed = true;
        }
        if (!changed) return decisionNode;
        ObjectNode copy = object.deepCopy();
        copy.set("nextEvidenceRequests", normalized);
        return copy;
    }

    private static String stripBom(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }
}
