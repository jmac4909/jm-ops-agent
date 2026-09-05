package com.jmopsagent.conversation;

import com.jmopsagent.domain.ConfidenceLevel;
import com.jmopsagent.domain.EvidenceSource;
import com.jmopsagent.domain.EvidenceType;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.RootCauseCategory;
import com.jmopsagent.orchestration.InvestigationApplicationService;
import com.jmopsagent.persistence.InvestigationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "local-mock"})
@TestPropertySource(properties = "jmops.registry.location=classpath:service-registry-tas-test.yml")
class TasFollowUpEvidenceRefreshIntegrationTest {
    @Autowired InvestigationApplicationService investigations;
    @Autowired FollowUpConversationService followUps;
    @Autowired InvestigationRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void explicitRecentRequestQuestionUsesTheBoundedSplunkBusinessCallOperation() {
        Investigation investigation = investigations.createServiceInvestigation(
                "demo-tas-service", "TEST", "Review the current behavior");
        investigation.complete("Prior operational diagnosis", ConfidenceLevel.LOW,
                RootCauseCategory.UNKNOWN, List.of("Continue read-only review"));
        repository.save(investigation);

        FollowUpExchange exchange = followUps.ask(investigation.getId(), "Show me recent requests");

        assertThat(exchange.isTargetedEvidenceRequested()).isTrue();
        assertThat(exchange.getTargetedEvidenceItems()).isEqualTo(1);
        assertThat(investigations.get(investigation.getId()).getSplunkSearchCount()).isEqualTo(1);
        assertThat(investigations.evidence(investigation.getId())).singleElement().satisfies(item -> {
            assertThat(item.getSourceSystem()).isEqualTo(EvidenceSource.SPLUNK);
            assertThat(item.getEvidenceType()).isEqualTo(EvidenceType.RECENT_BUSINESS_CALLS);
            assertThat(item.getSanitizedContent()).contains("bodyIncluded=false");
        });
    }
}
