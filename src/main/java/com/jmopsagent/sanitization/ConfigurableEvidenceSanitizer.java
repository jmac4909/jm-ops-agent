package com.jmopsagent.sanitization;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class ConfigurableEvidenceSanitizer implements EvidenceSanitizer {

    private static final String TRUNCATION_NOTICE = "\n[CONTENT TRUNCATED BY JM OPS AGENT]";
    private static final String BODY_MARKER = "[REDACTED:BODY]";
    private static final JsonMapper LOG_JSON = JsonMapper.builder().build();
    private static final List<String> SAFE_LOG_FIELDS = List.of(
            "timestamp", "_time", "level", "severity", "logger", "service", "service_name", "app",
            "trackingId", "tracking_id", "operation", "outcome", "status", "http_status", "message",
            "error", "exception", "stack_trace");

    private final SanitizationProperties properties;
    private final List<RedactionRule> rules;

    public ConfigurableEvidenceSanitizer(SanitizationProperties properties) {
        this.properties = properties;
        this.rules = createRules(properties);
    }

    @Override
    public SanitizationResult sanitize(String untrustedContent) {
        return sanitizeInternal(untrustedContent, 0, Set.of());
    }

    @Override
    public SanitizationResult sanitizeLogContent(String untrustedContent) {
        if (properties.isIncludeBodies()) return sanitize(untrustedContent);
        StructuredReduction reduction = reduceStructuredLogContent(untrustedContent);
        return sanitizeInternal(reduction.content(), reduction.omittedBodies(),
                reduction.omittedBodies() == 0 ? Set.of() : Set.of("BODY"));
    }

    private SanitizationResult sanitizeInternal(String untrustedContent, int initialCount,
                                                 Set<String> initialCategories) {
        String sanitized = untrustedContent == null ? "" : untrustedContent;
        int count = initialCount;
        Set<String> categories = new LinkedHashSet<>(initialCategories);

        for (RedactionRule rule : rules) {
            Matcher matcher = rule.pattern().matcher(sanitized);
            StringBuffer output = new StringBuffer(sanitized.length());
            int matchesForRule = 0;
            while (matcher.find()) {
                matchesForRule++;
                String marker = "[REDACTED:" + rule.category() + "]";
                String replacement = rule.preserveFirstGroup() && matcher.groupCount() >= 1
                        ? matcher.group(1) + marker : marker;
                matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
            }
            if (matchesForRule > 0) {
                matcher.appendTail(output);
                sanitized = output.toString();
                count += matchesForRule;
                categories.add(rule.category());
            }
        }

        boolean truncated = sanitized.length() > properties.getMaxContentCharacters();
        if (truncated) {
            int retainedCharacters = Math.max(0, properties.getMaxContentCharacters() - TRUNCATION_NOTICE.length());
            sanitized = sanitized.substring(0, retainedCharacters) + TRUNCATION_NOTICE;
        }

        return new SanitizationResult(sanitized, count > 0, count, new ArrayList<>(categories), truncated);
    }

    private StructuredReduction reduceStructuredLogContent(String content) {
        if (content == null || content.isBlank()) return new StructuredReduction(content, 0);
        StructuredReduction whole = reduceJson(content.trim());
        if (whole != null) return whole;

        StringBuilder reduced = new StringBuilder(content.length());
        int omitted = 0;
        for (String line : content.split("\\R", -1)) {
            StructuredReduction item = reduceJson(line.trim());
            if (item == null) reduced.append(line);
            else {
                reduced.append(item.content());
                omitted += item.omittedBodies();
            }
            reduced.append('\n');
        }
        if (!content.endsWith("\n") && !content.endsWith("\r") && !reduced.isEmpty()) {
            reduced.setLength(reduced.length() - 1);
        }
        return new StructuredReduction(reduced.toString(), omitted);
    }

    /** Returns null when the value is not a standalone JSON object/array. */
    private StructuredReduction reduceJson(String candidate) {
        if (candidate == null || candidate.length() < 2 || !looksLikeStructuredJson(candidate)) return null;
        try {
            JsonNode root = LOG_JSON.readTree(candidate);
            if (!root.isObject()) return new StructuredReduction(BODY_MARKER, 1);
            StringBuilder safe = new StringBuilder();
            int retained = 0;
            int nestedBodies = 0;
            for (String field : SAFE_LOG_FIELDS) {
                JsonNode value = root.get(field);
                if (value == null || value.isNull() || value.isObject() || value.isArray()) continue;
                String text = value.asText();
                if (text == null || text.isBlank()) continue;
                if (!safe.isEmpty()) safe.append(' ');
                safe.append(field).append('=');
                if (looksLikeStructuredJson(text.trim())) {
                    safe.append(BODY_MARKER);
                    nestedBodies++;
                } else {
                    safe.append(text, 0, Math.min(text.length(), 4_000));
                }
                retained++;
            }
            if (retained == 0) return new StructuredReduction(BODY_MARKER, 1);
            boolean omittedFields = root.size() > retained;
            int omitted = nestedBodies + (omittedFields ? 1 : 0);
            if (omittedFields) safe.append(" omittedFields=").append(BODY_MARKER);
            return new StructuredReduction(safe.toString(), omitted);
        } catch (RuntimeException ignored) {
            // Log connectors bound samples before this layer, so a JSON body may be missing its closing delimiter.
            // Fail closed for JSON-looking data instead of persisting the truncated body as ordinary text.
            return new StructuredReduction(BODY_MARKER, 1);
        }
    }

    private static boolean looksLikeStructuredJson(String candidate) {
        if (candidate.startsWith("{")) return true;
        if (!candidate.startsWith("[")) return false;
        int index = 1;
        while (index < candidate.length() && Character.isWhitespace(candidate.charAt(index))) index++;
        if (index >= candidate.length()) return true;
        char next = candidate.charAt(index);
        return next == '{' || next == '[' || next == '"' || next == ']' || next == '-'
                || Character.isDigit(next) || next == 't' || next == 'f' || next == 'n';
    }

    private static List<RedactionRule> createRules(SanitizationProperties properties) {
        List<RedactionRule> configured = new ArrayList<>();
        if (!properties.isIncludeBodies()) {
            // Body collection is opt-in. These conservative patterns remove common single-line structured and log forms.
            configured.add(rule("BODY",
                    "(?im)(\\b(?:request|response)[ _-]?body\\s*[:=]\\s*).+$", true));
            configured.add(rule("BODY",
                    "(?i)([\\\"'](?:requestBody|responseBody|payload|body)[\\\"']\\s*:\\s*)(?:\\\"(?:\\\\.|[^\\\"])*\\\"|\\{[^\\r\\n]*}|\\[[^\\r\\n]*])", true));
            configured.add(rule("BODY",
                    "(?im)(\\bpayload\\s*[:=]\\s*).+$", true));
        }
        configured.add(rule("PRIVATE_KEY",
                "(?is)-----BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-----.*?-----END(?: [A-Z0-9]+)? PRIVATE KEY-----", false));
        configured.add(rule("AUTHORIZATION",
                "(?i)([\\\"']authorization[\\\"']\\s*:\\s*[\\\"'])(?:bearer|basic)?\\s*[^\\\"'\\r\\n]+(?=[\\\"'])", true));
        configured.add(rule("AUTHORIZATION",
                "(?im)(\\b(?:proxy-)?authorization\\s*[:=]\\s*)[^\\r\\n]+", true));
        configured.add(rule("COOKIE",
                "(?i)([\\\"'](?:set-cookie|cookie)[\\\"']\\s*:\\s*[\\\"'])[^\\\"'\\r\\n]+(?=[\\\"'])", true));
        configured.add(rule("COOKIE",
                "(?im)(\\b(?:set-cookie|cookie)\\s*:\\s*)[^\\r\\n]+", true));
        configured.add(rule("CSRF",
                "(?i)([\\\"']?(?:x[-_]splunk[-_]form[-_]key|x[-_]csrf[-_]token|x[-_]xsrf[-_]token|splunkweb[-_]csrf[-_]token(?:_\\d+)?|csrf[-_]?token|xsrf[-_]?token)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,\\\"';&}\\]]+", true));
        configured.add(rule("SENSITIVE_BODY_FIELD",
                "(?i)([\\\"'](?:patient(?:id)?|memberid|subscriberid|customerid|claimid|medicalrecord(?:number)?|mrn|dateofbirth|dob|ssn|first[-_]?name|last[-_]?name|full[-_]?name|email|phone|address)[\\\"']\\s*:\\s*)(?:\\\"(?:\\\\.|[^\\\"])*\\\"|[^,}\\r\\n]+)", true));
        configured.add(rule("CREDENTIAL",
                "(?i)([\\\"'](?:password|passwd|pwd|api[-_]?key|access[-_]?token|refresh[-_]?token|client[-_]?secret|secret(?:[-_]?value)?)[\\\"']\\s*[:=]\\s*[\\\"'])[^\\\"'\\r\\n]*(?=[\\\"'])", true));
        configured.add(rule("CREDENTIAL",
                "(?i)([\\\"']?(?:password|passwd|pwd|api[-_]?key|access[-_]?token|refresh[-_]?token|client[-_]?secret|secret(?:[-_]?value)?)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,\\\"';&}\\]]+", true));
        configured.add(rule("CREDENTIAL",
                "(?i)(--(?:password|api[-_]?key|access[-_]?token|client[-_]?secret|secret)\\s+)[^\\s]+", true));
        configured.add(rule("URI_CREDENTIAL",
                "(?i)(\\b[a-z][a-z0-9+.-]*://[^\\s/@:]+:)[^\\s/@]+(?=@)", true));
        configured.add(rule("BEARER_TOKEN",
                "(?i)\\bbearer\\s+[a-z0-9._~+/=-]{8,}", false));
        configured.add(rule("JWT",
                "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b", false));
        configured.add(rule("AWS_ACCESS_KEY",
                "\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b", false));

        properties.getAdditionalPatterns().forEach((name, expression) -> {
            if (expression == null || expression.isBlank()) {
                return;
            }
            String category = "CUSTOM_" + name.replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(Locale.ROOT);
            try {
                configured.add(new RedactionRule(category, Pattern.compile(expression), false));
            } catch (PatternSyntaxException exception) {
                throw new IllegalArgumentException("Invalid sanitization pattern '" + name + "'", exception);
            }
        });
        return List.copyOf(configured);
    }

    private static RedactionRule rule(String category, String expression, boolean preserveFirstGroup) {
        return new RedactionRule(category, Pattern.compile(expression), preserveFirstGroup);
    }

    private record RedactionRule(String category, Pattern pattern, boolean preserveFirstGroup) {
    }

    private record StructuredReduction(String content, int omittedBodies) {
    }
}
