package com.jmopsagent.gitlab;

import tools.jackson.databind.JsonNode;
import com.jmopsagent.connector.CommitChange;
import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.ConnectorEndpointValidator;
import com.jmopsagent.connector.RepositoryRef;
import com.jmopsagent.registry.ServiceDefinition;
import com.jmopsagent.registry.ServiceRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
@Profile("local-live")
public class LiveGitLabConnector implements GitLabConnector {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveGitLabConnector.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final WebClient client;
    private final boolean configured;
    private final ServiceRegistry serviceRegistry;
    private final URI baseUri;

    public LiveGitLabConnector(WebClient.Builder builder, ServiceRegistry serviceRegistry,
            @Value("${jmops.integrations.gitlab.base-url:}") String baseUrl,
            @Value("${jmops.integrations.gitlab.token:}") String token) {
        String safeBaseUrl;
        try {
            safeBaseUrl = ConnectorEndpointValidator.optionalHttpsBaseUrl(baseUrl, "GitLab");
        } catch (IllegalArgumentException ignored) {
            // External settings may be repaired after startup. Keep this bean inert and unauthenticated.
            safeBaseUrl = "";
        }
        this.configured = !safeBaseUrl.isBlank() && token != null && !token.isBlank();
        this.baseUri = safeBaseUrl.isBlank() ? null : URI.create(safeBaseUrl);
        this.serviceRegistry = serviceRegistry;
        WebClient.Builder configuredBuilder = builder.clone();
        if (!safeBaseUrl.isBlank()) configuredBuilder.baseUrl(safeBaseUrl);
        if (configured) configuredBuilder.defaultHeader("PRIVATE-TOKEN", token);
        this.client = configuredBuilder.build();
    }

    @Override
    public Optional<RepositoryRef> resolveRepository(String service) {
        String safeService = ConnectorInputValidator.service(service);
        if (!configured) return Optional.empty();
        Optional<ServiceDefinition> definition = serviceRegistry.resolve(safeService);
        Optional<String> registeredUrl = definition.flatMap(value -> value.attributeValue("gitlab.repository"));
        if (registeredUrl.isPresent()) {
            Optional<String> projectId = projectIdFromUrl(registeredUrl.get());
            if (projectId.isPresent()) {
                String defaultBranch = definition.map(this::defaultBranch).orElse("main");
                return Optional.of(new RepositoryRef(projectId.get(), registeredUrl.get(), defaultBranch));
            }
        }
        try {
            JsonNode projects = client.get().uri(uri -> uri.path("/api/v4/projects")
                            .queryParam("search", safeService).queryParam("simple", true).queryParam("per_page", 20).build())
                    .retrieve().bodyToMono(JsonNode.class).block(REQUEST_TIMEOUT);
            if (projects == null || !projects.isArray()) return Optional.empty();
            for (JsonNode project : projects) {
                String name = project.path("name").asText("");
                String path = project.path("path").asText("");
                if (name.equalsIgnoreCase(safeService) || path.equalsIgnoreCase(safeService)) {
                    return Optional.of(toRepository(project));
                }
            }
            return Optional.empty();
        } catch (RuntimeException ex) {
            throw failure("resolve-repository", ex);
        }
    }

    @Override
    public List<CommitChange> getCommits(String service, String revision, int limit) {
        int safeLimit = ConnectorInputValidator.boundedLimit(limit, 100);
        String safeRevision = ConnectorInputValidator.revision(revision);
        Optional<RepositoryRef> repository = resolveRepository(service);
        if (repository.isEmpty()) return List.of();
        try {
            JsonNode commits = client.get().uri(uri -> uri.pathSegment("api", "v4", "projects", repository.get().projectId(),
                                    "repository", "commits")
                            .queryParam("ref_name", safeRevision).queryParam("per_page", safeLimit).build())
                    .retrieve().bodyToMono(JsonNode.class).block(REQUEST_TIMEOUT);
            if (commits == null || !commits.isArray()) return List.of();
            List<CommitChange> result = new ArrayList<>();
            for (JsonNode commit : commits) {
                result.add(new CommitChange(commit.path("id").asText(), commit.path("title").asText(),
                        commit.path("author_name").asText(), parseInstant(commit.path("committed_date").asText()),
                        List.of(), ""));
            }
            return List.copyOf(result);
        } catch (RuntimeException ex) {
            throw failure("commits", ex);
        }
    }

