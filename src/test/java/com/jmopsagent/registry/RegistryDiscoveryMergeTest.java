package com.jmopsagent.registry;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryDiscoveryMergeTest {

    @Test
    void preservesManualAttributesAndAliasProvenance() {
        YamlServiceRegistry registry = registry("""
                services:
                  - service: sample-service
                    aliases: [sample-api]
                    jenkins:
                      job:
                        TEST: folder/sample-service/deploy
                """);

        ServiceDefinition updated = registry.applyDiscovery(new RegistryDiscoveryUpdate(
                "sample-service", RegistryProvenance.DISCOVERED_JENKINS,
                Map.of(
                        "jenkins.job.TEST", List.of("sample-service-test-deploy"),
                        "jenkins.controller.TEST", List.of("reader")),
                Set.of("sample-api", "sample-workload")));

        assertThat(updated.attributeValue("jenkins.job.TEST"))
                .contains("folder/sample-service/deploy");
        assertThat(updated.attribute("jenkins.job.TEST")).get()
                .satisfies(value -> {
                    assertThat(value.provenance()).isEqualTo(RegistryProvenance.MANUAL);
                    assertThat(value.confirmed()).isTrue();
                });
        assertThat(updated.attribute("jenkins.controller.TEST")).get()
                .satisfies(value -> assertThat(value.provenance())
                        .isEqualTo(RegistryProvenance.DISCOVERED_JENKINS));
        assertThat(updated.alias("sample-api")).get()
                .satisfies(value -> {
                    assertThat(value.provenance()).isEqualTo(RegistryProvenance.MANUAL);
                    assertThat(value.confirmed()).isTrue();
                });
        assertThat(updated.alias("sample-workload")).get()
                .satisfies(value -> {
                    assertThat(value.provenance()).isEqualTo(RegistryProvenance.DISCOVERED_JENKINS);
                    assertThat(value.confirmed()).isFalse();
                });
    }

    @Test
    void rejectsUnboundedOrEmptyDiscoveryUpdates() {
        assertThatThrownBy(() -> RegistryDiscoveryUpdate.of(
                "sample-service", RegistryProvenance.DISCOVERED_GITLAB,
                Map.of("gitlab.repository", "x".repeat(2_049))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value");

        assertThatThrownBy(() -> new RegistryDiscoveryUpdate(
                "sample-service", RegistryProvenance.DISCOVERED_GITLAB, Map.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verified value");
    }

    private static YamlServiceRegistry registry(String yaml) {
        YamlServiceRegistry registry = new YamlServiceRegistry(
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        registry.load();
        return registry;
    }
}
