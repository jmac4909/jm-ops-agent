package com.jmopsagent.config.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmopsagent.connector.process.ProcessRequest;
import com.jmopsagent.connector.process.ProcessResult;
import com.jmopsagent.connector.process.ProcessRunner;
import com.jmopsagent.jenkins.JenkinsProperties;
import com.jmopsagent.splunk.SplunkAuthMode;
import com.jmopsagent.splunk.SplunkProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StartupDiagnosticsServiceTest {

    @Test
    void retainsExecutableChecksAndProbesEveryConfiguredEndpointWithoutCredentials() {
        String firstJenkinsEndpoint = "https://jenkins-a.example.invalid/ci";
        String secondJenkinsEndpoint = "https://jenkins-b.example.invalid/ci";
        String gitLabEndpoint = "https://gitlab.example.invalid";
        String splunkEndpoint = "https://splunk.example.invalid:8089";
        JenkinsProperties jenkins = new JenkinsProperties();
        Map<String, JenkinsProperties.Controller> controllers = new LinkedHashMap<>();
        controllers.put("controller-a", controller(firstJenkinsEndpoint, "jenkins-user-a", "jenkins-token-a"));
        controllers.put("controller-b", controller(secondJenkinsEndpoint, "jenkins-user-b", "jenkins-token-b"));
        jenkins.setControllers(controllers);
        SplunkProperties splunk = new SplunkProperties();
        splunk.setBaseUrl(splunkEndpoint);
        splunk.setAuthMode(SplunkAuthMode.BEARER_TOKEN);
        splunk.setToken("splunk-token-value");

        List<ProcessRequest> commands = new ArrayList<>();
        List<String> probedEndpoints = new ArrayList<>();
        StartupDiagnosticsService service = service(successfulRunner(commands), endpoint -> {
            probedEndpoints.add(endpoint);
            return EndpointProbe.Outcome.REACHABLE;
        }, jenkins, splunk, gitLabEndpoint, "gitlab-token-value");

        DiagnosticsSnapshot snapshot = service.refresh();

        assertThat(commands).extracting(ProcessRequest::executable)
                .containsExactly("claude", "kubectl", "cf", "git");
        assertThat(commands).extracting(ProcessRequest::arguments)
                .containsExactly(
                        List.of("--version"),
                        List.of("version", "--client=true", "--output=json"),
                        List.of("version"),
                        List.of("--version"));
        assertThat(probedEndpoints).containsExactly(
                firstJenkinsEndpoint, secondJenkinsEndpoint, gitLabEndpoint, splunkEndpoint);
        assertThat(item(snapshot, "Jenkins").status()).isEqualTo(DiagnosticStatus.CONFIGURED);
        assertThat(item(snapshot, "Jenkins").detail()).isEqualTo(
                "Required settings are present for 2 controllers");
        assertThat(item(snapshot, "GitLab").status()).isEqualTo(DiagnosticStatus.CONFIGURED);
        assertThat(item(snapshot, "Splunk").status()).isEqualTo(DiagnosticStatus.CONFIGURED);
        assertThat(snapshot.items()).extracting(DiagnosticItem::component)
                .contains("Jenkins connectivity 1", "Jenkins connectivity 2",
                        "GitLab connectivity", "Splunk connectivity");
        assertThat(item(snapshot, "Jenkins connectivity 1").status()).isEqualTo(DiagnosticStatus.AVAILABLE);

        String visibleDiagnostics = snapshot.items().toString();
        assertThat(visibleDiagnostics)
                .doesNotContain("controller-a", "controller-b")
                .doesNotContain("jenkins-a.example.invalid", "jenkins-b.example.invalid",
                        "gitlab.example.invalid", "splunk.example.invalid")
                .doesNotContain("jenkins-user-a", "jenkins-token-a", "gitlab-token-value", "splunk-token-value");
    }

    @Test
    void recognizesLegacyJenkinsAndSplunkSessionCsrfConfiguration() {
        JenkinsProperties jenkins = new JenkinsProperties();
        jenkins.setBaseUrl("https://jenkins.example.invalid");
        jenkins.setUsername("legacy-user");
        jenkins.setToken("legacy-token");
        SplunkProperties splunk = new SplunkProperties();
        splunk.setBaseUrl("https://splunk.example.invalid");
        splunk.setAuthMode(SplunkAuthMode.SESSION_CSRF);
        splunk.setSessionCookie("splunkd_1=cookie-secret; session_id_1=session-secret; "
                + "splunkweb_csrf_token_1=csrf-cookie-secret");
        splunk.setFormKey("form-key-secret");
        List<String> probedEndpoints = new ArrayList<>();

        DiagnosticsSnapshot snapshot = service(successfulRunner(new ArrayList<>()), endpoint -> {
            probedEndpoints.add(endpoint);
            return EndpointProbe.Outcome.REACHABLE;
        }, jenkins, splunk, "", "").refresh();

        assertThat(item(snapshot, "Jenkins").status()).isEqualTo(DiagnosticStatus.CONFIGURED);
        assertThat(item(snapshot, "Splunk").status()).isEqualTo(DiagnosticStatus.CONFIGURED);
        assertThat(item(snapshot, "Splunk").detail()).contains("session/CSRF");
        assertThat(probedEndpoints).containsExactly(
                "https://jenkins.example.invalid", "https://splunk.example.invalid");
        assertThat(snapshot.items().toString())
                .doesNotContain("legacy-user", "legacy-token", "cookie-secret", "session-secret",
                        "csrf-cookie-secret", "form-key-secret",
                        "jenkins.example.invalid", "splunk.example.invalid");
    }

    @Test
    void recognizesSplunkSessionKeyTokenConfiguration() {
        SplunkProperties splunk = new SplunkProperties();
        splunk.setBaseUrl("https://splunk.example.invalid");
        splunk.setAuthMode(SplunkAuthMode.SESSION_KEY);
        splunk.setToken("session-key-secret");
        List<String> probedEndpoints = new ArrayList<>();

        DiagnosticsSnapshot snapshot = service(successfulRunner(new ArrayList<>()), endpoint -> {
            probedEndpoints.add(endpoint);
            return EndpointProbe.Outcome.REACHABLE;
        }, new JenkinsProperties(), splunk, "", "").refresh();

        assertThat(item(snapshot, "Splunk").status()).isEqualTo(DiagnosticStatus.CONFIGURED);
        assertThat(probedEndpoints).containsExactly("https://splunk.example.invalid");
        assertThat(snapshot.items().toString())
                .doesNotContain("splunk.example.invalid", "session-key-secret");
    }

    @Test
    void requiresAllSplunkSessionCookieFamilies() {
        SplunkProperties splunk = new SplunkProperties();
        splunk.setBaseUrl("https://splunk.example.invalid");
        splunk.setAuthMode(SplunkAuthMode.SESSION_CSRF);
        splunk.setSessionCookie("splunkd_1=cookie-secret; session_id_1=session-secret");
        splunk.setFormKey("form-key-secret");
        List<String> probedEndpoints = new ArrayList<>();

        DiagnosticsSnapshot snapshot = service(successfulRunner(new ArrayList<>()), endpoint -> {
            probedEndpoints.add(endpoint);
            return EndpointProbe.Outcome.REACHABLE;
        }, new JenkinsProperties(), splunk, "", "").refresh();

        assertThat(item(snapshot, "Splunk").status()).isEqualTo(DiagnosticStatus.UNCONFIGURED);
        assertThat(probedEndpoints).isEmpty();
        assertThat(snapshot.items().toString())
                .doesNotContain("splunk.example.invalid", "cookie-secret", "session-secret", "form-key-secret");
    }

    @Test
    void conflictingJenkinsStylesAndSplunkCredentialsFailClosed() {
        JenkinsProperties jenkins = new JenkinsProperties();
        jenkins.setBaseUrl("https://legacy.example.invalid");
        jenkins.setUsername("legacy-user");
        jenkins.setToken("legacy-token");
        jenkins.setControllers(Map.of(
                "controller-a", controller(
                        "https://controller.example.invalid", "controller-user", "controller-token")));
        SplunkProperties splunk = new SplunkProperties();
        splunk.setBaseUrl("https://splunk.example.invalid");
        splunk.setToken("token-secret");
        splunk.setSessionCookie("cookie-secret");
        splunk.setFormKey("form-secret");
        List<String> probedEndpoints = new ArrayList<>();

        DiagnosticsSnapshot snapshot = service(successfulRunner(new ArrayList<>()), endpoint -> {
            probedEndpoints.add(endpoint);
            return EndpointProbe.Outcome.REACHABLE;
        }, jenkins, splunk, "", "").refresh();

        assertThat(item(snapshot, "Jenkins").status()).isEqualTo(DiagnosticStatus.UNCONFIGURED);
        assertThat(item(snapshot, "Splunk").status()).isEqualTo(DiagnosticStatus.UNCONFIGURED);
        assertThat(probedEndpoints).isEmpty();
        assertThat(snapshot.items().toString())
                .doesNotContain("legacy.example.invalid", "controller.example.invalid", "splunk.example.invalid",
                        "legacy-user", "legacy-token", "controller-user", "controller-token",
                        "token-secret", "cookie-secret", "form-secret", "controller-a");
    }

    @Test
    void reportsSanitizedConnectivityFailureDetails() {
        String endpoint = "https:" + "//reader:credential@private-host.example.invalid/path";
        JenkinsProperties jenkins = new JenkinsProperties();
        SplunkProperties splunk = new SplunkProperties();
        HttpsEndpointProbe probe = new HttpsEndpointProbe(request -> {
            throw new AssertionError("Invalid endpoint must not reach the transport");
        });

        DiagnosticsSnapshot snapshot = service(successfulRunner(new ArrayList<>()), probe,
                jenkins, splunk, endpoint, "gitlab-token").refresh();

        DiagnosticItem connectivity = item(snapshot, "GitLab connectivity");
        assertThat(connectivity.status()).isEqualTo(DiagnosticStatus.UNAVAILABLE);
        assertThat(connectivity.detail()).isEqualTo("Configured HTTPS endpoint is invalid");
        assertThat(connectivity.toString())
                .doesNotContain("reader", "credential", "private-host.example.invalid", "path", "gitlab-token");
    }

    @Test
    void mapsTransportFailuresToFixedDetailsWithoutExceptionText() {
        JenkinsProperties jenkins = new JenkinsProperties();
        SplunkProperties splunk = new SplunkProperties();
        Map<String, EndpointProbe.Outcome> outcomes = Map.of(
                "https://certificate.example.invalid", EndpointProbe.Outcome.TLS_CERTIFICATE_FAILURE,
                "https://timeout.example.invalid", EndpointProbe.Outcome.TIMEOUT,
                "https://unreachable.example.invalid", EndpointProbe.Outcome.UNREACHABLE);
        jenkins.setControllers(Map.of(
                "certificate", controller("https://certificate.example.invalid", "user-a", "token-a"),
                "timeout", controller("https://timeout.example.invalid", "user-b", "token-b"),
                "unreachable", controller("https://unreachable.example.invalid", "user-c", "token-c")));

        DiagnosticsSnapshot snapshot = service(successfulRunner(new ArrayList<>()), outcomes::get,
                jenkins, splunk, "", "").refresh();

        assertThat(snapshot.items().stream()
                .filter(item -> item.component().startsWith("Jenkins connectivity"))
                .map(DiagnosticItem::detail))
                .containsExactlyInAnyOrder(
                        "TLS certificate-chain validation failed",
                        "HTTPS connectivity probe timed out",
                        "HTTPS endpoint could not be reached");
        assertThat(snapshot.items().toString())
                .doesNotContain("certificate.example.invalid", "timeout.example.invalid",
                        "unreachable.example.invalid",
                        "user-a", "user-b", "user-c", "token-a", "token-b", "token-c");
    }

    private static StartupDiagnosticsService service(ProcessRunner runner, EndpointProbe probe,
            JenkinsProperties jenkins, SplunkProperties splunk, String gitLabEndpoint, String gitLabToken) {
        return new StartupDiagnosticsService(runner, probe, jenkins, splunk,
                "claude", "kubectl", "cf", "git", gitLabEndpoint, gitLabToken);
    }

    private static ProcessRunner successfulRunner(List<ProcessRequest> commands) {
        return request -> {
            commands.add(request);
            Instant now = Instant.now();
            return new ProcessResult(true, 0, "tool version", "", false, false, "", now, now);
        };
    }

    private static JenkinsProperties.Controller controller(String endpoint, String username, String token) {
        JenkinsProperties.Controller controller = new JenkinsProperties.Controller();
        controller.setBaseUrl(endpoint);
        controller.setUsername(username);
        controller.setToken(token);
        return controller;
    }

    private static DiagnosticItem item(DiagnosticsSnapshot snapshot, String component) {
        return snapshot.items().stream()
                .filter(item -> item.component().equals(component))
                .findFirst()
                .orElseThrow();
    }
}
