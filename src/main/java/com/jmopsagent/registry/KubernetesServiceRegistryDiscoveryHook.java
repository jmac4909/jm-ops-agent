package com.jmopsagent.registry;

import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.process.ProcessRequest;
import com.jmopsagent.connector.process.ProcessResult;
import com.jmopsagent.connector.process.ProcessRunner;
import com.jmopsagent.connector.process.ReadOnlyCliPolicy;
import com.jmopsagent.domain.DeploymentEnvironment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lists deployments in one explicitly configured non-production target and retains only an exact
 * workload match. Command output and arbitrary workload metadata never leave this adapter.
 */
@Component
@Profile("local-live")
@Order(300)
public class KubernetesServiceRegistryDiscoveryHook implements ServiceRegistryDiscoveryHook {

    static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_OUTPUT_CHARACTERS = 64_000;
    private static final int MAX_DEPLOYMENTS = 256;
    private static final Pattern CONTEXT = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._:@/-]{0,252})");

    private final ProcessRunner processRunner;
    private final ObjectMapper objectMapper;
    private final String executable;
    private final Map<DeploymentEnvironment, Target> targets;

    public KubernetesServiceRegistryDiscoveryHook(
            ProcessRunner processRunner,
            ObjectMapper objectMapper,
            @Value("${jmops.kubernetes.executable:kubectl}") String executable,
            @Value("${jmops.kubernetes.dev-context:}") String devContext,
            @Value("${jmops.kubernetes.test-context:}") String testContext,
            @Value("${jmops.kubernetes.dev-namespace:${KUBERNETES_DEV_NAMESPACE:}}") String devNamespace,
            @Value("${jmops.kubernetes.test-namespace:${KUBERNETES_TEST_NAMESPACE:}}") String testNamespace) {
        this.processRunner = processRunner;
        this.objectMapper = objectMapper;
        this.executable = safeExecutable(executable);
        this.targets = Map.of(
                DeploymentEnvironment.DEV, new Target(devContext, devNamespace),
                DeploymentEnvironment.TEST, new Target(testContext, testNamespace));
    }

    @Override
    public Optional<RegistryDiscoveryUpdate> discover(ServiceDefinition service,
            DeploymentEnvironment environment) {
        Target configured = targets.get(environment);
        if (configured == null || !configured.configured()) {
            return Optional.empty();
        }

        String candidate;
        String expectedDeployment;
        String expectedService;
        String namespace;
        try {
            candidate = ConnectorInputValidator.kubernetesName(service.service(), "service");
            expectedDeployment = ConnectorInputValidator.kubernetesName(
                    service.attributeForEnvironment("eks.deployment", environment).orElse(candidate), "deployment");
            expectedService = ConnectorInputValidator.kubernetesName(
                    service.attributeForEnvironment("eks.service", environment).orElse(candidate), "service");
            namespace = ConnectorInputValidator.kubernetesName(
                    service.attributeForEnvironment("eks.namespace", environment).orElse(configured.namespace()),
                    "namespace");
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }

        List<String> arguments = List.of(
                "--context", configured.context(),
                "--namespace", namespace,
                "get", "deployments", "-o", "json");
        ReadOnlyCliPolicy.validateKubectl(arguments);

        ProcessResult result;
        try {
            result = processRunner.execute(new ProcessRequest(
                    executable, arguments, COMMAND_TIMEOUT, MAX_OUTPUT_CHARACTERS));
        } catch (Exception ignored) {
            return Optional.empty();
        }
        if (result == null || !result.successful() || result.outputTruncated()) {
            return Optional.empty();
        }

        Optional<DeploymentMatch> match = exactMatch(
                result.stdout(), expectedDeployment, expectedService);
        if (match.isEmpty()) {
            return Optional.empty();
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("eks.namespace." + environment.name(), namespace);
        attributes.put("eks.deployment." + environment.name(), match.get().deployment());
        if (!match.get().service().isBlank()) {
            attributes.put("eks.service." + environment.name(), match.get().service());
        }
        Set<String> aliases = match.get().deployment().equals(candidate)
                ? Set.of() : Set.of(match.get().deployment());
        return Optional.of(new RegistryDiscoveryUpdate(candidate,
                RegistryProvenance.DISCOVERED_KUBERNETES,
                scalarAttributes(attributes), aliases));
    }

    private Optional<DeploymentMatch> exactMatch(String output, String expectedDeployment, String expectedService) {
        try {
            JsonNode root = objectMapper.readTree(output);
            JsonNode items = root == null ? null : root.path("items");
            if (items == null || !items.isArray() || items.size() > MAX_DEPLOYMENTS) {
                return Optional.empty();
            }

            List<DeploymentMatch> deploymentMatches = new ArrayList<>();
            List<DeploymentMatch> labelMatches = new ArrayList<>();
            for (JsonNode item : items) {
                String name = item.path("metadata").path("name").asText("");
                String label = item.path("metadata").path("labels")
                        .path("app.kubernetes.io/name").asText("");
                if (!safeKubernetesName(name)) {
                    continue;
                }
                if (name.equals(expectedDeployment)) {
                    String service = label.equals(expectedService) ? label : "";
                    deploymentMatches.add(new DeploymentMatch(name, service));
                } else if (label.equals(expectedService)) {
                    labelMatches.add(new DeploymentMatch(name, label));
                }
            }
            if (deploymentMatches.size() == 1) {
                return Optional.of(deploymentMatches.getFirst());
            }
            return deploymentMatches.isEmpty() && labelMatches.size() == 1
                    ? Optional.of(labelMatches.getFirst()) : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static boolean safeKubernetesName(String value) {
        try {
            ConnectorInputValidator.kubernetesName(value, "deployment");
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static Map<String, List<String>> scalarAttributes(Map<String, String> attributes) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        attributes.forEach((key, value) -> result.put(key, List.of(value)));
        return result;
    }

    private static String safeExecutable(String value) {
        if (value == null || value.isBlank() || value.length() > 500 || value.indexOf('\0') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Kubernetes executable is invalid");
        }
        return value.trim();
    }

    private record Target(String context, String namespace) {
        private Target {
            context = context == null ? "" : context.trim();
            namespace = namespace == null ? "" : namespace.trim().toLowerCase(Locale.ROOT);
            if (!context.isEmpty() && !CONTEXT.matcher(context).matches()) {
                throw new IllegalArgumentException("Kubernetes context is invalid");
            }
            if (!namespace.isEmpty()) {
                ConnectorInputValidator.kubernetesName(namespace, "namespace");
            }
        }

        private boolean configured() {
            return !context.isEmpty() && !namespace.isEmpty();
        }
    }

    private record DeploymentMatch(String deployment, String service) {
    }
}
