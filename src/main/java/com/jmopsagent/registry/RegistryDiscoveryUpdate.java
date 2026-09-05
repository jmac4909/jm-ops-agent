package com.jmopsagent.registry;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record RegistryDiscoveryUpdate(
        String service,
        RegistryProvenance provenance,
        Map<String, List<String>> attributes,
        Set<String> aliases
) {
    private static final int MAX_ATTRIBUTES = 32;
    private static final int MAX_VALUES_PER_ATTRIBUTE = 16;
    private static final int MAX_VALUE_CHARACTERS = 2_048;
    private static final int MAX_ALIASES = 32;
    private static final Pattern ATTRIBUTE_PATH = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,126}[A-Za-z0-9])?");

    public RegistryDiscoveryUpdate {
        service = ServiceDefinition.normalize(service);
        Objects.requireNonNull(provenance, "provenance");
        if (provenance == RegistryProvenance.MANUAL) {
            throw new IllegalArgumentException("Discovery hooks cannot publish MANUAL values");
        }
        if (attributes != null && attributes.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("A discovery update contains too many attributes");
        }
        Map<String, List<String>> copiedAttributes = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach((key, values) -> {
                String safeKey = safeAttributePath(key);
                List<String> safeValues = safeValues(values);
                if (!safeValues.isEmpty()) {
                    copiedAttributes.put(safeKey, safeValues);
                }
            });
        }
        attributes = Map.copyOf(copiedAttributes);
        if (aliases != null && aliases.size() > MAX_ALIASES) {
            throw new IllegalArgumentException("A discovery update contains too many aliases");
        }
        Set<String> copiedAliases = new LinkedHashSet<>();
        if (aliases != null) {
            aliases.forEach(alias -> copiedAliases.add(ServiceDefinition.normalize(alias)));
        }
        aliases = Set.copyOf(copiedAliases);
        if (attributes.isEmpty() && aliases.isEmpty()) {
            throw new IllegalArgumentException("A discovery update must contain a verified value");
        }
    }

    public static RegistryDiscoveryUpdate of(String service, RegistryProvenance provenance,
                                             Map<String, String> scalarAttributes) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        if (scalarAttributes != null) {
            scalarAttributes.forEach((key, value) -> values.put(key, value == null ? List.of() : List.of(value)));
        }
        return new RegistryDiscoveryUpdate(service, provenance, values, Set.of());
    }

    private static String safeAttributePath(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Discovery attribute path is required");
        }
        String normalized = value.trim();
        if (!ATTRIBUTE_PATH.matcher(normalized).matches() || normalized.contains("..")) {
            throw new IllegalArgumentException("Discovery attribute path contains unsupported characters");
        }
        return normalized;
    }

    private static List<String> safeValues(List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_VALUES_PER_ATTRIBUTE) {
            throw new IllegalArgumentException("A discovery attribute contains too many values");
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .peek(value -> {
                    if (value.length() > MAX_VALUE_CHARACTERS || value.indexOf('\0') >= 0
                            || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                        throw new IllegalArgumentException("A discovery attribute value is invalid");
                    }
                })
                .distinct()
                .toList();
    }
}
