package com.jmopsagent.registry;

import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.RepositoryRef;
import com.jmopsagent.gitlab.GitLabConnector;
import com.jmopsagent.domain.DeploymentEnvironment;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Confirms an exact repository match and projects only bounded registry identifiers. */
@Component
@Profile("local-live")
@Order(100)
public class GitLabServiceRegistryDiscoveryHook implements ServiceRegistryDiscoveryHook {

    private static final int MAX_REPOSITORY_URL_CHARACTERS = 2_000;
    private static final int MAX_PROJECT_ID_CHARACTERS = 500;

    private final GitLabConnector connector;

    public GitLabServiceRegistryDiscoveryHook(GitLabConnector connector) {
        this.connector = connector;
    }

    @Override
    public Optional<RegistryDiscoveryUpdate> discover(ServiceDefinition service,
            DeploymentEnvironment environment) {
        String candidate = ConnectorInputValidator.service(service.service());
        Optional<RepositoryRef> resolved;
        try {
            resolved = connector.resolveRepository(candidate);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        if (resolved.isEmpty() || !isExactRepository(resolved.get(), candidate)) {
            return Optional.empty();
        }

        RepositoryRef repository = resolved.get();
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("gitlab.repository", repository.repositoryUrl().trim());
        safeBranch(repository.defaultBranch()).ifPresent(value -> attributes.put("gitlab.defaultBranch", value));
        return Optional.of(RegistryDiscoveryUpdate.of(candidate,
                RegistryProvenance.DISCOVERED_GITLAB, attributes));
    }

    private static boolean isExactRepository(RepositoryRef repository, String candidate) {
        if (repository == null || !isBoundedIdentifier(repository.projectId(), MAX_PROJECT_ID_CHARACTERS)
                || repository.repositoryUrl() == null
                || repository.repositoryUrl().length() > MAX_REPOSITORY_URL_CHARACTERS) {
            return false;
        }
        try {
            URI uri = URI.create(repository.repositoryUrl().trim());
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                return false;
            }
            String path = uri.getPath();
            if (path == null || path.isBlank() || path.endsWith("/") || path.contains("//")) {
                return false;
            }
            for (String segment : path.split("/")) {
                if (segment.equals(".") || segment.equals("..")) {
                    return false;
                }
            }
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            return leaf.equalsIgnoreCase(candidate)
                    || leaf.endsWith(".git")
                    && leaf.substring(0, leaf.length() - ".git".length()).equalsIgnoreCase(candidate);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static Optional<String> safeBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ConnectorInputValidator.revision(branch));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static boolean isBoundedIdentifier(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.indexOf('\0') < 0 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }
}
