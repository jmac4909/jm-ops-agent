package com.jmopsagent.gitlab;

import static com.jmopsagent.connector.mock.MockFixtures.DEPLOYED_SHA;
import static com.jmopsagent.connector.mock.MockFixtures.DEPLOYMENT_TIME;
import static com.jmopsagent.connector.mock.MockFixtures.scenario;

import com.jmopsagent.connector.CommitChange;
import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.RepositoryRef;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local-mock")
public class MockGitLabConnector implements GitLabConnector {
    @Override
    public Optional<RepositoryRef> resolveRepository(String service) {
        String safeService = ConnectorInputValidator.service(service);
        return Optional.of(new RepositoryRef("mock/" + safeService,
                "https://example.invalid/mock/gitlab/" + safeService, "develop"));
    }

    @Override
    public List<CommitChange> getCommits(String service, String revision, int limit) {
        String safeService = ConnectorInputValidator.service(service);
        ConnectorInputValidator.revision(revision);
        ConnectorInputValidator.boundedLimit(limit, 100);
        return List.of(change(safeService));
    }

    @Override
    public List<CommitChange> compareRevisions(String service, String fromRevision, String toRevision,
            int maxDiffCharacters) {
        String safeService = ConnectorInputValidator.service(service);
        ConnectorInputValidator.revision(fromRevision);
        ConnectorInputValidator.revision(toRevision);
        ConnectorInputValidator.boundedLimit(maxDiffCharacters, 200_000);
        return List.of(change(safeService));
    }

    @Override
    public Optional<String> getFileContent(String service, String revision, String path, int maxCharacters) {
        String safeService = ConnectorInputValidator.service(service);
        ConnectorInputValidator.revision(revision);
        String safePath = ConnectorInputValidator.repositoryPath(path);
        ConnectorInputValidator.boundedLimit(maxCharacters, 200_000);
        String content = scenario(safeService).equals("bad-config")
                ? "# mock non-secret deployment configuration\nDB_URL_PARAMETER: /catalog/db-ur\n"
                : "# mock non-secret configuration\nhealth.readiness-path: /actuator/health/readiness\n";
        return Optional.of(content.substring(0, Math.min(content.length(), maxCharacters)));
    }

    @Override
    public List<String> getRepositoryTree(String service, String revision, String path, int limit) {
        ConnectorInputValidator.service(service);
        ConnectorInputValidator.revision(revision);
        if (path != null && !path.isBlank()) ConnectorInputValidator.repositoryPath(path);
        ConnectorInputValidator.boundedLimit(limit, 1_000);
        return List.of("src/main/java/com/example/Application.java", "deploy/application-test.yml", "pom.xml")
                .stream().limit(limit).toList();
    }

    private static CommitChange change(String service) {
        if (scenario(service).equals("bad-config")) {
            return new CommitChange(DEPLOYED_SHA, "Update TEST parameter path", "mock-developer", DEPLOYMENT_TIME.minusSeconds(900),
                    List.of("deploy/application-test.yml"),
                    "- DB_URL_PARAMETER: /catalog/db-url\n+ DB_URL_PARAMETER: /catalog/db-ur");
        }
        return new CommitChange(DEPLOYED_SHA, "Routine service update", "mock-developer", DEPLOYMENT_TIME.minusSeconds(900),
                List.of("pom.xml"), "- 1.0.0\n+ 1.0.1");
    }
}