    @Override
    public List<CommitChange> compareRevisions(String service, String fromRevision, String toRevision,
            int maxDiffCharacters) {
        String safeFrom = ConnectorInputValidator.revision(fromRevision);
        String safeTo = ConnectorInputValidator.revision(toRevision);
        ConnectorInputValidator.boundedLimit(maxDiffCharacters, 200_000);
        Optional<RepositoryRef> repository = resolveRepository(service);
        if (repository.isEmpty()) return List.of();
        try {
            JsonNode comparison = client.get().uri(uri -> uri.pathSegment("api", "v4", "projects",
                                    repository.get().projectId(), "repository", "compare")
                            .queryParam("from", safeFrom).queryParam("to", safeTo).build())
                    .retrieve().bodyToMono(JsonNode.class).block(REQUEST_TIMEOUT);
            if (comparison == null) return List.of();
            List<String> paths = new ArrayList<>();
            StringBuilder diff = new StringBuilder();
            for (JsonNode entry : comparison.path("diffs")) {
                String path = entry.path("new_path").asText(entry.path("old_path").asText());
                paths.add(path);
                appendBounded(diff, "--- " + path + "\n" + entry.path("diff").asText() + "\n", maxDiffCharacters);
                if (diff.length() >= maxDiffCharacters) break;
            }
            JsonNode lastCommit = comparison.path("commits").isArray() && !comparison.path("commits").isEmpty()
                    ? comparison.path("commits").get(comparison.path("commits").size() - 1) : null;
            return List.of(new CommitChange(safeTo,
                    lastCommit == null ? "Revision comparison" : lastCommit.path("title").asText("Revision comparison"),
                    lastCommit == null ? "" : lastCommit.path("author_name").asText(""),
                    lastCommit == null ? null : parseInstant(lastCommit.path("committed_date").asText()),
                    paths, diff.toString()));
        } catch (RuntimeException ex) {
            throw failure("compare", ex);
        }
    }

    @Override
    public Optional<String> getFileContent(String service, String revision, String path, int maxCharacters) {
        String safeRevision = ConnectorInputValidator.revision(revision);
        String safePath = ConnectorInputValidator.repositoryPath(path);
        ConnectorInputValidator.boundedLimit(maxCharacters, 200_000);
        Optional<RepositoryRef> repository = resolveRepository(service);
        if (repository.isEmpty()) return Optional.empty();
        try {
            String content = client.get().uri(uri -> uri.pathSegment("api", "v4", "projects",
                                    repository.get().projectId(), "repository", "files", safePath, "raw")
                            .queryParam("ref", safeRevision).build())
                    .retrieve().bodyToMono(String.class).block(REQUEST_TIMEOUT);
            if (content == null) return Optional.empty();
            return Optional.of(content.substring(0, Math.min(content.length(), maxCharacters)));
        } catch (RuntimeException ex) {
            if (isNotFound(ex)) return Optional.empty();
            throw failure("file-content", ex);
        }
    }

    @Override
    public List<String> getRepositoryTree(String service, String revision, String path, int limit) {
        String safeRevision = ConnectorInputValidator.revision(revision);
        String safePath = path == null || path.isBlank() ? "" : ConnectorInputValidator.repositoryPath(path);
        int safeLimit = ConnectorInputValidator.boundedLimit(limit, 1_000);
        Optional<RepositoryRef> repository = resolveRepository(service);
        if (repository.isEmpty()) return List.of();
        try {
            JsonNode entries = client.get().uri(uri -> uri.pathSegment("api", "v4", "projects",
                                    repository.get().projectId(), "repository", "tree")
                            .queryParam("ref", safeRevision).queryParam("path", safePath)
                            .queryParam("recursive", true).queryParam("per_page", safeLimit).build())
                    .retrieve().bodyToMono(JsonNode.class).block(REQUEST_TIMEOUT);
            if (entries == null || !entries.isArray()) return List.of();
            List<String> paths = new ArrayList<>();
            entries.forEach(entry -> paths.add(entry.path("path").asText()));
            return paths.stream().filter(value -> !value.isBlank()).limit(safeLimit).toList();
        } catch (RuntimeException ex) {
            if (isNotFound(ex)) return List.of();
            throw failure("repository-tree", ex);
        }
    }

