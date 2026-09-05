package com.jmopsagent.registry;

import com.jmopsagent.domain.DeploymentEnvironment;

import java.util.Optional;

/** Read-only enrichment hook implemented by connector adapters as they become available. */
public interface ServiceRegistryDiscoveryHook {
    Optional<RegistryDiscoveryUpdate> discover(ServiceDefinition service, DeploymentEnvironment environment);
}
