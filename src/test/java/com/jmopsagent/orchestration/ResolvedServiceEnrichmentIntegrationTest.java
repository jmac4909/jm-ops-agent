package com.jmopsagent.orchestration;

import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.persistence.InvestigationRepository;
import com.jmopsagent.registry.RegistryDiscoveryUpdate;
import com.jmopsagent.registry.RegistryProvenance;
import com.jmopsagent.registry.ServiceDefinition;
import com.jmopsagent.registry.ServiceRegistry;
import com.jmopsagent.registry.ServiceRegistryDiscoveryHook;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "local-mock"})
@Import(ResolvedServiceEnrichmentIntegrationTest.Configuration.class)
class ResolvedServiceEnrichmentIntegrationTest {

    @Autowired InvestigationApplicationService applicationService;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired InvestigationRepository repository;
    @Autowired ServiceRegistry registry;
    @Autowired RecordingDiscoveryHook discoveryHook;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        discoveryHook.reset();
    }

    @Test
    void enrichesMissingMetadataWithoutReplacingConfirmedManualValues() {
        ServiceDefinition original = registry.resolve("catalog-service").orElseThrow();
        String manualJob = original.attributeValue("jenkins.job").orElseThrow();

        Investigation created = applicationService.createServiceInvestigation(
                "catalog-service", "TEST", "Requests are returning errors");
        orchestrator.investigate(created.getId());

        Investigation completed = applicationService.get(created.getId());
        ServiceDefinition enriched = registry.resolve("catalog-service").orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(discoveryHook.calls).isOne();
        assertThat(discoveryHook.environment).isEqualTo(DeploymentEnvironment.TEST);
        assertThat(discoveryHook.candidate.attributeValue("jenkins.job")).contains(manualJob);
        assertThat(enriched.attributeValue("jenkins.job")).contains(manualJob);
        assertThat(enriched.attribute("jenkins.job")).get().satisfies(value -> {
            assertThat(value.provenance()).isEqualTo(RegistryProvenance.MANUAL);
            assertThat(value.confirmed()).isTrue();
        });
        assertThat(enriched.attributeValue("jenkins.controller.TEST")).contains("test-controller");
        assertThat(enriched.attribute("jenkins.controller.TEST")).get().satisfies(value -> {
            assertThat(value.provenance()).isEqualTo(RegistryProvenance.DISCOVERED_JENKINS);
            assertThat(value.confirmed()).isFalse();
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        RecordingDiscoveryHook recordingDiscoveryHook() {
            return new RecordingDiscoveryHook();
        }
    }

    static final class RecordingDiscoveryHook implements ServiceRegistryDiscoveryHook {
        private int calls;
        private ServiceDefinition candidate;
        private DeploymentEnvironment environment;

        @Override
        public Optional<RegistryDiscoveryUpdate> discover(
                ServiceDefinition service, DeploymentEnvironment environment) {
            calls++;
            candidate = service;
            this.environment = environment;
            return Optional.of(RegistryDiscoveryUpdate.of(service.service(),
                    RegistryProvenance.DISCOVERED_JENKINS,
                    Map.of(
                            "jenkins.job", "unconfirmed-discovered-job",
                            "jenkins.controller.TEST", "test-controller")));
        }

        void reset() {
            calls = 0;
            candidate = null;
            environment = null;
        }
    }
}
