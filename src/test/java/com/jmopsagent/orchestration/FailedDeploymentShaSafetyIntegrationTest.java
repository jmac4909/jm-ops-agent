package com.jmopsagent.orchestration;

import com.jmopsagent.connector.CommitChange;
import com.jmopsagent.connector.DeploymentInfo;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.RepositoryRef;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.domain.RootCauseCategory;
import com.jmopsagent.gitlab.GitLabConnector;
import com.jmopsagent.jenkins.JenkinsConnector;
import com.jmopsagent.persistence.InvestigationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "local-mock"})
@Import(FailedDeploymentShaSafetyIntegrationTest.Configuration.class)
class FailedDeploymentShaSafetyIntegrationTest {
    @Autowired InvestigationApplicationService applicationService;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired InvestigationStateService state;
    @Autowired InvestigationRepository repository;
    @Autowired RejectingGitLabConnector gitLab;
    @Autowired FailureOnlyJenkinsConnector jenkins;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        gitLab.reset();
        jenkins.reset();
    }

    @Test
    void stopsCodeInspectionWhenJenkinsHasNoSuccessfulDeployedSha() {
        Investigation created = applicationService.createServiceInvestigation("failed-candidate-only-service", "TEST",
                "The candidate deployment failed");
        orchestrator.investigate(created.getId());
        Investigation operational = applicationService.get(created.getId());
        assertThat(operational.getRootCauseCategory()).isEqualTo(RootCauseCategory.DEPLOYMENT);
        String operationalDiagnosis = operational.getFinalDiagnosis();
        state.beginCodeInvestigation(created.getId());

        orchestrator.investigateCode(created.getId());

        Investigation completed = applicationService.get(created.getId());
        assertThat(completed.getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(completed.getFinalDiagnosis())
                .contains(operationalDiagnosis)
                .contains("Exact Git SHA for a successful deployment is unavailable")
                .contains("no source code was inspected");
        assertThat(gitLab.calls()).isZero();
    }

    @Test
    void usesNewestSuccessfulShaForChangesAndCodeWhenLatestCandidateFailed() {
        jenkins.includeSuccessfulHistory();
        Investigation created = applicationService.createServiceInvestigation("failed-candidate-only-service", "TEST",
                "The newest deployment candidate failed");

        orchestrator.investigate(created.getId());

        assertThat(gitLab.revisionCalls())
                .contains("compare:feedface1234567890->cafebabe1234567890")
                .noneMatch(call -> call.contains("deadbeef1234567890"));
        state.beginCodeInvestigation(created.getId());
        orchestrator.investigateCode(created.getId());

        assertThat(applicationService.evidence(created.getId()))
                .filteredOn(item -> item.getEvidenceType().name().equals("SOURCE_CODE"))
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item.getMetadataJson())
                        .contains("cafebabe1234567890")
                        .doesNotContain("deadbeef1234567890"));
        assertThat(gitLab.revisionCalls())
                .contains("tree:cafebabe1234567890", "file:cafebabe1234567890")
                .noneMatch(call -> call.contains("deadbeef1234567890"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        FailureOnlyJenkinsConnector failureOnlyJenkinsConnector() {
            return new FailureOnlyJenkinsConnector();
        }

        @Bean
        @Primary
        RejectingGitLabConnector rejectingGitLabConnector() {
            return new RejectingGitLabConnector();
        }
    }

    static final class FailureOnlyJenkinsConnector implements JenkinsConnector {
        private final DeploymentInfo failure = new DeploymentInfo("failed-candidate-only-service", Environment.TEST,
                "failed-candidate-only-service-test-deploy", 91, "FAILURE", Instant.parse("2026-01-15T10:37:00Z"),
                "deadbeef1234567890", null, List.of(), List.of("Deploy to TEST"),
                List.of("Deployment verification failed"), Map.of("deployed", "false"));
        private final DeploymentInfo deployed = successful(90, "cafebabe1234567890");
        private final DeploymentInfo previous = successful(89, "feedface1234567890");
        private boolean includeSuccessfulHistory;

        @Override
        public Optional<DeploymentInfo> getLatestDeployment(String service, Environment environment) {
            return Optional.of(failure);
        }

        @Override
        public List<DeploymentInfo> getLastBuilds(String service, Environment environment, int limit) {
            return includeSuccessfulHistory ? List.of(failure, previous, deployed) : List.of(failure);
        }

        void includeSuccessfulHistory() {
            includeSuccessfulHistory = true;
        }

        void reset() {
            includeSuccessfulHistory = false;
        }

        private static DeploymentInfo successful(long build, String sha) {
            return new DeploymentInfo("failed-candidate-only-service", Environment.TEST,
                    "failed-candidate-only-service-test-deploy", build, "SUCCESS",
                    Instant.parse("2026-01-15T10:37:00Z").minusSeconds(91 - build), sha, null,
                    List.of(), List.of(), List.of(), Map.of("deployed", "true"));
        }
    }

    static final class RejectingGitLabConnector implements GitLabConnector {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> revisionCalls = new ArrayList<>();

        @Override
        public Optional<RepositoryRef> resolveRepository(String service) {
            calls.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public List<CommitChange> getCommits(String service, String revision, int limit) {
            calls.incrementAndGet();
            revisionCalls.add("commits:" + revision);
            return List.of();
        }

        @Override
        public List<CommitChange> compareRevisions(String service, String fromRevision, String toRevision,
                                                   int maxDiffCharacters) {
            calls.incrementAndGet();
            revisionCalls.add("compare:" + fromRevision + "->" + toRevision);
            return List.of();
        }

        @Override
        public Optional<String> getFileContent(String service, String revision, String path, int maxCharacters) {
            calls.incrementAndGet();
            revisionCalls.add("file:" + revision);
            return Optional.of("class App {}");
        }

        @Override
        public List<String> getRepositoryTree(String service, String revision, String path, int limit) {
            calls.incrementAndGet();
            revisionCalls.add("tree:" + revision);
            return List.of("src/main/java/com/example/App.java");
        }

        int calls() {
            return calls.get();
        }

        List<String> revisionCalls() {
            return List.copyOf(revisionCalls);
        }

        void reset() {
            calls.set(0);
            revisionCalls.clear();
        }
    }
}
