package com.jmopsagent.conversation;

import com.jmopsagent.domain.EvidenceSource;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.orchestration.InvestigationApplicationService;
import com.jmopsagent.orchestration.InvestigationOrchestrator;
import com.jmopsagent.persistence.InvestigationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "local-mock"})
class FollowUpEvidenceRefreshIntegrationTest {
    @Autowired InvestigationApplicationService investigations;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired FollowUpConversationService followUps;
    @Autowired InvestigationRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void explicitRecentRequestQuestionCollectsOneBoundedKubernetesSample() {
        Investigation investigation = investigations.createTrackingInvestigation("DEMO-TRACE-001", "TEST");
        orchestrator.investigate(investigation.getId());
        int evidenceBefore = investigations.evidence(investigation.getId()).size();
        int splunkSearchesBefore = investigations.get(investigation.getId()).getSplunkSearchCount();

        FollowUpExchange exchange = followUps.ask(investigation.getId(), "Show me recent requests");

        assertThat(exchange.isTargetedEvidenceRequested()).isTrue();
        assertThat(exchange.getTargetedEvidenceItems()).isEqualTo(1);
        assertThat(exchange.getAnswer()).contains("bounded Kubernetes pod-log sample")
                .contains("not guaranteed to represent complete business-call traffic");
        assertThat(investigations.evidence(investigation.getId())).hasSize(evidenceBefore + 1)
                .filteredOn(item -> item.getSourceSystem() == EvidenceSource.KUBERNETES)
                .isNotEmpty();
        assertThat(investigations.get(investigation.getId()).getSplunkSearchCount())
                .isEqualTo(splunkSearchesBefore);
    }

    @Test
    void targetedRefreshLimitPreventsRepeatedConnectorCollection() {
        Investigation investigation = investigations.createTrackingInvestigation("DEMO-TRACE-001", "TEST");
        orchestrator.investigate(investigation.getId());
        FollowUpExchange first = followUps.ask(investigation.getId(), "Show recent calls");
        int evidenceAfterFirst = investigations.evidence(investigation.getId()).size();

        FollowUpExchange second = followUps.ask(investigation.getId(), "What recent calls are there now?");

        assertThat(first.getTargetedEvidenceItems()).isPositive();
        assertThat(second.isTargetedEvidenceRequested()).isTrue();
        assertThat(second.getTargetedEvidenceItems()).isZero();
        assertThat(investigations.evidence(investigation.getId())).hasSize(evidenceAfterFirst);
    }

    @Test
    void explanatoryQuestionUsesStoredEvidenceOnly() {
        Investigation investigation = investigations.createTrackingInvestigation("DEMO-TRACE-001", "TEST");
        orchestrator.investigate(investigation.getId());
        int evidenceBefore = investigations.evidence(investigation.getId()).size();

        FollowUpExchange exchange = followUps.ask(investigation.getId(), "Why is configuration the likely cause?");

        assertThat(exchange.isTargetedEvidenceRequested()).isFalse();
        assertThat(exchange.getTargetedEvidenceItems()).isZero();
        assertThat(investigations.evidence(investigation.getId())).hasSize(evidenceBefore);
        assertThat(exchange.getAnswer()).contains("No live evidence was recollected");
    }
}
