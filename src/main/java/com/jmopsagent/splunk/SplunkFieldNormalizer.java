package com.jmopsagent.splunk;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Builds a fixed canonicalization pipeline and resolves the same mappings in returned JSON. */
final class SplunkFieldNormalizer {
    private final List<SplunkFieldProfile.Validated> profiles;

    SplunkFieldNormalizer(List<SplunkFieldProfile.Validated> profiles) {
        this.profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    String pipeline() {
        return pipeline(List.of());
    }

    String pipeline(List<String> selectedProfileNames) {
        StringBuilder spl = new StringBuilder();
        Map<SplunkCanonicalField, List<String>> profileOutputs = new EnumMap<>(SplunkCanonicalField.class);
        List<String> selected = selectedProfileNames == null ? List.of() : selectedProfileNames;
        if (selected.stream().distinct().count() != selected.size()
                || !profiles.stream().map(SplunkFieldProfile.Validated::name).toList().containsAll(selected)) {
            throw new IllegalArgumentException("Unknown or duplicate Splunk field profile selection");
        }
        for (SplunkFieldProfile.Validated profile : profiles) {
            if (!selected.contains(profile.name())) continue;
            String textTrackingId = "jmops_rex_" + profile.index() + "_tracking_id";
            if (profile.trackingIdExtraction() == SplunkTrackingIdExtraction.PREFIXED_TEXT) {
                spl.append(" | rex field=_raw \"(?i)X-TrackingId[_=](?<")
                        .append(textTrackingId).append(">[^\\s,\\\"]+)\"");
            }
            EnumSet<SplunkCanonicalField> outputs = profile.fields().isEmpty()
                    ? EnumSet.noneOf(SplunkCanonicalField.class)
                    : EnumSet.copyOf(profile.fields().keySet());
            if (profile.trackingIdExtraction() == SplunkTrackingIdExtraction.PREFIXED_TEXT) {
                outputs.add(SplunkCanonicalField.TRACKING_ID);
            }
            outputs.forEach(canonical -> {
                List<String> paths = profile.fields().getOrDefault(canonical, List.of());
                List<String> values = new ArrayList<>();
                for (int pathIndex = 0; pathIndex < paths.size(); pathIndex++) {
                    String path = paths.get(pathIndex);
                    if (path.contains(".")) {
                        String extracted = "jmops_extract_" + profile.index() + "_"
                                + canonical.name().toLowerCase(java.util.Locale.ROOT) + "_" + pathIndex;
                        spl.append(" | spath path=\"").append(path).append("\" output=").append(extracted);
                        values.add(quotedField(extracted));
                    } else {
                        values.add(quotedField(path));
                    }
                }
                if (canonical == SplunkCanonicalField.TRACKING_ID
                        && profile.trackingIdExtraction() == SplunkTrackingIdExtraction.PREFIXED_TEXT) {
                    values.add(quotedField(textTrackingId));
                }
                String profileOutput = "jmops_profile_" + profile.index() + "_"
                        + canonical.name().toLowerCase(java.util.Locale.ROOT);
                spl.append(" | eval ").append(profileOutput).append('=');
                String coalesced = coalesce(values);
                if (profile.sourcetype().isBlank()) {
                    spl.append(coalesced);
                } else {
                    spl.append("if(sourcetype=\"").append(profile.sourcetype()).append("\",")
                            .append(coalesced).append(",null())");
                }
                profileOutputs.computeIfAbsent(canonical, ignored -> new ArrayList<>()).add(quotedField(profileOutput));
            });
        }

        for (SplunkCanonicalField canonical : SplunkCanonicalField.values()) {
            List<String> sources = new ArrayList<>();
            canonical.defaultPaths().forEach(path -> sources.add(quotedField(path)));
            sources.addAll(profileOutputs.getOrDefault(canonical, List.of()));
            spl.append(" | eval ").append(canonical.outputName()).append('=').append(coalesce(sources));
        }
        return spl.toString();
    }

    String value(JsonNode result, SplunkCanonicalField canonical) {
        for (String path : canonical.defaultPaths()) {
            String value = valueAt(result, path);
            if (!value.isBlank()) return value;
        }
        String sourcetype = valueAt(result, "sourcetype");
        for (SplunkFieldProfile.Validated profile : profiles) {
            if (!profile.sourcetype().isBlank() && !profile.sourcetype().equalsIgnoreCase(sourcetype)) continue;
            for (String path : profile.fields().getOrDefault(canonical, List.of())) {
                String value = valueAt(result, path);
                if (!value.isBlank()) return value;
            }
        }
        return "";
    }

    private static String valueAt(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) return "";
        JsonNode direct = root.get(path);
        if (isValue(direct)) return direct.asText();
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            if (current == null || !current.isObject()) return "";
            current = current.get(part);
        }
        return isValue(current) ? current.asText() : "";
    }

    private static boolean isValue(JsonNode node) {
        return node != null && !node.isNull() && !node.isObject() && !node.isArray() && !node.asText().isBlank();
    }

    private static String coalesce(List<String> values) {
        List<String> safe = values == null ? List.of() : values.stream().distinct().toList();
        if (safe.isEmpty()) return "null()";
        if (safe.size() == 1) return "coalesce(" + safe.getFirst() + ",null())";
        return "coalesce(" + String.join(",", safe) + ")";
    }

    private static String quotedField(String field) {
        return "'" + field + "'";
    }

    String businessCallRawPredicate(List<String> selectedProfileNames) {
        List<String> selected = selectedProfileNames == null ? List.of() : selectedProfileNames;
        List<SplunkFieldProfile.Validated> selectedProfiles = profiles.stream()
                .filter(profile -> selected.contains(profile.name())).toList();
        if (selectedProfiles.size() != selected.stream().distinct().count()) {
            throw new IllegalArgumentException("Unknown or duplicate Splunk field profile selection");
        }
        List<String> trackingMarkers = new ArrayList<>();
        List<String> statusMarkers = new ArrayList<>();
        for (SplunkFieldProfile.Validated profile : selectedProfiles) {
            if (profile.trackingIdExtraction() == SplunkTrackingIdExtraction.PREFIXED_TEXT) {
                trackingMarkers.add("X-TrackingId");
            }
            profile.fields().getOrDefault(SplunkCanonicalField.TRACKING_ID, List.of()).stream()
                    .map(SplunkFieldNormalizer::lastPathSegment).forEach(trackingMarkers::add);
            profile.fields().getOrDefault(SplunkCanonicalField.HTTP_STATUS, List.of()).stream()
                    .map(SplunkFieldNormalizer::lastPathSegment).forEach(statusMarkers::add);
        }
        if (trackingMarkers.isEmpty()) trackingMarkers.add("X-TrackingId");
        if (selectedProfiles.isEmpty() && statusMarkers.isEmpty()) statusMarkers.add("statusCode");
        String tracking = rawTerms(trackingMarkers);
        return statusMarkers.isEmpty() ? tracking : tracking + " " + rawTerms(statusMarkers);
    }

    private static String rawTerms(List<String> terms) {
        return terms.stream().distinct().map(term -> "\"" + term + "\"")
                .collect(java.util.stream.Collectors.joining(" OR ", "(", ")"));
    }

    private static String lastPathSegment(String path) {
        int separator = path.lastIndexOf('.');
        return separator < 0 ? path : path.substring(separator + 1);
    }
}