    private GitLabConnectorException failure(String operation, RuntimeException exception) {
        GitLabFailureKind kind = classify(exception);
        LOGGER.warn("GitLab operation={} outcome={}", operation, kind);
        return new GitLabConnectorException(kind, exception);
    }

    private static boolean isNotFound(RuntimeException exception) {
        return classify(exception) == GitLabFailureKind.NOT_FOUND;
    }

    private static GitLabFailureKind classify(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof WebClientResponseException response) {
                return switch (response.getStatusCode().value()) {
                    case 401 -> GitLabFailureKind.UNAUTHORIZED;
                    case 403 -> GitLabFailureKind.FORBIDDEN;
                    case 404 -> GitLabFailureKind.NOT_FOUND;
                    default -> GitLabFailureKind.REMOTE_FAILURE;
                };
            }
            if (current instanceof SSLException) return GitLabFailureKind.TLS_FAILURE;
            if (current instanceof TimeoutException
                    || current.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("timeout")) {
                return GitLabFailureKind.TIMEOUT;
            }
            current = current.getCause();
        }
        return GitLabFailureKind.REMOTE_FAILURE;
    }

    private RepositoryRef toRepository(JsonNode project) {
        String repositoryUrl = safeRepositoryUrl(project.path("web_url").asText()).orElse("");
        return new RepositoryRef(project.path("id").asText(), repositoryUrl,
                project.path("default_branch").asText("main"));
    }

    private Optional<String> projectIdFromUrl(String repositoryUrl) {
        try {
            URI uri = URI.create(repositoryUrl);
            if (!sameOrigin(baseUri, uri) || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) return Optional.empty();
            String path = uri.getPath();
            if (path == null) return Optional.empty();
            path = path.replaceFirst("^/+", "").replaceFirst("\\.git$", "");
            if (path.isBlank() || path.contains("..")) return Optional.empty();
            for (String segment : path.split("/")) ConnectorInputValidator.repositoryPath(segment);
            return Optional.of(path);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Optional<String> safeRepositoryUrl(String value) {
        try {
            URI candidate = URI.create(value);
            if (!sameOrigin(baseUri, candidate) || candidate.getUserInfo() != null
                    || candidate.getQuery() != null || candidate.getFragment() != null) {
                return Optional.empty();
            }
            return Optional.of(candidate.toString());
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static boolean sameOrigin(URI expected, URI actual) {
        if (expected == null || actual == null || expected.getScheme() == null || actual.getScheme() == null
                || expected.getHost() == null || actual.getHost() == null) return false;
        return expected.getScheme().equalsIgnoreCase(actual.getScheme())
                && expected.getHost().equalsIgnoreCase(actual.getHost())
                && effectivePort(expected) == effectivePort(actual);
    }

    private static int effectivePort(URI value) {
        if (value.getPort() >= 0) return value.getPort();
        return "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
    }

    private String defaultBranch(ServiceDefinition definition) {
        List<String> eksBranches = definition.attributeValues("eksBranches");
        if (!eksBranches.isEmpty()) return eksBranches.getFirst();
        List<String> tasBranches = definition.attributeValues("tasBranches");
        return tasBranches.isEmpty() ? "main" : tasBranches.getFirst();
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static void appendBounded(StringBuilder target, String value, int maximum) {
        int remaining = maximum - target.length();
        if (remaining > 0) target.append(value, 0, Math.min(value.length(), remaining));
    }
}
