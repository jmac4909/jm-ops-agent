package com.jmopsagent.registry;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class YamlServiceRegistry implements ServiceRegistry {

    private final Resource registryResource;
    private final Map<String, ServiceDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, String> aliases = new ConcurrentHashMap<>();

    public YamlServiceRegistry() {
        this(new ClassPathResource("service-registry.yml"));
    }

    @Autowired
    public YamlServiceRegistry(
            @Value("${jmops.registry.location:classpath:service-registry.yml}") String registryLocation) {
        this(new DefaultResourceLoader().getResource(validateLocation(registryLocation)));
    }

    public YamlServiceRegistry(Resource registryResource) {
        this.registryResource = registryResource;
    }

    @PostConstruct
    public synchronized void load() {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(registryResource);
        Map<String, Object> root = yaml.getObject();
        if (root == null || !(root.get("services") instanceof Collection<?> services)) {
            throw new IllegalStateException("service-registry.yml must contain a services list");
        }

        Map<String, ServiceDefinition> loaded = new LinkedHashMap<>();
        for (Object candidate : services) {
            if (!(candidate instanceof Map<?, ?> rawEntry)) {
                throw new IllegalStateException("Each service registry entry must be a map");
            }
            Map<String, Object> entry = stringKeyMap(rawEntry);
            ServiceDefinition definition = new ServiceDefinition(requiredString(entry, "service"));
            definition.addAliases(asStrings(entry.get("aliases")));
            entry.forEach((key, value) -> {
                if (!key.equals("service") && !key.equals("aliases")) {
                    flattenManual(definition, key, value);
                }
            });
            ServiceDefinition previous = loaded.putIfAbsent(definition.service(), definition);
            if (previous != null) {
                throw new IllegalStateException("Duplicate registry service: " + definition.service());
            }
        }

        Map<String, String> loadedAliases = new LinkedHashMap<>();
        loaded.values().forEach(definition -> indexAliases(definition, loadedAliases));
        definitions.clear();
        aliases.clear();
        definitions.putAll(loaded);
        aliases.putAll(loadedAliases);
    }

    @Override
    public Optional<ServiceDefinition> resolve(String serviceOrAlias) {
        if (serviceOrAlias == null || serviceOrAlias.isBlank()) {
            return Optional.empty();
        }
        String normalized = serviceOrAlias.trim().toLowerCase(Locale.ROOT);
        String canonical = definitions.containsKey(normalized) ? normalized : aliases.get(normalized);
        ServiceDefinition definition = canonical == null ? null : definitions.get(canonical);
        return Optional.ofNullable(definition).map(ServiceDefinition::snapshot);
    }

    @Override
    public Collection<ServiceDefinition> all() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(ServiceDefinition::service))
                .map(ServiceDefinition::snapshot)
                .toList();
    }

    @Override
    public synchronized ServiceDefinition applyDiscovery(RegistryDiscoveryUpdate update) {
        String requested = update.service();
        String canonical = definitions.containsKey(requested) ? requested : aliases.getOrDefault(requested, requested);
        for (String candidate : update.aliases()) {
            String alias = ServiceDefinition.normalize(candidate);
            String existing = aliases.get(alias);
            if (existing != null && !existing.equals(canonical)) {
                throw new IllegalArgumentException("Registry alias is already assigned to another service");
            }
        }
        ServiceDefinition definition = definitions.computeIfAbsent(canonical, ServiceDefinition::new);
        update.attributes().forEach((path, values) ->
                definition.applyDiscovered(path, values, update.provenance()));
        definition.applyDiscoveredAliases(update.aliases(), update.provenance());
        indexAliases(definition);
        return definition.snapshot();
    }

    private void indexAliases(ServiceDefinition definition) {
        indexAliases(definition, aliases);
    }

    private static void indexAliases(ServiceDefinition definition, Map<String, String> target) {
        indexAlias(target, definition.service(), definition.service());
        definition.aliases().forEach(alias -> indexAlias(target, alias, definition.service()));
    }

    private static void indexAlias(Map<String, String> target, String alias, String canonical) {
        String existing = target.putIfAbsent(alias, canonical);
        if (existing != null && !existing.equals(canonical)) {
            throw new IllegalStateException("Registry alias is assigned to more than one service");
        }
    }

    private static String validateLocation(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.length() > 2_000) {
            throw new IllegalArgumentException("Service registry location is invalid");
        }
        String normalized = value.trim();
        if (!(normalized.startsWith("classpath:") || normalized.startsWith("file:"))) {
            throw new IllegalArgumentException("Service registry location must use classpath: or file:");
        }
        return normalized;
    }

    private static void flattenManual(ServiceDefinition definition, String path, Object value) {
        if (value instanceof Map<?, ?> nested) {
            stringKeyMap(nested).forEach((key, nestedValue) -> flattenManual(definition, path + "." + key, nestedValue));
            return;
        }
        List<String> values = asStrings(value);
        definition.putManual(path, values);
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static List<String> asStrings(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>();
            collection.stream().filter(item -> item != null && !String.valueOf(item).isBlank())
                    .map(String::valueOf).forEach(values::add);
            return List.copyOf(values);
        }
        return List.of(String.valueOf(value));
    }

    private static String requiredString(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("Registry field '" + key + "' is required");
        }
        return String.valueOf(value);
    }
}
