package com.jmopsagent.orchestration;

import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationEventType;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.persistence.InvestigationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "jmops.limits.max-evidence-items=2"
})
@ActiveProfiles({"test", "local-mock"})
class EvidenceItemLimitIntegrationTest {
    @Autowired InvestigationApplicationService applicationService;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired InvestigationRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void neverPersistsMoreThanTheConfiguredEvidenceLimit() {
        Investigation created = applicationService.createTrackingInvestigation("DEMO-TRACE-001", "TEST");

        orchestrator.investigate(created.getId());

        Investigation completed = applicationService.get(created.getId());
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(applicationService.evidence(created.getId())).hasSize(2);
        assertThat(completed.getFinalDiagnosis()).contains("Maximum evidence items reached");
        assertThat(applicationService.timeline(created.getId()))
                .anySatisfy(event -> assertThat(event.getType()).isEqualTo(InvestigationEventType.LIMIT_REACHED));
    }
}
