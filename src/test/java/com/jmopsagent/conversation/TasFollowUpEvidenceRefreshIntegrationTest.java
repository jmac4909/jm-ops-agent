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
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.jmopsagent.splunk.SplunkConnector splunk;

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
        var query = org.mockito.ArgumentCaptor.forClass(com.jmopsagent.connector.EvidenceQuery.class);
        org.mockito.Mockito.verify(splunk).searchRecentBusinessCallsDetailed(
                org.mockito.ArgumentMatchers.eq("demo-tas-service"),
                org.mockito.ArgumentMatchers.eq(com.jmopsagent.connector.Environment.TEST),
                query.capture(), org.mockito.ArgumentMatchers.any());
        assertThat(java.time.Duration.between(query.getValue().from(), query.getValue().to()))
                .isEqualTo(java.time.Duration.ofHours(72));
        assertThat(investigations.evidence(investigation.getId())).singleElement().satisfies(item -> {
            assertThat(item.getSourceSystem()).isEqualTo(EvidenceSource.SPLUNK);
            assertThat(item.getEvidenceType()).isEqualTo(EvidenceType.RECENT_BUSINESS_CALLS);
            assertThat(item.getSanitizedContent()).contains("bodyIncluded=false");
        });
    }
}
