package com.jmopsagent.registry;

import com.jmopsagent.domain.DeploymentEnvironment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class ServiceDefinition {

    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z0-9](?:[-a-z0-9.]{0,126}[a-z0-9])?");

    private final String service;
    private final Map<String, RegistryValue> aliases;
    private final Map<String, RegistryValue> attributes;

    ServiceDefinition(String service) {
        this(service, new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private ServiceDefinition(String service, Map<String, RegistryValue> aliases,
            Map<String, RegistryValue> attributes) {
        this.service = normalize(service);
        this.aliases = aliases;
        this.attributes = attributes;
    }

    public String service() {
        return service;
    }

    public Set<String> aliases() {
        return Collections.unmodifiableSet(aliases.keySet());
    }

    /** Metadata for aliases, including whether each value was manually configured or discovered. */
    public Map<String, RegistryValue> aliasesWithProvenance() {
        return Collections.unmodifiableMap(aliases);
    }

    public Optional<RegistryValue> alias(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(aliases.get(normalize(value)));
    }

    public Map<String, RegistryValue> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public Optional<RegistryValue> attribute(String path) {
        if (path == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(attributes.get(path.trim()));
    }

    public Optional<String> attributeValue(String path) {
        return attribute(path).flatMap(RegistryValue::value);
    }

    public List<String> attributeValues(String path) {
        return attribute(path).map(RegistryValue::values).orElseGet(List::of);
    }

    /** Checks an environment-specific path first, then the generic value, expanding {environment}. */
    public Optional<String> attributeForEnvironment(String path, DeploymentEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        Optional<String> environmentValue = attributeValue(path + "." + environment.name());
        return environmentValue.or(() -> attributeValue(path))
                .map(value -> value.replace("{environment}", environment.name().toLowerCase(Locale.ROOT)));
    }

    void addAliases(Iterable<String> additions) {
        if (additions == null) {
            return;
        }
        additions.forEach(alias -> {
            if (alias != null && !alias.isBlank()) {
                String normalized = normalize(alias);
                aliases.putIfAbsent(normalized, RegistryValue.manual(List.of(normalized)));
            }
        });
    }

    void applyDiscoveredAliases(Iterable<String> additions, RegistryProvenance provenance) {
        if (additions == null) {
            return;
        }
        additions.forEach(alias -> {
            if (alias == null || alias.isBlank()) {
                return;
            }
            String normalized = normalize(alias);
            RegistryValue existing = aliases.get(normalized);
            if (existing != null
                    && (existing.provenance() == RegistryProvenance.MANUAL || existing.confirmed())) {
                return;
            }
            aliases.put(normalized, RegistryValue.discovered(List.of(normalized), provenance));
        });
    }

    void putManual(String path, List<String> values) {
        if (path != null && !path.isBlank() && values != null && !values.isEmpty()) {
            attributes.put(path.trim(), RegistryValue.manual(values));
        }
    }

    void applyDiscovered(String path, List<String> values, RegistryProvenance provenance) {
        if (path == null || path.isBlank() || values == null || values.isEmpty()) {
            return;
        }
        RegistryValue existing = attributes.get(path.trim());
        if (existing != null && (existing.provenance() == RegistryProvenance.MANUAL || existing.confirmed())) {
            return;
        }
        attributes.put(path.trim(), RegistryValue.discovered(values, provenance));
    }

    ServiceDefinition snapshot() {
        return new ServiceDefinition(service, new LinkedHashMap<>(aliases), new LinkedHashMap<>(attributes));
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("service name is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!SERVICE_NAME.matcher(normalized).matches() || normalized.contains("..")) {
            throw new IllegalArgumentException("service name contains unsupported characters");
        }
        return normalized;
    }
}
