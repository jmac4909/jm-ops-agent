package com.jmopsagent.orchestration;

import com.jmopsagent.claude.ClaudeCodeClient;
import com.jmopsagent.claude.DeterministicClaudeCodeClient;
import com.jmopsagent.connector.mock.MockFixtures;
import com.jmopsagent.conversation.FollowUpConversationService;
import com.jmopsagent.domain.EvidenceSource;
import com.jmopsagent.domain.EvidenceType;
import com.jmopsagent.domain.FeedbackRating;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationEventType;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.domain.RootCauseCategory;
import com.jmopsagent.persistence.InvestigationRepository;
import com.jmopsagent.gitlab.GitLabConnector;
import com.jmopsagent.gitlab.MockGitLabConnector;
import com.jmopsagent.jenkins.JenkinsConnector;
import com.jmopsagent.jenkins.MockJenkinsConnector;
import com.jmopsagent.kubernetes.KubernetesConnector;
import com.jmopsagent.kubernetes.MockKubernetesConnector;
import com.jmopsagent.splunk.MockSplunkConnector;
import com.jmopsagent.splunk.SplunkConnector;
import com.jmopsagent.tas.MockTasConnector;
import com.jmopsagent.tas.TasConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "local-mock"})
class TrackingInvestigationIntegrationTest {
    @Autowired InvestigationApplicationService applicationService;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired InvestigationStateService state;
    @Autowired FollowUpConversationService followUps;
    @Autowired InvestigationRepository repository;
    @Autowired ApplicationContext context;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void tracesFictionalRequestLocalizesCatalogServiceAndDiagnosesBadConfiguration() {
        Investigation created = applicationService.createTrackingInvestigation("DEMO-TRACE-001", "TEST");

        orchestrator.investigate(created.getId());

        Investigation completed = applicationService.get(created.getId());
        var evidence = applicationService.evidence(created.getId());
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(completed.getService()).isEqualTo("catalog-service");
        assertThat(completed.getRootCauseCategory()).isEqualTo(RootCauseCategory.CONFIG);
        assertThat(completed.getFinalDiagnosis()).containsIgnoringCase("parameter");
        assertThat(completed.getClaudeSessionId()).startsWith("mock-");
        assertThat(completed.getRecommendedActions()).isNotEmpty();
        assertThat(evidence).extracting(item -> item.getSourceSystem())
                .contains(EvidenceSource.SPLUNK, EvidenceSource.KUBERNETES,
                        EvidenceSource.JENKINS, EvidenceSource.GITLAB);
        assertThat(evidence).filteredOn(item -> item.getEvidenceType() == EvidenceType.CALL_CHAIN).hasSize(4);
        assertThat(evidence).allSatisfy(item -> assertThat(item.getSanitizedContent())
                .doesNotContain("Bearer ", "password=", "api_key="));
        assertThat(applicationService.timeline(created.getId()))
                .anySatisfy(event -> assertThat(event.getMessage()).contains("Failure localized"));
    }

    @Test
    void serviceTriageBranchesToDatabaseEvidenceOnlyWhenLogsPointThere() {
        Investigation created = applicationService.createServiceInvestigation("database-error-service", "DEV",
                "Service returns 500 while reading provider data");

        orchestrator.investigate(created.getId());

        Investigation completed = applicationService.get(created.getId());
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(completed.getRootCauseCategory()).isEqualTo(RootCauseCategory.DEPENDENCY);
        assertThat(completed.getFinalDiagnosis()).containsIgnoringCase("database");
        assertThat(applicationService.evidence(created.getId()))
                .anySatisfy(item -> assertThat(item.getSourceSystem()).isEqualTo(EvidenceSource.DATABASE));
    }

    @Test
    void serviceTriageClassifiesReadinessFailureAsRuntimeWhenJenkinsSucceeded() {
        Investigation created = applicationService.createServiceInvestigation("readiness-failure-service", "TEST",
                "Pods are repeatedly failing readiness after startup");

        orchestrator.investigate(created.getId());

        Investigation completed = applicationService.get(created.getId());
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(completed.getRootCauseCategory()).isEqualTo(RootCauseCategory.RUNTIME);
        assertThat(completed.getFinalDiagnosis()).containsIgnoringCase("runtime health");
        assertThat(applicationService.evidence(created.getId()))
                .filteredOn(item -> item.getSourceSystem() == EvidenceSource.JENKINS)
                .anySatisfy(item -> assertThat(item.getSummary()).contains("SUCCESS"));
    }

