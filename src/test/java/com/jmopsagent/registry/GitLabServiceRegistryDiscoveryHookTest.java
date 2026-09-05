package com.jmopsagent.registry;

import com.jmopsagent.connector.RepositoryRef;
import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.gitlab.GitLabConnector;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitLabServiceRegistryDiscoveryHookTest {

    @Test
    void projectsOnlyAnExactRepositoryAndSafeBranch() {
        GitLabConnector connector = mock(GitLabConnector.class);
        when(connector.resolveRepository("sample-service")).thenReturn(Optional.of(new RepositoryRef(
                "group/sample-service", "https://gitlab.example.invalid/group/sample-service", "main")));
        GitLabServiceRegistryDiscoveryHook hook = new GitLabServiceRegistryDiscoveryHook(connector);

        RegistryDiscoveryUpdate update = hook.discover(
                new ServiceDefinition("sample-service"), DeploymentEnvironment.TEST).orElseThrow();

        assertThat(update.provenance()).isEqualTo(RegistryProvenance.DISCOVERED_GITLAB);
        assertThat(update.attributes()).containsOnlyKeys("gitlab.repository", "gitlab.defaultBranch");
        assertThat(update.attributes().get("gitlab.repository"))
                .containsExactly("https://gitlab.example.invalid/group/sample-service");
        assertThat(update.attributes().values()).noneMatch(values -> values.contains("group/sample-service"));
        assertThat(update.aliases()).isEmpty();
    }

    @Test
    void rejectsARepositoryWhoseLeafIsOnlyAFuzzyMatch() {
        GitLabConnector connector = mock(GitLabConnector.class);
        when(connector.resolveRepository("sample-service")).thenReturn(Optional.of(new RepositoryRef(
                "group/sample-service-old",
                "https://gitlab.example.invalid/group/sample-service-old", "main")));
        GitLabServiceRegistryDiscoveryHook hook = new GitLabServiceRegistryDiscoveryHook(connector);

        assertThat(hook.discover(new ServiceDefinition("sample-service"), DeploymentEnvironment.DEV)).isEmpty();
    }

    @Test
    void rejectsCredentialsOrTraversalInARepositoryUrl() {
        GitLabConnector connector = mock(GitLabConnector.class);
        when(connector.resolveRepository("sample-service")).thenReturn(Optional.of(new RepositoryRef(
                "group/sample-service",
                "https://" + "reader@example.invalid/group/../sample-service", "main")));
        GitLabServiceRegistryDiscoveryHook hook = new GitLabServiceRegistryDiscoveryHook(connector);

        assertThat(hook.discover(new ServiceDefinition("sample-service"), DeploymentEnvironment.DEV)).isEmpty();
    }
}
