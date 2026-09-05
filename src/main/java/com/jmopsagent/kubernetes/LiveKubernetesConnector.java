package com.jmopsagent.kubernetes;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.EvidenceSource;
import com.jmopsagent.connector.EvidenceType;
import com.jmopsagent.connector.process.ProcessRequest;
import com.jmopsagent.connector.process.ProcessResult;
import com.jmopsagent.connector.process.ProcessRunner;
import com.jmopsagent.connector.process.ReadOnlyCliPolicy;
import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.registry.ServiceDefinition;
import com.jmopsagent.registry.ServiceRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local-live")
public class LiveKubernetesConnector implements KubernetesConnector {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final ProcessRunner processRunner;
    private final ObjectMapper objectMapper;
    private final ServiceRegistry serviceRegistry;
    private final String executable;
    private final Map<Environment, String> contexts;
    private final Map<Environment, String> fallbackNamespaces;

    public LiveKubernetesConnector(
            ProcessRunner processRunner,
            ObjectMapper objectMapper,
            ServiceRegistry serviceRegistry,
            @Value("${jmops.kubernetes.executable:kubectl}") String executable,
            @Value("${jmops.kubernetes.dev-context:}") String devContext,
            @Value("${jmops.kubernetes.test-context:}") String testContext,
            @Value("${jmops.kubernetes.dev-namespace:${KUBERNETES_DEV_NAMESPACE:}}") String devNamespace,
            @Value("${jmops.kubernetes.test-namespace:${KUBERNETES_TEST_NAMESPACE:}}") String testNamespace) {
        this.processRunner = processRunner;
        this.objectMapper = objectMapper;
        this.serviceRegistry = serviceRegistry;
        this.executable = executable;
        this.contexts = Map.of(Environment.DEV, clean(devContext), Environment.TEST, clean(testContext));
        this.fallbackNamespaces = Map.of(Environment.DEV, clean(devNamespace), Environment.TEST, clean(testNamespace));
    }

    @Override
    public List<ConnectorEvidence> getWorkloadHealth(String service, Environment environment) {
        String safeService = ConnectorInputValidator.kubernetesName(service, "service");
        Target target = target(safeService, environment);
        if (!target.configured()) {
            return List.of(configurationMissing(safeService, environment));
        }
        ConnectorEvidence deployment = jsonEvidence(safeService, environment, EvidenceType.WORKLOAD_HEALTH,
                "deployment health", args(target, "get", "deployment", target.deployment(), "-o", "json"),
                this::summarizeDeploymentHealth);
        ConnectorEvidence pods = jsonEvidence(safeService, environment, EvidenceType.WORKLOAD_HEALTH,
                "pod health", args(target, "get", "pods", "-l", "app.kubernetes.io/name=" + target.serviceName(), "-o", "json"),
                this::summarizePodHealth);
        ConnectorEvidence rollout = commandEvidence(safeService, environment, EvidenceType.WORKLOAD_HEALTH,
                "rollout status", args(target, "rollout", "status", "deployment/" + target.deployment(), "--timeout=15s"), 20_000);
        return List.of(deployment, pods, rollout);
    }

    @Override
    public List<ConnectorEvidence> getRecentPodEvents(String service, Environment environment, EvidenceQuery query) {
        String safeService = ConnectorInputValidator.kubernetesName(service, "service");
        Target target = target(safeService, environment);
        if (!target.configured()) return List.of(configurationMissing(safeService, environment, EvidenceType.POD_EVENT));
        ConnectorEvidence evidence = jsonEvidence(safeService, environment, EvidenceType.POD_EVENT,
                "recent Kubernetes events",
                args(target, "get", "events", "--field-selector", "involvedObject.kind=Pod",
                        "--sort-by=.lastTimestamp", "-o", "json"),
                root -> projectEvents(root, target.deployment(), query.maxResults(), query.maxContentCharacters()));
        return List.of(evidence);
    }

    @Override
    public List<ConnectorEvidence> getRecentPodLogs(String service, Environment environment, EvidenceQuery query) {
        String safeService = ConnectorInputValidator.kubernetesName(service, "service");
        Target target = target(safeService, environment);
        if (!target.configured()) return List.of(configurationMissing(safeService, environment, EvidenceType.APPLICATION_LOG));
        long seconds = Math.max(1, Math.min(86_400, ChronoUnit.SECONDS.between(query.from(), query.to())));
        int tail = Math.min(1_000, query.maxResults());
        ConnectorEvidence evidence = commandEvidence(safeService, environment, EvidenceType.APPLICATION_LOG,
                "recent Kubernetes logs",
                args(target, "logs", "deployment/" + target.deployment(), "--all-containers=true", "--timestamps=true",
                        "--since=" + seconds + "s", "--tail=" + tail), query.maxContentCharacters());
        return List.of(evidence);
    }

