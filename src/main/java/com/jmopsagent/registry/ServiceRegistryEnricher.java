package com.jmopsagent.registry;

import com.jmopsagent.domain.DeploymentEnvironment;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class ServiceRegistryEnricher {

    private final ServiceRegistry registry;
    private final List<ServiceRegistryDiscoveryHook> discoveryHooks;

    public ServiceRegistryEnricher(ServiceRegistry registry, List<ServiceRegistryDiscoveryHook> discoveryHooks) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.discoveryHooks = List.copyOf(discoveryHooks);
    }

    public ServiceDefinition enrich(String serviceOrAlias, DeploymentEnvironment environment) {
        Objects.requireNonNull(environment, "environment");
        Optional<ServiceDefinition> registered = registry.resolve(serviceOrAlias);
        ServiceDefinition current = registered.orElseGet(() -> new ServiceDefinition(serviceOrAlias));
        boolean verified = registered.isPresent();

        for (ServiceRegistryDiscoveryHook hook : discoveryHooks) {
            Optional<RegistryDiscoveryUpdate> discovered = hook.discover(current, environment);
            if (discovered.isPresent() && discovered.get().service().equals(current.service())) {
                current = registry.applyDiscovery(discovered.get());
                verified = true;
            }
            current = registry.resolve(current.service()).orElse(current);
        }

        if (!verified) {
            throw new IllegalArgumentException("Unknown service: " + serviceOrAlias);
        }
        return current;
    }
}
