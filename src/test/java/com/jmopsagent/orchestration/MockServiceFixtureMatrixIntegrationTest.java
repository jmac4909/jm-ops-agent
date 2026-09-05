package com.jmopsagent.orchestration;

import com.jmopsagent.domain.EvidenceSource;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationEventType;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.domain.RootCauseCategory;
import com.jmopsagent.persistence.InvestigationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "local-mock"})
class MockServiceFixtureMatrixIntegrationTest {
    @Autowired InvestigationApplicationService applicationService;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired InvestigationStateService state;
    @Autowired InvestigationRepository repository;

    @BeforeEach
    void resetInvestigations() {
        repository.deleteAll();
    }

    @ParameterizedTest(name = "{0} is classified as {1}")
    @CsvSource({
            "downstream-500-service, DEPENDENCY",
            "deployment-failure-service, DEPLOYMENT",
            "bad-config-service, CONFIG",
            "database-error-service, DEPENDENCY",
            "readiness-failure-service, RUNTIME"
    })
    void classifiesEvidenceDrivenMockScenarios(String service, RootCauseCategory expectedCategory) {
        Investigation created = applicationService.createServiceInvestigation(service, "TEST",
                "Investigate the representative mock failure");

        orchestrator.investigate(created.getId());

        Investigation completed = applicationService.get(created.getId());
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(completed.getRootCauseCategory()).isEqualTo(expectedCategory);
        if (service.equals("downstream-500-service")) {
            assertThat(applicationService.evidence(created.getId()))
                    .anySatisfy(item -> {
                        assertThat(item.getEvidenceType().name()).isEqualTo("DEPENDENCY_STATUS");
                        assertThat(item.getSanitizedContent()).contains("inventory-api returned HTTP 500");
                    });
        }
    }

    @Test
    void healthyServiceCompletesWithABoundedUnknownConclusionWithoutInventingAFault() {
        Investigation created = applicationService.createServiceInvestigation("healthy-service", "DEV",
                "Check an unconfirmed report of errors");

        orchestrator.investigate(created.getId());

        Investigation completed = applicationService.get(created.getId());
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(completed.getRootCauseCategory()).isEqualTo(RootCauseCategory.UNKNOWN);
        assertThat(completed.getFinalDiagnosis())
                .containsIgnoringCase("does not contain a sufficiently specific failure signature")
                .contains("Maximum Claude iterations reached");
        assertThat(applicationService.evidence(created.getId()))
                .noneSatisfy(item -> assertThat(item.getSourceSystem()).isEqualTo(EvidenceSource.DATABASE));
        assertThat(applicationService.timeline(created.getId()))
                .anySatisfy(event -> assertThat(event.getType()).isEqualTo(InvestigationEventType.LIMIT_REACHED));
    }

    @Test
    void failedCandidateBuildUsesLastSuccessfulShaForExplicitCodeInspection() {
        Investigation created = applicationService.createServiceInvestigation("deployment-failure-service", "TEST",
                "Candidate deployment failed");
        orchestrator.investigate(created.getId());
        state.beginCodeInvestigation(created.getId());

        orchestrator.investigateCode(created.getId());

        assertThat(applicationService.get(created.getId()).getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(applicationService.evidence(created.getId()))
                .filteredOn(item -> item.getEvidenceType().name().equals("SOURCE_CODE"))
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item.getMetadataJson())
                        .contains("previous1")
                        .doesNotContain("deadbeef1234567890"));
        assertThat(applicationService.timeline(created.getId()))
                .anySatisfy(event -> assertThat(event.getMessage())
                        .contains("deployed SHA previous1"));
    }
}
