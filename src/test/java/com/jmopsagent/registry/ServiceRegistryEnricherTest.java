package com.jmopsagent.registry;

import com.jmopsagent.domain.DeploymentEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceRegistryEnricherTest {

    @Test
    void presentsAnUnknownCandidateToHooksWithoutPersistingAnUnverifiedSeed() {
        YamlServiceRegistry registry = emptyRegistry();
        AtomicReference<ServiceDefinition> attempted = new AtomicReference<>();
        ServiceRegistryDiscoveryHook noMatch = (candidate, environment) -> {
            attempted.set(candidate);
            return Optional.empty();
        };
        ServiceRegistryEnricher enricher = new ServiceRegistryEnricher(registry, List.of(noMatch));

        assertThatThrownBy(() -> enricher.enrich("Sample-Service", DeploymentEnvironment.TEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown service");

        assertThat(attempted.get().service()).isEqualTo("sample-service");
        assertThat(registry.resolve("sample-service")).isEmpty();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void persistsOnlyAnExactHookConfirmedUpdate() {
        YamlServiceRegistry registry = emptyRegistry();
        ServiceRegistryDiscoveryHook confirmed = (candidate, environment) -> Optional.of(
                new RegistryDiscoveryUpdate(candidate.service(), RegistryProvenance.DISCOVERED_GITLAB,
                        Map.of("gitlab.repository", List.of(
                                "https://gitlab.example.invalid/group/sample-service")),
                        Set.of("sample-api")));
        ServiceRegistryEnricher enricher = new ServiceRegistryEnricher(registry, List.of(confirmed));

        ServiceDefinition discovered = enricher.enrich("sample-service", DeploymentEnvironment.DEV);

        assertThat(discovered.service()).isEqualTo("sample-service");
        assertThat(discovered.attribute("gitlab.repository")).get()
                .satisfies(value -> {
                    assertThat(value.provenance()).isEqualTo(RegistryProvenance.DISCOVERED_GITLAB);
                    assertThat(value.confirmed()).isFalse();
                });
        assertThat(discovered.alias("sample-api")).get()
                .satisfies(value -> assertThat(value.provenance())
                        .isEqualTo(RegistryProvenance.DISCOVERED_GITLAB));
        assertThat(registry.resolve("sample-api")).isPresent();
    }

    @Test
    void ignoresAnUpdateForAServiceOtherThanTheCandidate() {
        YamlServiceRegistry registry = emptyRegistry();
        ServiceRegistryDiscoveryHook mismatched = (candidate, environment) -> Optional.of(
                RegistryDiscoveryUpdate.of("different-service", RegistryProvenance.DISCOVERED_GITLAB,
                        Map.of("gitlab.repository",
                                "https://gitlab.example.invalid/group/different-service")));
        ServiceRegistryEnricher enricher = new ServiceRegistryEnricher(registry, List.of(mismatched));

        assertThatThrownBy(() -> enricher.enrich("sample-service", DeploymentEnvironment.TEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown service");

        assertThat(registry.resolve("sample-service")).isEmpty();
        assertThat(registry.resolve("different-service")).isEmpty();
    }

    private static YamlServiceRegistry emptyRegistry() {
        YamlServiceRegistry registry = new YamlServiceRegistry(new ByteArrayResource(
                "services: []\n".getBytes(StandardCharsets.UTF_8)));
        registry.load();
        return registry;
    }
}
