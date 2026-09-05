package com.jmopsagent.jenkins;

import tools.jackson.databind.JsonNode;
import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.DeploymentInfo;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.registry.ServiceRegistry;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
@Profile("local-live")
public class LiveJenkinsConnector implements JenkinsConnector {
    private static final Logger log = LoggerFactory.getLogger(LiveJenkinsConnector.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final String BUILD_TREE = "builds[number,result,timestamp,url,building,actions[lastBuiltRevision[SHA1]],changeSet[items[msg,commitId]]]";

    private final JenkinsTargetResolver targetResolver;

    @Autowired
    public LiveJenkinsConnector(WebClient.Builder builder, ServiceRegistry serviceRegistry,
            JenkinsProperties properties) {
        this(JenkinsControllerRegistry.startupTolerant(builder, properties),
                serviceRegistry, properties.effectiveJobPattern());
    }

    LiveJenkinsConnector(JenkinsControllerRegistry controllers, ServiceRegistry serviceRegistry, String jobPattern) {
        this.targetResolver = new JenkinsTargetResolver(controllers, serviceRegistry, jobPattern);
    }

    @Override
    public Optional<DeploymentInfo> getLatestDeployment(String service, Environment environment) {
        List<DeploymentInfo> builds = getLastBuilds(service, environment, 1);
        return builds.isEmpty() ? Optional.empty() : Optional.of(builds.getFirst());
    }

    @Override
    public List<DeploymentInfo> getLastBuilds(String service, Environment environment, int limit) {
        String safeService = ConnectorInputValidator.service(service);
        if (environment == null) throw new IllegalArgumentException("Environment is required");
        int safeLimit = ConnectorInputValidator.boundedLimit(limit, 25);
        JenkinsResolvedTarget target = targetResolver.resolve(safeService, environment);
        JsonNode response = execute("list builds", () -> target.controller().client().get()
                    .uri(uri -> uri.pathSegment(jobSegments(target.job(), "api", "json"))
                            .queryParam("tree", "{tree}")
                            .build(Map.of("tree", BUILD_TREE + "{0," + safeLimit + "}")))
                    .retrieve()
                    .onStatus(HttpStatusCode::is3xxRedirection, ignored -> unexpectedRedirect())
                    .bodyToMono(JsonNode.class).block(REQUEST_TIMEOUT));
        if (response == null) return List.of();
        List<DeploymentInfo> result = new ArrayList<>();
        int index = 0;
        for (JsonNode build : response.path("builds")) {
            if (index++ >= safeLimit) break;
            long buildNumber = build.path("number").asLong();
            String buildResult = build.path("building").asBoolean(false) ? "BUILDING"
                    : build.path("result").asText("UNKNOWN");
            String sha = findCommitSha(build);
            List<String> changes = changes(build);
            List<String> errors = "SUCCESS".equalsIgnoreCase(buildResult) ? List.of()
                    : consoleErrors(target, buildNumber);
            List<String> failedStages = failedStages(target, buildNumber);
            result.add(new DeploymentInfo(safeService, environment, target.job(), buildNumber, buildResult,
                    Instant.ofEpochMilli(build.path("timestamp").asLong()), sha,
                    target.controller().safeResultUri(build.path("url").asText()), changes, failedStages, errors,
                    Map.of("building", Boolean.toString(build.path("building").asBoolean(false)),
                            "controller", target.controller().id())));
        }
        return List.copyOf(result);
    }

    private List<String> consoleErrors(JenkinsResolvedTarget target, long buildNumber) {
        try {
            String console = execute("read console", () -> target.controller().client().get()
                    .uri(uri -> uri.pathSegment(jobSegments(target.job(), Long.toString(buildNumber),
                            "consoleText")).build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is3xxRedirection, ignored -> unexpectedRedirect())
                    .bodyToMono(String.class).block(REQUEST_TIMEOUT));
            if (console == null) return List.of();
            return console.lines().filter(LiveJenkinsConnector::looksLikeError)
                    .map(line -> line.length() <= 1_000 ? line : line.substring(0, 1_000))
                    .distinct().limit(30).toList();
        } catch (JenkinsConnectorException ex) {
            if (ex.kind() == JenkinsFailureKind.NOT_FOUND) return List.of();
            throw ex;
        }
    }

    private List<String> failedStages(JenkinsResolvedTarget target, long buildNumber) {
        try {
            JsonNode workflow = execute("read pipeline stages", () -> target.controller().client().get()
                    .uri(uri -> uri.pathSegment(jobSegments(target.job(), Long.toString(buildNumber),
                            "wfapi", "describe")).build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is3xxRedirection, ignored -> unexpectedRedirect())
                    .bodyToMono(JsonNode.class).block(REQUEST_TIMEOUT));
            if (workflow == null) return List.of();
            List<String> failed = new ArrayList<>();
            for (JsonNode stage : workflow.path("stages")) {
                String status = stage.path("status").asText("UNKNOWN");
                if (!"SUCCESS".equalsIgnoreCase(status) && !"NOT_EXECUTED".equalsIgnoreCase(status)) {
                    failed.add(stage.path("name").asText("unnamed") + " (" + status + ")");
                }
            }
            return List.copyOf(failed);
        } catch (JenkinsConnectorException ex) {
            if (ex.kind() == JenkinsFailureKind.NOT_FOUND) return List.of();
            throw ex;
        }
    }

    private static String[] jobSegments(String job, String... suffix) {
        List<String> segments = new ArrayList<>();
        for (String part : JenkinsTargetResolver.validateJobPath(job).split("/")) {
            segments.add("job");
            segments.add(part);
        }
        segments.addAll(List.of(suffix));
        return segments.toArray(String[]::new);
    }

    private static String findCommitSha(JsonNode build) {
        for (JsonNode item : build.path("changeSet").path("items")) {
            String sha = item.path("commitId").asText();
            if (!sha.isBlank()) return sha;
        }
        for (JsonNode action : build.path("actions")) {
            String sha = action.path("lastBuiltRevision").path("SHA1").asText();
            if (!sha.isBlank()) return sha;
        }
        return "";
    }

    private static List<String> changes(JsonNode build) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : build.path("changeSet").path("items")) {
            String message = item.path("msg").asText();
            if (!message.isBlank()) result.add(message.length() <= 1_000 ? message : message.substring(0, 1_000));
        }
        return result.stream().distinct().limit(50).toList();
    }

    private static boolean looksLikeError(String line) {
        String lower = line.toLowerCase();
        return lower.contains("error") || lower.contains("exception") || lower.contains("failed")
                || lower.contains("failure");
    }

    private static <T> T execute(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (JenkinsConnectorException ex) {
            throw ex;
        } catch (WebClientResponseException ex) {
            JenkinsConnectorException failure = responseFailure(ex.getStatusCode(), ex);
            log.warn("Jenkins operation '{}' failed ({})", operation, failure.kind());
            throw failure;
        } catch (RuntimeException ex) {
            JenkinsConnectorException failure = requestFailure(ex);
            log.warn("Jenkins operation '{}' failed ({})", operation, failure.kind());
            throw failure;
        }
    }

    private static JenkinsConnectorException responseFailure(HttpStatusCode status, RuntimeException cause) {
        JenkinsFailureKind kind = switch (status.value()) {
            case 401 -> JenkinsFailureKind.UNAUTHORIZED;
            case 403 -> JenkinsFailureKind.FORBIDDEN;
            case 404 -> JenkinsFailureKind.NOT_FOUND;
            default -> JenkinsFailureKind.REMOTE_FAILURE;
        };
        return new JenkinsConnectorException(kind, "The Jenkins server rejected the read-only request", cause);
    }

    private static JenkinsConnectorException requestFailure(RuntimeException cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof JenkinsConnectorException failure) return failure;
            current = current.getCause();
        }
        if (hasCause(cause, TimeoutException.class) || hasCause(cause, HttpTimeoutException.class)) {
            return new JenkinsConnectorException(JenkinsFailureKind.TIMEOUT, "The Jenkins request timed out", cause);
        }
        if (hasCause(cause, javax.net.ssl.SSLException.class)) {
            return new JenkinsConnectorException(JenkinsFailureKind.TLS_FAILURE,
                    "The Jenkins TLS connection could not be established", cause);
        }
        if (hasCause(cause, DecodingException.class)) {
            return new JenkinsConnectorException(JenkinsFailureKind.RESPONSE_INVALID,
                    "The Jenkins response could not be parsed", cause);
        }
        JenkinsFailureKind kind = cause instanceof WebClientRequestException
                ? JenkinsFailureKind.REMOTE_FAILURE
                : JenkinsFailureKind.RESPONSE_INVALID;
        return new JenkinsConnectorException(kind, "The Jenkins read-only request failed", cause);
    }

    private static Mono<? extends Throwable> unexpectedRedirect() {
        return Mono.error(new JenkinsConnectorException(JenkinsFailureKind.REMOTE_FAILURE,
                "The Jenkins server returned an unexpected redirect"));
    }

    private static boolean hasCause(Throwable value, Class<? extends Throwable> type) {
        Throwable current = value;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }
}
