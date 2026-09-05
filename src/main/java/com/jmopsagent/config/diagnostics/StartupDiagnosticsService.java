package com.jmopsagent.config.diagnostics;

import com.jmopsagent.connector.process.ProcessRequest;
import com.jmopsagent.connector.process.ProcessResult;
import com.jmopsagent.connector.process.ProcessRunner;
import com.jmopsagent.connector.process.ProcessEnvironmentPolicy;
import com.jmopsagent.jenkins.JenkinsProperties;
import com.jmopsagent.splunk.SplunkProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StartupDiagnosticsService {
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_SPLUNK_TOKEN_CHARACTERS = 8_192;
    private static final int MAX_SPLUNK_COOKIE_CHARACTERS = 8_192;
    private static final int MAX_SPLUNK_FORM_KEY_CHARACTERS = 4_096;
    private static final Pattern SPLUNK_COOKIE_NAME =
            Pattern.compile("[!#$%&'*+.^_`|~A-Za-z0-9-]{1,128}");

    private final ProcessRunner processRunner;
    private final String claudeExecutable;
    private final String kubectlExecutable;
    private final String cfExecutable;
    private final String gitExecutable;
    private final EndpointProbe endpointProbe;
    private final ConnectorConfiguration jenkins;
    private final ConnectorConfiguration gitLab;
    private final ConnectorConfiguration splunk;
    private final AtomicReference<DiagnosticsSnapshot> cached = new AtomicReference<>();

    public StartupDiagnosticsService(ProcessRunner processRunner, EndpointProbe endpointProbe,
            JenkinsProperties jenkinsProperties, SplunkProperties splunkProperties,
            @Value("${jmops.claude.executable:claude}") String claudeExecutable,
            @Value("${jmops.kubernetes.executable:kubectl}") String kubectlExecutable,
            @Value("${jmops.tas.executable:cf}") String cfExecutable,
            @Value("${jmops.git.executable:git}") String gitExecutable,
            @Value("${jmops.integrations.gitlab.base-url:}") String gitLabBaseUrl,
            @Value("${jmops.integrations.gitlab.token:}") String gitLabToken) {
        this.processRunner = processRunner;
        this.endpointProbe = endpointProbe;
        this.claudeExecutable = claudeExecutable;
        this.kubectlExecutable = kubectlExecutable;
        this.cfExecutable = cfExecutable;
        this.gitExecutable = gitExecutable;
        this.jenkins = jenkinsConfiguration(jenkinsProperties);
        this.gitLab = simpleConfiguration(gitLabBaseUrl, gitLabToken);
        this.splunk = splunkConfiguration(splunkProperties);
    }

    public DiagnosticsSnapshot getDiagnostics() {
        DiagnosticsSnapshot existing = cached.get();
        if (existing != null) return existing;
        DiagnosticsSnapshot collected = collect();
        cached.compareAndSet(null, collected);
        return cached.get();
    }

    public DiagnosticsSnapshot refresh() {
        DiagnosticsSnapshot collected = collect();
        cached.set(collected);
        return collected;
    }

    private DiagnosticsSnapshot collect() {
        List<DiagnosticItem> items = new ArrayList<>();
        items.add(command("Claude Code", claudeExecutable, List.of("--version"),
                ProcessEnvironmentPolicy.CLAUDE_VERTEX_ALLOWLIST));
        items.add(command("kubectl", kubectlExecutable, List.of("version", "--client=true", "--output=json")));
        items.add(command("CF CLI", cfExecutable, List.of("version")));
        items.add(command("Git", gitExecutable, List.of("--version")));
        items.add(configured("Jenkins", jenkins));
        items.add(configured("GitLab", gitLab));
        items.add(configured("Splunk", splunk));
        addConnectivityItems(items, "Jenkins connectivity", jenkins.endpoints());
        addConnectivityItems(items, "GitLab connectivity", gitLab.endpoints());
        addConnectivityItems(items, "Splunk connectivity", splunk.endpoints());
        return new DiagnosticsSnapshot(Instant.now(), items);
    }

    private DiagnosticItem command(String component, String executable, List<String> arguments) {
        return command(component, executable, arguments, ProcessEnvironmentPolicy.INHERIT);
    }

    private DiagnosticItem command(String component, String executable, List<String> arguments,
                                   ProcessEnvironmentPolicy environmentPolicy) {
        ProcessResult result = processRunner.execute(new ProcessRequest(executable, arguments, CHECK_TIMEOUT, 4_000,
                null, environmentPolicy));
        if (!result.successful()) {
            String detail = result.timedOut() ? "Version check timed out"
                    : result.started() ? "Version check returned exit code " + result.exitCode()
                    : "Executable was not found or could not be started";
            return new DiagnosticItem(component, DiagnosticStatus.UNAVAILABLE, detail);
        }
        String detail = result.stdout().lines().filter(line -> !line.isBlank()).findFirst().orElse("Available");
        return new DiagnosticItem(component, DiagnosticStatus.AVAILABLE,
                detail.substring(0, Math.min(detail.length(), 240)));
    }

    private void addConnectivityItems(List<DiagnosticItem> items, String component, List<String> endpoints) {
        for (int index = 0; index < endpoints.size(); index++) {
            String safeComponent = endpoints.size() == 1 ? component : component + " " + (index + 1);
            items.add(connectivity(safeComponent, endpointProbe.probe(endpoints.get(index))));
        }
    }

    private static DiagnosticItem connectivity(String component, EndpointProbe.Outcome outcome) {
        if (outcome == EndpointProbe.Outcome.REACHABLE) {
            return new DiagnosticItem(component, DiagnosticStatus.AVAILABLE,
                    "Credential-free HTTPS HEAD probe completed");
        }
        String detail = switch (outcome) {
            case INVALID_ENDPOINT -> "Configured HTTPS endpoint is invalid";
            case TLS_CERTIFICATE_FAILURE -> "TLS certificate-chain validation failed";
            case TIMEOUT -> "HTTPS connectivity probe timed out";
            case UNREACHABLE -> "HTTPS endpoint could not be reached";
            case REACHABLE -> throw new IllegalStateException("Reachable outcome was handled above");
        };
        return new DiagnosticItem(component, DiagnosticStatus.UNAVAILABLE, detail);
    }

    private static DiagnosticItem configured(String component, ConnectorConfiguration configuration) {
        return new DiagnosticItem(component,
                configuration.configured() ? DiagnosticStatus.CONFIGURED : DiagnosticStatus.UNCONFIGURED,
                configuration.detail());
    }

    private static ConnectorConfiguration simpleConfiguration(String endpoint, String credential) {
        if (!allPresent(endpoint, credential)) {
            return ConnectorConfiguration.unconfigured("Required settings are incomplete");
        }
        return ConnectorConfiguration.configured(
                "Required endpoint and credential settings are present", List.of(endpoint));
    }

    private static ConnectorConfiguration jenkinsConfiguration(JenkinsProperties properties) {
        if (properties == null) {
            return ConnectorConfiguration.unconfigured("Required settings are incomplete");
        }

        Map<String, JenkinsProperties.Controller> controllers = properties.getControllers();
        boolean hasControllers = controllers != null && !controllers.isEmpty();
        boolean hasLegacyValues = anyPresent(
                properties.getBaseUrl(), properties.getUsername(), properties.getToken());
        if (hasControllers && hasLegacyValues) {
            return ConnectorConfiguration.unconfigured(
                    "Legacy and multi-controller settings cannot be combined");
        }

        if (!hasControllers) {
            if (!allPresent(properties.getBaseUrl(), properties.getUsername(), properties.getToken())) {
                return ConnectorConfiguration.unconfigured("Required settings are incomplete");
            }
            return ConnectorConfiguration.configured(
                    "Required settings are present for one controller", List.of(properties.getBaseUrl()));
        }

        List<String> completeEndpoints = new ArrayList<>();
        boolean complete = true;
        for (JenkinsProperties.Controller controller : controllers.values()) {
            if (controller == null || !allPresent(
                    controller.getBaseUrl(), controller.getUsername(), controller.getToken())) {
                complete = false;
                continue;
            }
            completeEndpoints.add(controller.getBaseUrl());
        }
        if (!complete) {
            return new ConnectorConfiguration(false,
                    "One or more controller settings are incomplete", completeEndpoints);
        }
        int count = completeEndpoints.size();
        String noun = count == 1 ? "controller" : "controllers";
        return ConnectorConfiguration.configured(
                "Required settings are present for " + count + " " + noun, completeEndpoints);
    }

    private static ConnectorConfiguration splunkConfiguration(SplunkProperties properties) {
        if (properties == null) {
            return ConnectorConfiguration.unconfigured("Required settings are incomplete");
        }
        if (!properties.hasValidRequestTimeout()) {
            return ConnectorConfiguration.unconfigured("Request timeout setting is invalid");
        }
        if (!allPresent(properties.getBaseUrl())) {
            return ConnectorConfiguration.unconfigured("Required settings are incomplete");
        }

        boolean tokenSupplied = allPresent(properties.getToken());
        boolean sessionCookieSupplied = allPresent(properties.getSessionCookie());
        boolean formKeySupplied = allPresent(properties.getFormKey());
        boolean tokenValid = validCredentialValue(properties.getToken(), MAX_SPLUNK_TOKEN_CHARACTERS);
        boolean sessionCookiesValid = validSplunkSessionCookieNames(properties.getSessionCookie());
        boolean formKeyValid = validCredentialValue(properties.getFormKey(), MAX_SPLUNK_FORM_KEY_CHARACTERS);
        String mode = properties.getAuthMode() == null ? "BEARER_TOKEN" : properties.getAuthMode().name();
        boolean tokenMode = "BEARER_TOKEN".equals(mode) || "SESSION_KEY".equals(mode);
        if (tokenMode && tokenValid && !sessionCookieSupplied && !formKeySupplied) {
            return ConnectorConfiguration.configured(
                    "Required endpoint and token settings are present", List.of(properties.getBaseUrl()));
        }
        if ("SESSION_CSRF".equals(mode) && !tokenSupplied && sessionCookiesValid && formKeyValid) {
            return ConnectorConfiguration.configured(
                    "Required endpoint and session/CSRF settings are present", List.of(properties.getBaseUrl()));
        }
        return ConnectorConfiguration.unconfigured("Authentication settings are incomplete or conflicting");
    }

    private static boolean validCredentialValue(String value, int maximumCharacters) {
        if (value == null || value.isBlank() || value.length() > maximumCharacters) return false;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return false;
        }
        return true;
    }

    private static boolean validSplunkSessionCookieNames(String cookieHeader) {
        if (!validCredentialValue(cookieHeader, MAX_SPLUNK_COOKIE_CHARACTERS)) return false;
        boolean splunkd = false;
        boolean sessionId = false;
        boolean csrfToken = false;
        for (String part : cookieHeader.split(";", -1)) {
            String candidate = part.trim();
            int separator = candidate.indexOf('=');
            if (separator < 1 || separator == candidate.length() - 1) return false;
            String name = candidate.substring(0, separator).trim();
            if (!SPLUNK_COOKIE_NAME.matcher(name).matches()) return false;
            String normalizedName = name.toLowerCase(Locale.ROOT);
            splunkd |= normalizedName.startsWith("splunkd_");
            sessionId |= normalizedName.startsWith("session_id_");
            csrfToken |= normalizedName.startsWith("splunkweb_csrf_token_");
        }
        return splunkd && sessionId && csrfToken;
    }

    private static boolean allPresent(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) return false;
        }
        return true;
    }

    private static boolean anyPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return true;
        }
        return false;
    }

    private record ConnectorConfiguration(boolean configured, String detail, List<String> endpoints) {
        private ConnectorConfiguration {
            endpoints = List.copyOf(endpoints);
        }

        private static ConnectorConfiguration configured(String detail, List<String> endpoints) {
            return new ConnectorConfiguration(true, detail, endpoints);
        }

        private static ConnectorConfiguration unconfigured(String detail) {
            return new ConnectorConfiguration(false, detail, List.of());
        }
    }
}
