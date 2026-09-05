package com.jmopsagent.splunk;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Declarative field aliases for one log source. Values are field paths, never executable SPL.
 */
public class SplunkFieldProfile {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}");
    private static final Pattern SOURCETYPE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,159}");
    private static final Pattern FIELD_PATH = Pattern.compile("_?[A-Za-z][A-Za-z0-9_.:-]{0,127}");

    private String name = "default";
    private String sourcetype = "";
    private SplunkTrackingIdExtraction trackingIdExtraction = SplunkTrackingIdExtraction.FIELD_ALIASES;
    private Map<String, List<String>> fields = new LinkedHashMap<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourcetype() {
        return sourcetype;
    }

    public void setSourcetype(String sourcetype) {
        this.sourcetype = sourcetype;
    }

    public SplunkTrackingIdExtraction getTrackingIdExtraction() {
        return trackingIdExtraction;
    }

    public void setTrackingIdExtraction(SplunkTrackingIdExtraction trackingIdExtraction) {
        this.trackingIdExtraction = trackingIdExtraction;
    }

    public Map<String, List<String>> getFields() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        fields.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return copy;
    }

    public void setFields(Map<String, List<String>> fields) {
        this.fields = new LinkedHashMap<>();
        if (fields != null) fields.forEach((key, value) ->
                this.fields.put(key, value == null ? List.of() : new ArrayList<>(value)));
    }

    Validated validate(int index) {
        String safeName = requirePattern(name, NAME, "field profile name");
        String safeSourcetype = sourcetype == null || sourcetype.isBlank()
                ? "" : requirePattern(sourcetype, SOURCETYPE, "field profile sourcetype");
        SplunkTrackingIdExtraction safeExtraction = trackingIdExtraction == null
                ? SplunkTrackingIdExtraction.FIELD_ALIASES : trackingIdExtraction;
        Map<SplunkCanonicalField, List<String>> validatedFields = new EnumMap<>(SplunkCanonicalField.class);
        fields.forEach((canonicalName, paths) -> {
            SplunkCanonicalField canonical = SplunkCanonicalField.parse(canonicalName);
            if (paths == null || paths.isEmpty()) {
                throw new IllegalArgumentException("Splunk field profile mappings cannot be empty");
            }
            List<String> safePaths = paths.stream()
                    .map(path -> requirePattern(path, FIELD_PATH, "Splunk field path"))
                    .distinct()
                    .toList();
            if (safePaths.size() > 12) {
                throw new IllegalArgumentException("A Splunk canonical field accepts at most 12 aliases");
            }
            validatedFields.put(canonical, safePaths);
        });
        if (validatedFields.isEmpty() && safeExtraction == SplunkTrackingIdExtraction.FIELD_ALIASES) {
            throw new IllegalArgumentException("Splunk field profile must define at least one field mapping");
        }
        return new Validated(index, safeName, safeSourcetype, safeExtraction, Map.copyOf(validatedFields));
    }

    private static String requirePattern(String value, Pattern pattern, String label) {
        if (value == null || !pattern.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException(label + " contains unsupported characters");
        }
        return value.trim();
    }

    record Validated(int index, String name, String sourcetype, SplunkTrackingIdExtraction trackingIdExtraction,
                     Map<SplunkCanonicalField, List<String>> fields) {
    }
}
