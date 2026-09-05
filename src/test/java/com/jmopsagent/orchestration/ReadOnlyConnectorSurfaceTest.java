package com.jmopsagent.orchestration;

import com.jmopsagent.connector.DocumentationConnector;
import com.jmopsagent.database.DependencyConnector;
import com.jmopsagent.gitlab.GitLabConnector;
import com.jmopsagent.jenkins.JenkinsConnector;
import com.jmopsagent.kubernetes.KubernetesConnector;
import com.jmopsagent.splunk.SplunkConnector;
import com.jmopsagent.tas.TasConnector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyConnectorSurfaceTest {
    private static final Pattern MUTATION_OR_GENERIC_EXECUTION = Pattern.compile(
            "^(run|execute|apply|create|delete|edit|patch|replace|scale|set|restart|rerun|write|update|push|commit).*");

    @Test
    void operationalConnectorsExposeOnlySemanticReadMethods() {
        List<Class<?>> connectorTypes = List.of(KubernetesConnector.class, TasConnector.class,
                JenkinsConnector.class, GitLabConnector.class, SplunkConnector.class,
                DependencyConnector.class, DocumentationConnector.class);

        List<String> exposedMethods = connectorTypes.stream()
                .flatMap(type -> Stream.of(type.getDeclaredMethods()))
                .map(Method::getName)
                .toList();

        assertThat(exposedMethods).isNotEmpty().allSatisfy(name ->
                assertThat(name.toLowerCase(Locale.ROOT)).doesNotMatch(MUTATION_OR_GENERIC_EXECUTION));
        assertThat(Set.copyOf(exposedMethods)).doesNotContain(
                "runKubectl", "runCf", "runCommand", "executeCommand", "deploy", "restart", "rerun");
    }
}
