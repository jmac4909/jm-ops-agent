package com.jmopsagent.jenkins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

class JenkinsControllerRegistryTest {

    @Test
    void bindsIndependentControllersFromExternalConfigurationProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "jmops.integrations.jenkins.controllers.primary.base-url",
                "https://primary.example.invalid/controller",
                "jmops.integrations.jenkins.controllers.primary.username", "reader-one",
                "jmops.integrations.jenkins.controllers.primary.token", "test-value-one",
                "jmops.integrations.jenkins.controllers.secondary.base-url",
                "https://secondary.example.invalid/controller",
                "jmops.integrations.jenkins.controllers.secondary.username", "reader-two",
                "jmops.integrations.jenkins.controllers.secondary.token", "test-value-two"));

        JenkinsProperties properties = new Binder(source)
                .bind("jmops.integrations.jenkins", Bindable.of(JenkinsProperties.class))
                .orElseThrow(() -> new AssertionError("Jenkins properties did not bind"));

        assertThat(properties.getControllers()).containsOnlyKeys("primary", "secondary");
        assertThat(properties.getControllers().get("primary").getBaseUrl())
                .isEqualTo("https://primary.example.invalid/controller");
        assertThat(properties.getControllers().get("secondary").getUsername()).isEqualTo("reader-two");
        assertThat(new JenkinsControllerRegistry(WebClient.builder(), properties).size()).isEqualTo(2);
    }

    @Test
    void supportsLegacySingleControllerConfiguration() {
        JenkinsProperties properties = new JenkinsProperties();
        properties.setBaseUrl("https://jenkins.example.invalid/controller");
        properties.setUsername("reader");
        properties.setToken("placeholder-token");

        JenkinsControllerRegistry registry = new JenkinsControllerRegistry(WebClient.builder(), properties);

        assertThat(registry.size()).isOne();
        assertThat(registry.resolve(Optional.empty()).id()).isEqualTo("default");
    }

    @Test
    void rejectsMixedLegacyAndControllerMapConfiguration() {
        JenkinsProperties properties = new JenkinsProperties();
        properties.setBaseUrl("https://legacy.example.invalid");
        properties.setUsername("reader");
        properties.setToken("placeholder-token");
        properties.setControllers(Map.of("controller-one",
                configuration("https://one.example.invalid", "reader", "placeholder-token")));

        assertThatThrownBy(() -> new JenkinsControllerRegistry(WebClient.builder(), properties))
                .isInstanceOfSatisfying(JenkinsConnectorException.class, failure ->
                        assertThat(failure.kind()).isEqualTo(JenkinsFailureKind.INVALID_CONFIGURATION));
    }

    @Test
    void rejectsIncompleteControllerWithoutLeakingItsValues() {
        JenkinsProperties properties = new JenkinsProperties();
        properties.setControllers(Map.of("controller-one",
                configuration("https://one.example.invalid", "reader", "")));

        assertThatThrownBy(() -> new JenkinsControllerRegistry(WebClient.builder(), properties))
                .isInstanceOfSatisfying(JenkinsConnectorException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(JenkinsFailureKind.INVALID_CONFIGURATION);
                    assertThat(failure.getMessage()).doesNotContain("one.example.invalid", "reader");
                });
    }

    @Test
    void startupTolerantRegistryDefersSanitizedFailureAndNeverBuildsAPartialClient() {
        JenkinsProperties properties = new JenkinsProperties();
        properties.setControllers(Map.of("controller-one",
                configuration("https://one.example.invalid", "reader", "")));

        JenkinsControllerRegistry registry = JenkinsControllerRegistry.startupTolerant(
                WebClient.builder(), properties);

        assertThat(registry.size()).isZero();
        assertThatThrownBy(() -> registry.resolve(Optional.empty()))
                .isInstanceOfSatisfying(JenkinsConnectorException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(JenkinsFailureKind.INVALID_CONFIGURATION);
                    assertThat(failure.getMessage())
                            .doesNotContain("controller-one", "one.example.invalid", "reader");
                });
    }

    private static JenkinsProperties.Controller configuration(String baseUrl, String username, String token) {
        JenkinsProperties.Controller configuration = new JenkinsProperties.Controller();
        configuration.setBaseUrl(baseUrl);
        configuration.setUsername(username);
        configuration.setToken(token);
        return configuration;
    }
}