    @Override
    public List<ConnectorEvidence> getDeploymentMetadata(String service, Environment environment) {
        String safeService = ConnectorInputValidator.kubernetesName(service, "service");
        Target target = target(safeService, environment);
        if (!target.configured()) return List.of(configurationMissing(safeService, environment, EvidenceType.DEPLOYMENT));
        return List.of(jsonEvidence(safeService, environment, EvidenceType.DEPLOYMENT,
                "Kubernetes deployment metadata", args(target, "get", "deployment", target.deployment(), "-o", "json"),
                this::projectDeploymentMetadata));
    }

    @Override
    public List<ConnectorEvidence> getEffectiveConfiguration(String service, Environment environment) {
        String safeService = ConnectorInputValidator.kubernetesName(service, "service");
        Target target = target(safeService, environment);
        if (!target.configured()) return List.of(configurationMissing(safeService, environment, EvidenceType.CONFIGURATION));
        return List.of(jsonEvidence(safeService, environment, EvidenceType.CONFIGURATION,
                "effective non-secret deployment configuration",
                args(target, "get", "deployment", target.deployment(), "-o", "json"), this::projectSafeConfiguration));
    }

    @Override
    public List<ConnectorEvidence> getNetworking(String service, Environment environment) {
        String safeService = ConnectorInputValidator.kubernetesName(service, "service");
        Target target = target(safeService, environment);
        if (!target.configured()) return List.of(configurationMissing(safeService, environment, EvidenceType.NETWORK));
        ConnectorEvidence services = commandEvidence(safeService, environment, EvidenceType.NETWORK,
                "Kubernetes service", args(target, "get", "service", target.serviceName(), "-o", "json"), 30_000);
        ConnectorEvidence ingress = commandEvidence(safeService, environment, EvidenceType.NETWORK,
                "Kubernetes ingress", args(target, "get", "ingress", "-l", "app.kubernetes.io/name=" + target.serviceName(),
                        "-o", "json"), 30_000);
        return List.of(services, ingress);
    }

    private Target target(String service, Environment environment) {
        if (environment == null) {
            throw new IllegalArgumentException("Environment is required");
        }
        ServiceDefinition definition = serviceRegistry.resolve(service).orElse(null);
        DeploymentEnvironment registryEnvironment = DeploymentEnvironment.valueOf(environment.name());
        String namespace = definition == null ? fallbackNamespaces.get(environment)
                : definition.attributeForEnvironment("eks.namespace", registryEnvironment)
                        .orElse(fallbackNamespaces.get(environment));
        String deployment = definition == null ? service
                : definition.attributeForEnvironment("eks.deployment", registryEnvironment).orElse(service);
        String serviceName = definition == null ? service
                : definition.attributeForEnvironment("eks.service", registryEnvironment).orElse(service);
        return new Target(contexts.get(environment), namespace, deployment, serviceName);
    }

    private List<String> args(Target target, String... operation) {
        List<String> args = new ArrayList<>();
        args.add("--context");
        args.add(target.context());
        args.add("--namespace");
        args.add(target.namespace());
        args.addAll(List.of(operation));
        return List.copyOf(args);
    }

    private ConnectorEvidence commandEvidence(String service, Environment environment, EvidenceType type,
            String label, List<String> args, int maxCharacters) {
        ReadOnlyCliPolicy.validateKubectl(args);
        ProcessResult result = processRunner.execute(new ProcessRequest(executable, args, COMMAND_TIMEOUT,
                Math.min(100_000, Math.max(1, maxCharacters))));
        String content = result.successful() ? result.stdout() : nonBlank(result.stderr(), result.startupError());
        String summary = result.successful() ? label + " collected" : label + " unavailable";
        return evidence(service, environment, type, summary, content, Map.of(
                "status", result.successful() ? "SUCCESS" : "ERROR",
                "exitCode", Integer.toString(result.exitCode()),
                "timedOut", Boolean.toString(result.timedOut()),
                "truncated", Boolean.toString(result.outputTruncated())));
    }

