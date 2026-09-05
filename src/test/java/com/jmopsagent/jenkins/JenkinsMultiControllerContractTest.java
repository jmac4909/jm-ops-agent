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
            } else if (exchange.getRequestURI().getPath().contains("/wfapi/describe")) {
                response = "{\"stages\":[]}".getBytes(StandardCharsets.UTF_8);
            } else {
                String buildUrl = baseUrl() + "/job/sample/17/";
                response = ("{\"builds\":[{\"number\":17,\"result\":\"SUCCESS\","
                        + "\"timestamp\":1700000000000,\"url\":\"" + buildUrl + "\","
                        + "\"building\":false,\"actions\":[{\"lastBuiltRevision\":{\"SHA1\":\"f00ba4\"}}],"
                        + "\"changeSet\":{\"items\":[]}}]}").getBytes(StandardCharsets.UTF_8);
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
