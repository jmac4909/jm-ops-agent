package com.jmopsagent.registry;

import com.jmopsagent.domain.DeploymentEnvironment;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Resolves log hints against registered identities; it never probes a log-supplied URL. */
public final class ServiceDependencyResolver {
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:GET|HEAD|POST|PUT|PATCH|DELETE)\\s+(https?://[^\\s<>\\\"]+)");
    private static final Pattern FEIGN = Pattern.compile("\\b([A-Za-z][A-Za-z0-9]*Client)#[A-Za-z][A-Za-z0-9]*");
    private final ServiceRegistry registry;

    public ServiceDependencyResolver(ServiceRegistry registry) { this.registry = registry; }

    public Map<String, String> downstream(String service, DeploymentEnvironment environment, List<String> logs) {
        Map<String, String> result = new LinkedHashMap<>();
        registry.resolve(service).ifPresent(definition -> definition.dependencies().forEach(name ->
                registry.resolve(name).ifPresent(target -> result.putIfAbsent(target.service(), "registry"))));
        for (String log : logs) {
            if (log == null) continue;
            var urls = URL.matcher(log);
            while (urls.find()) {
                try {
                    String host = URI.create(urls.group(1)).getHost();
                    if (host != null) resolveHost(host, environment).forEach(target ->
                            result.putIfAbsent(target.service(), "observed HTTP destination"));
                } catch (IllegalArgumentException ignored) { }
            }
            var clients = FEIGN.matcher(log);
            while (clients.find()) {
                String name = clients.group(1);
                List<ServiceDefinition> matches = registry.all().stream().filter(definition -> definition.attributeValues("feignClients").contains(name)
                                || definition.service().equalsIgnoreCase(name.substring(0, name.length() - 6)))
                        .toList();
                if (matches.size() == 1) result.putIfAbsent(matches.getFirst().service(), "observed Feign client");
            }
        }
        result.remove(service);
        return java.util.Collections.unmodifiableMap(result);
    }

    private List<ServiceDefinition> resolveHost(String host, DeploymentEnvironment environment) {
        var exact = registry.resolve(host);
        if (exact.isPresent()) return List.of(exact.get());
        List<ServiceDefinition> matches = registry.all().stream().filter(definition ->
                definition.attributeValues("dependencyHosts").stream().anyMatch(host::equalsIgnoreCase)
                        || definition.attributeForEnvironment("tas.appPattern", environment)
                        .map(pattern -> pattern.replace("{service}", definition.service()))
                        .filter(host::equalsIgnoreCase).isPresent()).toList();
        return matches.size() == 1 ? matches : List.of();
    }
}