    @Test
    void productionLikeEnvironmentsAreRejectedBeforePersistenceOrConnectorUse() {
        assertThatThrownBy(() -> applicationService.createTrackingInvestigation("DEMO-TRACE-001", "PROD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DEV and TEST");
        assertThat(repository.count()).isZero();
    }

    @Test
    void feedbackCannotConfirmAnActiveInvestigation() {
        Investigation created = applicationService.createTrackingInvestigation("DEMO-TRACE-001", "TEST");

        assertThatThrownBy(() -> applicationService.recordFeedback(created.getId(), "YES", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only after");
        assertThat(applicationService.get(created.getId()).getUserFeedback()).isNull();
    }

    @Test
    void localMockProfileWiresOneDeterministicImplementationPerBoundary() {
        assertThat(context.getBeansOfType(ClaudeCodeClient.class)).hasSize(1);
        assertThat(context.getBean(ClaudeCodeClient.class)).isInstanceOf(DeterministicClaudeCodeClient.class);
        assertThat(context.getBeansOfType(KubernetesConnector.class)).hasSize(1);
        assertThat(context.getBean(KubernetesConnector.class)).isInstanceOf(MockKubernetesConnector.class);
        assertThat(context.getBeansOfType(TasConnector.class)).hasSize(1);
        assertThat(context.getBean(TasConnector.class)).isInstanceOf(MockTasConnector.class);
        assertThat(context.getBeansOfType(SplunkConnector.class)).hasSize(1);
        assertThat(context.getBean(SplunkConnector.class)).isInstanceOf(MockSplunkConnector.class);
        assertThat(context.getBeansOfType(JenkinsConnector.class)).hasSize(1);
        assertThat(context.getBean(JenkinsConnector.class)).isInstanceOf(MockJenkinsConnector.class);
        assertThat(context.getBeansOfType(GitLabConnector.class)).hasSize(1);
        assertThat(context.getBean(GitLabConnector.class)).isInstanceOf(MockGitLabConnector.class);
    }

    @Test
    void feedbackIsPersistedForHistoricalLearning() {
        Investigation created = applicationService.createTrackingInvestigation("DEMO-TRACE-001", "TEST");
        orchestrator.investigate(created.getId());

        applicationService.recordFeedback(created.getId(), "PARTIALLY",
                "Parameter Store path typo", "Corrected through reviewed deployment");

        Investigation stored = applicationService.get(created.getId());
        assertThat(stored.getUserFeedback()).isEqualTo(FeedbackRating.PARTIALLY);
        assertThat(stored.getActualRootCause()).isEqualTo("Parameter Store path typo");
        assertThat(stored.getSuccessfulRemediation()).contains("reviewed deployment");
    }

    @Test
    void sensitiveFeedbackIsRedactedBeforeItBecomesHistoricalMemory() {
        Investigation created = applicationService.createTrackingInvestigation("DEMO-TRACE-001", "TEST");
        orchestrator.investigate(created.getId());

        applicationService.recordFeedback(created.getId(), "NO",
                "The configured password=hunter2 was rejected",
                "Used Authorization: Bearer abcdefghijklmnop through the reviewed process");

        Investigation stored = applicationService.get(created.getId());
        assertThat(stored.getActualRootCause()).contains("[REDACTED:CREDENTIAL]").doesNotContain("hunter2");
        assertThat(stored.getSuccessfulRemediation()).contains("[REDACTED:AUTHORIZATION]")
                .doesNotContain("abcdefghijklmnop");
        assertThat(applicationService.timeline(created.getId()))
                .anySatisfy(event -> assertThat(event.getMessage()).contains("redacted from submitted feedback"));
    }

    @Test
    void followUpResumesStoredContextAndPersistsOnlySanitizedText() {
        Investigation created = applicationService.createTrackingInvestigation("DEMO-TRACE-001", "TEST");
        orchestrator.investigate(created.getId());
        String sessionId = applicationService.get(created.getId()).getClaudeSessionId();

        var exchange = followUps.ask(created.getId(),
                "Why? Authorization: Bearer abcdefghijklmnop");

        assertThat(exchange.getQuestion()).contains("[REDACTED:AUTHORIZATION]")
                .doesNotContain("abcdefghijklmnop");
        assertThat(exchange.getAnswer()).contains("No live evidence was recollected");
        assertThat(exchange.getAnsweredAt()).isNotNull();
        assertThat(exchange.isRedactionApplied()).isTrue();
        assertThat(followUps.list(created.getId())).singleElement().satisfies(stored -> {
            assertThat(stored.getId()).isEqualTo(exchange.getId());
            assertThat(stored.getAnswer()).isNotBlank();
        });
        assertThat(applicationService.get(created.getId()).getClaudeSessionId()).isEqualTo(sessionId);
    }

    @Test
    void codeEscalationInspectsOnlyFilesAtTheExactDeployedSha() {
        Investigation created = applicationService.createTrackingInvestigation("DEMO-TRACE-001", "TEST");
        orchestrator.investigate(created.getId());
        state.beginCodeInvestigation(created.getId());

        orchestrator.investigateCode(created.getId());

        Investigation completed = applicationService.get(created.getId());
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(applicationService.evidence(created.getId()))
                .filteredOn(item -> item.getEvidenceType() == EvidenceType.SOURCE_CODE)
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item.getMetadataJson()).contains(MockFixtures.DEPLOYED_SHA));
        assertThat(applicationService.timeline(created.getId()))
                .filteredOn(event -> event.getType() == InvestigationEventType.EVIDENCE_COLLECTED)
                .anySatisfy(event -> assertThat(event.getMessage())
                        .contains(MockFixtures.DEPLOYED_SHA.substring(0, 10)));
    }
}
