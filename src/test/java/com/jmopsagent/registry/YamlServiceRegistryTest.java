package com.jmopsagent.registry;

import com.jmopsagent.domain.DeploymentEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlServiceRegistryTest {

    @Test
    void resolvesFixturesByAliasAndEnvironment() {
        YamlServiceRegistry registry = new YamlServiceRegistry();
        registry.load();

        ServiceDefinition service = registry.resolve("demo-catalog-service-test").orElseThrow();

        assertThat(service.service()).isEqualTo("catalog-service");
        assertThat(service.attributeForEnvironment("eks.namespace", DeploymentEnvironment.TEST))
                .contains("demo-catalog-service-test");
        assertThat(service.attributeValue("jenkins.job"))
                .contains("demo/catalog-service/deploy");
        assertThat(registry.resolve("EDGE-GATEWAY")).isPresent();
        assertThat(registry.resolve("demo-identity-api")).isPresent();
    }

    @Test
    void discoveryEnrichesMissingFieldsButCannotReplaceManualValues() {
        YamlServiceRegistry registry = new YamlServiceRegistry();
        registry.load();
        String originalJob = registry.resolve("catalog-service").orElseThrow()
                .attributeValue("jenkins.job").orElseThrow();

        ServiceDefinition updated = registry.applyDiscovery(new RegistryDiscoveryUpdate(
                "catalog-service",
                RegistryProvenance.DISCOVERED_JENKINS,
                Map.of(
                        "jenkins.job", List.of("untrusted-discovered-job"),
                        "jenkins.lastSuccessfulJob", List.of("demo/catalog-service/verify")),
                Set.of("catalog-discovered")));

        assertThat(updated.attributeValue("jenkins.job")).contains(originalJob);
        assertThat(updated.attribute("jenkins.job").orElseThrow().provenance())
                .isEqualTo(RegistryProvenance.MANUAL);
        assertThat(updated.attributeValue("jenkins.lastSuccessfulJob"))
                .contains("demo/catalog-service/verify");
        assertThat(registry.resolve("catalog-discovered")).isPresent();
    }

    @Test
    void rejectsAnAliasAssignedToMultipleServices() {
        String yaml = """
                services:
                  - service: sample-one
                    aliases: [shared-alias]
                  - service: sample-two
                    aliases: [shared-alias]
                """;
        YamlServiceRegistry registry = new YamlServiceRegistry(
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(registry::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alias");
    }

    @Test
    void rejectsAConflictingDiscoveredAlias() {
        YamlServiceRegistry registry = new YamlServiceRegistry();
        registry.load();

        assertThatThrownBy(() -> registry.applyDiscovery(new RegistryDiscoveryUpdate(
                "catalog-service",
                RegistryProvenance.DISCOVERED_JENKINS,
                Map.of(),
                Set.of("demo-identity-api"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alias");
    }
}
