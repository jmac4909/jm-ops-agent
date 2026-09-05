package com.jmopsagent.registry;

import java.util.Collection;
import java.util.Optional;

public interface ServiceRegistry {
    Optional<ServiceDefinition> resolve(String serviceOrAlias);

    Collection<ServiceDefinition> all();

    ServiceDefinition applyDiscovery(RegistryDiscoveryUpdate update);
}