    private ConnectorEvidence jsonEvidence(String service, Environment environment, EvidenceType type,
            String label, List<String> args, JsonProjection projection) {
        ReadOnlyCliPolicy.validateKubectl(args);
        ProcessResult result = processRunner.execute(new ProcessRequest(executable, args, COMMAND_TIMEOUT, 100_000));
        if (!result.successful()) {
            return evidence(service, environment, type, label + " unavailable",
                    nonBlank(result.stderr(), result.startupError()), Map.of("status", "ERROR"));
        }
        try {
            String projected = projection.apply(objectMapper.readTree(result.stdout()));
            return evidence(service, environment, type, label + " collected", projected,
                    Map.of("status", "SUCCESS", "truncated", Boolean.toString(result.outputTruncated())));
        } catch (Exception ex) {
            return evidence(service, environment, type, label + " could not be parsed",
                    "kubectl returned invalid or unsupported JSON", Map.of("status", "PARSE_ERROR"));
        }
    }

    private String summarizeDeploymentHealth(JsonNode root) throws Exception {
        ObjectNode summary = objectMapper.createObjectNode();
        JsonNode status = root.path("status");
        summary.put("desiredReplicas", root.path("spec").path("replicas").asInt(0));
        summary.put("availableReplicas", status.path("availableReplicas").asInt(0));
        summary.put("readyReplicas", status.path("readyReplicas").asInt(0));
        summary.put("unavailableReplicas", status.path("unavailableReplicas").asInt(0));
        summary.set("conditions", status.path("conditions"));
        return objectMapper.writeValueAsString(summary);
    }

    private String summarizePodHealth(JsonNode root) throws Exception {
        ArrayNode pods = objectMapper.createArrayNode();
        for (JsonNode item : root.path("items")) {
            ObjectNode pod = pods.addObject();
            pod.put("name", item.path("metadata").path("name").asText("unknown"));
            pod.put("phase", item.path("status").path("phase").asText("unknown"));
            ArrayNode containers = pod.putArray("containers");
            for (JsonNode status : item.path("status").path("containerStatuses")) {
                ObjectNode container = containers.addObject();
                container.put("name", status.path("name").asText("unknown"));
                container.put("ready", status.path("ready").asBoolean(false));
                container.put("restartCount", status.path("restartCount").asInt(0));
                container.set("state", projectContainerState(status.path("state")));
            }
        }
        return objectMapper.writeValueAsString(pods);
    }

    private String projectDeploymentMetadata(JsonNode root) throws Exception {
        ObjectNode projected = objectMapper.createObjectNode();
        projected.put("name", root.path("metadata").path("name").asText());
        projected.put("namespace", root.path("metadata").path("namespace").asText());
        projected.put("generation", root.path("metadata").path("generation").asLong());
        projected.set("labels", root.path("metadata").path("labels"));
        projected.set("strategy", root.path("spec").path("strategy"));
        ArrayNode images = projected.putArray("images");
        for (JsonNode container : root.path("spec").path("template").path("spec").path("containers")) {
            ObjectNode image = images.addObject();
            image.put("container", container.path("name").asText());
            image.put("image", container.path("image").asText());
        }
        return objectMapper.writeValueAsString(projected);
    }

    private String projectSafeConfiguration(JsonNode root) throws Exception {
        ObjectNode projected = objectMapper.createObjectNode();
        ArrayNode containers = projected.putArray("containers");
        for (JsonNode source : root.path("spec").path("template").path("spec").path("containers")) {
            ObjectNode container = containers.addObject();
            container.put("name", source.path("name").asText());
            container.put("image", source.path("image").asText());
            ArrayNode environmentNames = container.putArray("environmentVariables");
            for (JsonNode env : source.path("env")) {
                ObjectNode item = environmentNames.addObject();
                item.put("name", env.path("name").asText());
                if (env.has("valueFrom")) {
                    item.put("source", firstFieldName(env.path("valueFrom")));
                } else {
                    item.put("source", "literal-value-redacted-at-source");
                }
            }
            ArrayNode envFrom = container.putArray("environmentSources");
            for (JsonNode sourceRef : source.path("envFrom")) {
                envFrom.add(firstFieldName(sourceRef));
            }
            container.set("resources", source.path("resources"));
            if (source.has("readinessProbe")) container.set("readinessProbe", projectProbe(source.path("readinessProbe")));
            if (source.has("livenessProbe")) container.set("livenessProbe", projectProbe(source.path("livenessProbe")));
        }
        return objectMapper.writeValueAsString(projected);
    }

