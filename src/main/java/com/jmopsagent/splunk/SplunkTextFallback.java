package com.jmopsagent.splunk;

import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed patterns only: configuration and model output never supply executable SPL. */
final class SplunkTextFallback {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final Map<SplunkCanonicalField, String> PATTERNS = Map.of(
            SplunkCanonicalField.TRACKING_ID, "(?i)X-TrackingId[\\\"' ]*[:=_][\\\"' ]*(?<value>[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127})",
            SplunkCanonicalField.HTTP_STATUS, "(?i)(?:statusCode|httpStatus|http_status)[\\\"' ]*[:=][\\\"' ]*(?<value>[1-5][0-9]{2})\\b",
            SplunkCanonicalField.SEVERITY, "(?i)(?:^|[\\s\\[])(?<value>ERROR|WARN|INFO|DEBUG|TRACE|FATAL|SEVERE)(?:[\\s\\]:]|$)",
            SplunkCanonicalField.OPERATION, "(?<value>[A-Za-z][A-Za-z0-9]*Client#[A-Za-z][A-Za-z0-9]*)",
            SplunkCanonicalField.TARGET_URL, "(?i)\\b(?:GET|HEAD|POST|PUT|PATCH|DELETE) +(?<value>https?://[^\\s<>\\\"]+)");

    private SplunkTextFallback() { }

    static String rawMessage(JsonNode result) {
        if (result == null) return "";
        JsonNode msg = result.get("msg");
        if (msg != null && msg.isTextual() && !msg.asText().isBlank()) return msg.asText();
        JsonNode raw = result.get("_raw");
        if (raw == null || !raw.isTextual()) return "";
        String text = raw.asText();
        try {
            JsonNode envelope = JSON.readTree(text);
            if (envelope != null && envelope.isObject()) {
                for (String key : new String[]{"message", "msg"}) {
                    JsonNode value = envelope.get(key);
                    if (value != null && value.isTextual() && !value.asText().isBlank()) return value.asText();
                }
            }
        } catch (RuntimeException ignored) {
            // Plain text is evidence too.
        }
        return text;
    }

    static String value(JsonNode result, SplunkCanonicalField field) {
        if (field == SplunkCanonicalField.MESSAGE) return rawMessage(result);
        String regex = PATTERNS.get(field);
        if (regex == null || result == null) return "";
        JsonNode message = result.get("message");
        String text = message != null && message.isTextual() && !message.asText().isBlank()
                ? message.asText() : rawMessage(result);
        var matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group("value") : "";
    }
}
