package com.jmopsagent.registry;

import com.jmopsagent.connector.process.ProcessRequest;
import com.jmopsagent.connector.process.ProcessResult;
import com.jmopsagent.connector.process.ProcessRunner;
import com.jmopsagent.domain.DeploymentEnvironment;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class KubernetesServiceRegistryDiscoveryHookTest {

    @Test
    void usesAnExplicitBoundedReadOnlyCommandAndProjectsOneExactLabelMatch() {
        AtomicReference<ProcessRequest> captured = new AtomicReference<>();
        String output = """
                {
                  "items": [
                    {
                      "metadata": {
                        "name": "unrelated-service",
                        "labels": {"app.kubernetes.io/name": "unrelated-service"}
                      }
                    },
                    {
                      "metadata": {
                        "name": "sample-service-blue",
                        "labels": {"app.kubernetes.io/name": "sample-service"},
                        "annotations": {"private.example/token": "must-not-be-persisted"}
                      },
                      "spec": {"template": {"spec": {"containers": [{"image": "private-image"}]}}}
                    }
                  ]
                }
                """;
        ProcessRunner runner = request -> {
            captured.set(request);
            return success(output, false);
        };
        KubernetesServiceRegistryDiscoveryHook hook = hook(runner);

        RegistryDiscoveryUpdate update = hook.discover(
                new ServiceDefinition("sample-service"), DeploymentEnvironment.TEST).orElseThrow();

        assertThat(captured.get().executable()).isEqualTo("kubectl");
        assertThat(captured.get().arguments()).containsExactly(
                "--context", "test-cluster",
                "--namespace", "team-test",
                "get", "deployments", "-o", "json");
        assertThat(captured.get().timeout()).isEqualTo(KubernetesServiceRegistryDiscoveryHook.COMMAND_TIMEOUT);
        assertThat(captured.get().maxOutputCharacters())
                .isEqualTo(KubernetesServiceRegistryDiscoveryHook.MAX_OUTPUT_CHARACTERS);
        assertThat(captured.get().stdinContent()).isNull();

        assertThat(update.provenance()).isEqualTo(RegistryProvenance.DISCOVERED_KUBERNETES);
        assertThat(update.attributes()).containsOnlyKeys(
                "eks.namespace.TEST", "eks.deployment.TEST", "eks.service.TEST");
        assertThat(update.attributes().get("eks.deployment.TEST")).containsExactly("sample-service-blue");
        assertThat(update.aliases()).containsExactly("sample-service-blue");
        assertThat(update.attributes().values()).allSatisfy(values -> assertThat(values)
                .noneMatch(value -> value.contains("private-image") || value.contains("must-not-be-persisted")));
    }

    @Test
    void doesNotExecuteWithoutBothContextAndNamespace() {
        AtomicReference<ProcessRequest> captured = new AtomicReference<>();
        ProcessRunner runner = request -> {
            captured.set(request);
            return success("{\"items\":[]}", false);
        };
        KubernetesServiceRegistryDiscoveryHook hook = new KubernetesServiceRegistryDiscoveryHook(
                runner, new ObjectMapper(), "kubectl", "dev-cluster", "", "", "team-test");

        Optional<RegistryDiscoveryUpdate> update = hook.discover(
                new ServiceDefinition("sample-service"), DeploymentEnvironment.DEV);

        assertThat(update).isEmpty();
        assertThat(captured).hasNullValue();
    }

    @Test
    void doesNotClaimAServiceNameThatWasNotObserved() {
        KubernetesServiceRegistryDiscoveryHook hook = hook(request -> success("""
                {"items":[{"metadata":{"name":"sample-service"}}]}
                """, false));

        RegistryDiscoveryUpdate update = hook.discover(
                new ServiceDefinition("sample-service"), DeploymentEnvironment.TEST).orElseThrow();

        assertThat(update.attributes()).containsKeys("eks.namespace.TEST", "eks.deployment.TEST")
                .doesNotContainKey("eks.service.TEST");
    }

    @Test
    void rejectsFuzzyAmbiguousAndTruncatedResults() {
        String fuzzy = """
                {"items":[
                  {"metadata":{"name":"sample-service-old","labels":{"app.kubernetes.io/name":"other"}}},
                  {"metadata":{"name":"one","labels":{"app.kubernetes.io/name":"sample-service"}}},
                  {"metadata":{"name":"two","labels":{"app.kubernetes.io/name":"sample-service"}}}
                ]}
                """;
        KubernetesServiceRegistryDiscoveryHook ambiguous = hook(request -> success(fuzzy, false));
        assertThat(ambiguous.discover(
                new ServiceDefinition("sample-service"), DeploymentEnvironment.TEST)).isEmpty();

        KubernetesServiceRegistryDiscoveryHook truncated = hook(request -> success(
                "{\"items\":[{\"metadata\":{\"name\":\"sample-service\"}}]}", true));
        assertThat(truncated.discover(
                new ServiceDefinition("sample-service"), DeploymentEnvironment.TEST)).isEmpty();
    }

    private static KubernetesServiceRegistryDiscoveryHook hook(ProcessRunner runner) {
        return new KubernetesServiceRegistryDiscoveryHook(
                runner, new ObjectMapper(), "kubectl",
                "dev-cluster", "test-cluster", "team-dev", "team-test");
    }

    private static ProcessResult success(String stdout, boolean truncated) {
        Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
        return new ProcessResult(true, 0, stdout, "", false, truncated,
                null, timestamp, timestamp.plusSeconds(1));
    }
}
