package com.jmopsagent.jenkins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jmopsagent.connector.DeploymentInfo;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.registry.YamlServiceRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.reactive.function.client.WebClient;

class JenkinsMultiControllerContractTest {
    private final List<RecordingServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(RecordingServer::close);
    }

    @Test
    void routesEnvironmentSpecificJobsToSeparatelyAuthenticatedControllers() {
        RecordingServer first = server("controller-one");
        RecordingServer second = server("controller-two");
        JenkinsProperties.Controller firstConfiguration = configuration(first.baseUrl(), "reader-one", "token-one");
        JenkinsProperties.Controller secondConfiguration = configuration(second.baseUrl(), "reader-two", "token-two");
        JenkinsControllerRegistry controllers = new JenkinsControllerRegistry(WebClient.builder(), Map.of(
                "controller-one", firstConfiguration,
                "controller-two", secondConfiguration), false);
        LiveJenkinsConnector connector = new LiveJenkinsConnector(controllers, registry("""
                services:
                  - service: sample-api
                    jenkins:
                      controller: controller-one
                      job: Default/Deploy
                      controller.TEST: controller-two
                      job.TEST: Parent Folder/Nested Job/Deploy Test
                """), "{service}-{environment}-deploy");

        List<DeploymentInfo> testBuilds = connector.getLastBuilds("sample-api", Environment.TEST, 2);
        List<DeploymentInfo> devBuilds = connector.getLastBuilds("sample-api", Environment.DEV, 2);

        assertThat(testBuilds).singleElement().satisfies(build -> {
            assertThat(build.jobName()).isEqualTo("Parent Folder/Nested Job/Deploy Test");
            assertThat(build.metadata()).containsEntry("controller", "controller-two");
        });
        assertThat(devBuilds).singleElement().satisfies(build -> {
            assertThat(build.jobName()).isEqualTo("Default/Deploy");
            assertThat(build.metadata()).containsEntry("controller", "controller-one");
        });

        assertThat(first.requests()).allSatisfy(request ->
                assertThat(request.authorization()).isEqualTo(basic("reader-one", "token-one")));
        assertThat(second.requests()).allSatisfy(request ->
                assertThat(request.authorization()).isEqualTo(basic("reader-two", "token-two")));
        assertThat(first.requests()).noneMatch(request ->
                request.authorization().equals(basic("reader-two", "token-two")));
        assertThat(second.requests()).noneMatch(request ->
                request.authorization().equals(basic("reader-one", "token-one")));

        Request testBuildRequest = second.requests().getFirst();
        assertThat(testBuildRequest.rawPath()).isEqualTo(
                "/controller-two/job/Parent%20Folder/job/Nested%20Job/job/Deploy%20Test/api/json");
        assertThat(URLDecoder.decode(testBuildRequest.rawQuery(), StandardCharsets.UTF_8))
                .isEqualTo("tree=builds[number,result,timestamp,url,building,actions[lastBuiltRevision[SHA1]],changeSet[items[msg,commitId]]]{0,2}");
        assertThat(testBuildRequest.rawQuery()).doesNotContain("%257B", "%257D");
    }

    @Test
    void historicalMetadataUsesOneBoundedRequestAndTheBuiltRevision() {
        RecordingServer only = server("history-controller");
        JenkinsControllerRegistry controllers = new JenkinsControllerRegistry(WebClient.builder(),
                Map.of("history-controller", configuration(only.baseUrl(), "reader", "token")), false);
        LiveJenkinsConnector connector = new LiveJenkinsConnector(controllers, registry("""
                services:
                  - service: sample-api
                    jenkins.job: Team/Sample/Deploy
                """), "{service}-{environment}-deploy");
        assertThat(connector.getDeploymentHistory("sample-api", Environment.TEST)).singleElement().satisfies(build -> {
            assertThat(build.commitSha()).isEqualTo("f00ba4");
            assertThat(build.metadata()).containsEntry("completedAt", "2023-11-14T22:14:20Z");
        });
        assertThat(only.requests()).singleElement().satisfies(request ->
                assertThat(URLDecoder.decode(request.rawQuery(), StandardCharsets.UTF_8))
                        .contains("timestamp,duration", "{0,100}").doesNotContain("changeSet"));
    }

    @Test
    void seeksMonthsOldRetainedBuildsWithoutScanningThousandsOfNewerBuildDetails() {
        RecordingServer only = server("history-seek");
        java.time.Instant newest = java.time.Instant.parse("2026-09-01T00:00:00Z");
        only.historyResponse = query -> indexedHistory(query, newest, 5000);
        LiveJenkinsConnector connector = historyConnector(only);
        var anchor = newest.minusSeconds(4000L * 3600).plusSeconds(30);
        List<DeploymentInfo> builds = connector.getDeploymentHistory("sample-api", Environment.TEST, anchor);
        assertThat(builds).hasSize(100);
        assertThat(builds).anySatisfy(build -> {
            assertThat(build.buildNumber()).isEqualTo(9999 - 4001);
            assertThat(build.metadata()).containsEntry("completedAt", newest.minusSeconds(4001L * 3600).plusSeconds(60).toString());
        });
        assertThat(builds).allSatisfy(build -> assertThat(build.metadata().get("coverage")).contains("sought near", "incomplete", "not deployment attestation").doesNotContain("most recent"));
        assertThat(only.requests()).hasSizeLessThan(30).allSatisfy(request -> {
            String query = URLDecoder.decode(request.rawQuery(), StandardCharsets.UTF_8);
            assertThat(query).startsWith("tree=builds[").doesNotContain("changeSet");
            assertThat(request.rawPath()).endsWith("/api/json");
        });
        assertThat(only.requests().stream().filter(request -> URLDecoder.decode(request.rawQuery(), StandardCharsets.UTF_8).contains("duration"))).hasSize(1);
    }

    @Test
    void historicalSeekReportsExhaustedRetentionAndRejectsMissingTimestamps() {
        RecordingServer only = server("history-exhausted");
        java.time.Instant newest = java.time.Instant.parse("2026-09-01T00:00:00Z");
        only.historyResponse = query -> indexedHistory(query, newest, 20);
        LiveJenkinsConnector connector = historyConnector(only);
        var anchor = java.time.Instant.parse("2024-01-01T00:00:00Z");
        assertThat(connector.getDeploymentHistory("sample-api", Environment.TEST, anchor))
                .allSatisfy(build -> assertThat(build.timestamp()).isAfter(anchor));
        assertThat(only.requests()).hasSizeLessThan(12);
        only.historyResponse = query -> "{\"builds\":[{\"timestamp\":null}]}";
        assertThatThrownBy(() -> connector.getDeploymentHistory("sample-api", Environment.TEST, anchor))
                .isInstanceOf(JenkinsConnectorException.class).hasMessageContaining("timestamp was unavailable");
    }

    private LiveJenkinsConnector historyConnector(RecordingServer only) {
        return new LiveJenkinsConnector(new JenkinsControllerRegistry(WebClient.builder(),
                Map.of("history", configuration(only.baseUrl(), "reader", "token")), false), registry("""
                services:
                  - service: sample-api
                    jenkins.job: Team/Sample/Deploy
                """), "{service}-{environment}-deploy");
    }

    private static String indexedHistory(String query, java.time.Instant newest, int retained) {
        var range = java.util.regex.Pattern.compile("\\{(\\d+),(\\d+)\\}").matcher(query);
        if (!range.find()) throw new IllegalArgumentException("Expected bounded range");
        int from = Integer.parseInt(range.group(1)), to = Math.min(Integer.parseInt(range.group(2)), retained);
        List<String> builds = new ArrayList<>();
        for (int index = from; index < to; index++) {
            builds.add("{\"number\":" + (9999 - index) + ",\"timestamp\":" + newest.minusSeconds(index * 3600L).toEpochMilli()
                    + ",\"duration\":60000,\"result\":\"SUCCESS\",\"building\":false,\"actions\":[{\"lastBuiltRevision\":{\"SHA1\":\"abc123\"}}]}");
        }
        return "{\"builds\":[" + String.join(",", builds) + "]}";
    }

    @Test
    void defaultsOnlyWhenExactlyOneControllerIsConfigured() {
        RecordingServer only = server("only-controller");
        JenkinsControllerRegistry controllers = new JenkinsControllerRegistry(WebClient.builder(),
                Map.of("only-controller", configuration(only.baseUrl(), "reader", "token")), false);
        LiveJenkinsConnector connector = new LiveJenkinsConnector(controllers, registry("""
                services:
                  - service: sample-api
                    jenkins:
                      job: Team/Sample/Deploy
                """), "{service}-{environment}-deploy");

        DeploymentInfo deployment = connector.getLatestDeployment("sample-api", Environment.TEST).orElseThrow();

        assertThat(deployment.metadata()).containsEntry("controller", "only-controller");
        assertThat(only.requests()).isNotEmpty();
    }

    @Test
    void ambiguousAndUnknownControllerMappingsFailClosed() {
        RecordingServer first = server("controller-one");
        RecordingServer second = server("controller-two");
        JenkinsControllerRegistry controllers = new JenkinsControllerRegistry(WebClient.builder(), Map.of(
                "controller-one", configuration(first.baseUrl(), "reader-one", "token-one"),
                "controller-two", configuration(second.baseUrl(), "reader-two", "token-two")), false);
        LiveJenkinsConnector connector = new LiveJenkinsConnector(controllers, registry("""
                services:
                  - service: ambiguous-api
                    jenkins:
                      job: Team/Ambiguous/Deploy
                  - service: unknown-api
                    jenkins:
                      controller: missing-controller
                      job: Team/Unknown/Deploy
                """), "{service}-{environment}-deploy");

        assertThatThrownBy(() -> connector.getLatestDeployment("ambiguous-api", Environment.TEST))
                .isInstanceOfSatisfying(JenkinsConnectorException.class, failure ->
                        assertThat(failure.kind()).isEqualTo(JenkinsFailureKind.AMBIGUOUS_CONTROLLER));
        assertThatThrownBy(() -> connector.getLatestDeployment("unknown-api", Environment.TEST))
                .isInstanceOfSatisfying(JenkinsConnectorException.class, failure ->
                        assertThat(failure.kind()).isEqualTo(JenkinsFailureKind.UNKNOWN_CONTROLLER));
        assertThat(first.requests()).isEmpty();
        assertThat(second.requests()).isEmpty();
    }

    @Test
    void authenticationFailureIsClassifiedWithoutExposingEndpointOrCredentials() {
        RecordingServer server = server("secured-controller", 401);
        JenkinsControllerRegistry controllers = new JenkinsControllerRegistry(WebClient.builder(), Map.of(
                "secured-controller", configuration(server.baseUrl(), "private-reader", "private-token")), false);
        LiveJenkinsConnector connector = new LiveJenkinsConnector(controllers, registry("""
                services:
                  - service: sample-api
                    jenkins:
                      controller: secured-controller
                      job: Team/Sample/Deploy
                """), "{service}-{environment}-deploy");

        assertThatThrownBy(() -> connector.getLatestDeployment("sample-api", Environment.TEST))
                .isInstanceOfSatisfying(JenkinsConnectorException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(JenkinsFailureKind.UNAUTHORIZED);
                    assertThat(failure.getMessage()).doesNotContain(server.baseUrl(), "private-reader", "private-token");
                    assertThat(failure.getCause()).isNull();
                });
    }

    @Test
    void doesNotForwardAuthenticationAcrossRedirectOrigins() {
        RecordingServer destination = server("destination-controller");
        RecordingServer redirector = server("source-controller", 302, destination.baseUrl() + "/capture");
        JenkinsControllerRegistry controllers = new JenkinsControllerRegistry(WebClient.builder(), Map.of(
                "source-controller", configuration(redirector.baseUrl(), "private-reader", "private-token")), false);
        LiveJenkinsConnector connector = new LiveJenkinsConnector(controllers, registry("""
                services:
                  - service: sample-api
                    jenkins:
                      controller: source-controller
                      job: Team/Sample/Deploy
                """), "{service}-{environment}-deploy");

        assertThatThrownBy(() -> connector.getLatestDeployment("sample-api", Environment.TEST))
                .isInstanceOfSatisfying(JenkinsConnectorException.class, failure ->
                        assertThat(failure.kind()).isEqualTo(JenkinsFailureKind.REMOTE_FAILURE));
        assertThat(destination.requests()).isEmpty();
        assertThat(redirector.requests()).singleElement().satisfies(request ->
                assertThat(request.authorization()).isEqualTo(basic("private-reader", "private-token")));
    }

    private RecordingServer server(String path) {
        return server(path, 200);
    }

    private RecordingServer server(String path, int status) {
        return server(path, status, null);
    }

    private RecordingServer server(String path, int status, String redirectLocation) {
        RecordingServer server = new RecordingServer(path, status, redirectLocation);
        servers.add(server);
        return server;
    }

    private static JenkinsProperties.Controller configuration(String baseUrl, String username, String token) {
        JenkinsProperties.Controller configuration = new JenkinsProperties.Controller();
        configuration.setBaseUrl(baseUrl);
        configuration.setUsername(username);
        configuration.setToken(token);
        return configuration;
    }

    private static YamlServiceRegistry registry(String yaml) {
        YamlServiceRegistry registry = new YamlServiceRegistry(
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        registry.load();
        return registry;
    }

    private static String basic(String username, String token) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + token).getBytes(StandardCharsets.UTF_8));
    }

    private record Request(String rawPath, String rawQuery, String authorization) {}

    private static final class RecordingServer implements AutoCloseable {
        private final String controllerPath;
        private final int status;
        private final String redirectLocation;
        private final HttpServer server;
        private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
        private java.util.function.Function<String, String> historyResponse;

        private RecordingServer(String controllerPath, int status, String redirectLocation) {
            this.controllerPath = controllerPath;
            this.status = status;
            this.redirectLocation = redirectLocation;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException ex) {
                throw new IllegalStateException("Could not create local test server", ex);
            }
            server.createContext("/", this::handle);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + controllerPath;
        }

        List<Request> requests() {
            return List.copyOf(requests);
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.add(new Request(exchange.getRequestURI().getRawPath(), exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            if (redirectLocation != null) exchange.getResponseHeaders().set("Location", redirectLocation);
            byte[] response;
            if (status != 200) {
                response = "denied".getBytes(StandardCharsets.UTF_8);
            } else if (historyResponse != null) {
                response = historyResponse.apply(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            } else if (exchange.getRequestURI().getPath().contains("/wfapi/describe")) {
                response = "{\"stages\":[]}".getBytes(StandardCharsets.UTF_8);
            } else {
                String buildUrl = baseUrl() + "/job/sample/17/";
                response = ("{\"builds\":[{\"number\":17,\"result\":\"SUCCESS\","
                        + "\"timestamp\":1700000000000,\"duration\":60000,\"url\":\"" + buildUrl + "\","
                        + "\"building\":false,\"actions\":[{\"lastBuiltRevision\":{\"SHA1\":\"f00ba4\"}}],"
                        + "\"changeSet\":{\"items\":[{\"commitId\":\"aaa111\"}]}}]}").getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