    private ObjectNode projectContainerState(JsonNode state) {
        ObjectNode projected = objectMapper.createObjectNode();
        for (String stateName : List.of("waiting", "running", "terminated")) {
            if (!state.has(stateName)) continue;
            JsonNode value = state.path(stateName);
            projected.put("state", stateName);
            if (value.has("reason")) projected.put("reason", value.path("reason").asText());
            if (value.has("exitCode")) projected.put("exitCode", value.path("exitCode").asInt());
            if (value.has("startedAt")) projected.put("startedAt", value.path("startedAt").asText());
            if (value.has("finishedAt")) projected.put("finishedAt", value.path("finishedAt").asText());
            break;
        }
        return projected;
    }

    private ObjectNode projectProbe(JsonNode probe) {
        ObjectNode projected = objectMapper.createObjectNode();
        for (String field : List.of("initialDelaySeconds", "periodSeconds", "timeoutSeconds",
                "successThreshold", "failureThreshold", "terminationGracePeriodSeconds")) {
            if (probe.has(field)) projected.put(field, probe.path(field).asInt());
        }
        if (probe.has("httpGet")) {
            JsonNode http = probe.path("httpGet");
            ObjectNode target = projected.putObject("httpGet");
            target.put("path", http.path("path").asText());
            target.put("port", http.path("port").asText());
            target.put("scheme", http.path("scheme").asText());
            if (http.has("httpHeaders")) target.put("headers", "omitted-at-source");
        } else if (probe.has("tcpSocket")) {
            projected.putObject("tcpSocket").put("port", probe.path("tcpSocket").path("port").asText());
        } else if (probe.has("grpc")) {
            projected.putObject("grpc").put("port", probe.path("grpc").path("port").asInt());
        } else if (probe.has("exec")) {
            projected.put("exec", "command-omitted-at-source");
        }
        return projected;
    }

    private String projectEvents(JsonNode root, String workloadName, int maxResults, int maxCharacters) throws Exception {
        ArrayNode events = objectMapper.createArrayNode();
        int count = 0;
        for (JsonNode item : root.path("items")) {
            String involvedName = item.path("involvedObject").path("name").asText("");
            if (!involvedName.startsWith(workloadName + "-") || count++ >= maxResults) continue;
            ObjectNode event = events.addObject();
            event.put("timestamp", item.path("lastTimestamp").asText(item.path("eventTime").asText()));
            event.put("type", item.path("type").asText());
            event.put("reason", item.path("reason").asText());
            event.put("message", item.path("message").asText());
            event.put("count", item.path("count").asInt(1));
            event.put("object", involvedName);
        }
        String value = objectMapper.writeValueAsString(events);
        return value.substring(0, Math.min(value.length(), maxCharacters));
    }

    private static String firstFieldName(JsonNode node) {
        return node.propertyNames().stream().findFirst().orElse("unknown");
    }

    private ConnectorEvidence configurationMissing(String service, Environment environment) {
        return configurationMissing(service, environment, EvidenceType.WORKLOAD_HEALTH);
    }

    private ConnectorEvidence configurationMissing(String service, Environment environment, EvidenceType type) {
        return evidence(service, environment, type, "Kubernetes target is not configured",
                "Set an explicit context and namespace for this environment before enabling live Kubernetes evidence.",
                Map.of("status", "UNCONFIGURED"));
    }

    private ConnectorEvidence evidence(String service, Environment environment, EvidenceType type, String summary,
            String content, Map<String, String> metadata) {
        Instant timestamp = Instant.now();
        return new ConnectorEvidence("kubernetes-" + type.name().toLowerCase() + "-" + timestamp.toEpochMilli(),
                EvidenceSource.KUBERNETES, type, timestamp, service, environment, summary, content, null, metadata, 0.9);
    }

    private static String nonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "Command did not provide an error message" : second;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private record Target(String context, String namespace, String deployment, String serviceName) {
        private Target {
            context = clean(context);
            namespace = clean(namespace);
            deployment = ConnectorInputValidator.kubernetesName(deployment, "deployment");
            serviceName = ConnectorInputValidator.kubernetesName(serviceName, "service");
            if (context.length() > 500 || context.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Kubernetes context is invalid");
            }
            if (!namespace.isBlank()) {
                ConnectorInputValidator.kubernetesName(namespace, "namespace");
            }
        }

        private boolean configured() {
            return !context.isBlank() && !namespace.isBlank();
        }
    }

    @FunctionalInterface
    private interface JsonProjection {
        String apply(JsonNode root) throws Exception;
    }
}
