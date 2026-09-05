package com.jmopsagent.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class EnvironmentAndInputValidatorTest {
    @Test
    void onlyDevAndTestCanBeParsed() {
        assertThat(Environment.parse("dev")).isEqualTo(Environment.DEV);
        assertThat(Environment.parse(" TEST ")).isEqualTo(Environment.TEST);
        assertThatIllegalArgumentException().isThrownBy(() -> Environment.parse("STAGE"));
        assertThatIllegalArgumentException().isThrownBy(() -> Environment.parse("PROD"));
    }

    @Test
    void rejectsCommandAndSearchInjectionInputs() {
        assertThatIllegalArgumentException().isThrownBy(() -> ConnectorInputValidator.service("orders; delete pods"));
        assertThatIllegalArgumentException().isThrownBy(() -> ConnectorInputValidator.trackingId("ABC\" | delete"));
        assertThatIllegalArgumentException().isThrownBy(() -> ConnectorInputValidator.repositoryPath("../../secret"));
        assertThatIllegalArgumentException().isThrownBy(() -> ConnectorInputValidator.revision("--help"));
    }

    @Test
    void credentialBearingConnectorEndpointsRequireHttps() {
        assertThat(ConnectorEndpointValidator.optionalHttpsBaseUrl("https://tools.example.test/", "Test"))
                .isEqualTo("https://tools.example.test");
        assertThatIllegalArgumentException().isThrownBy(() ->
                ConnectorEndpointValidator.optionalHttpsBaseUrl("http://tools.example.test", "Test"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ConnectorEndpointValidator.optionalHttpsBaseUrl(
                        "https" + "://user:secret@tools.example.test", "Test"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ConnectorEndpointValidator.optionalHttpsBaseUrl("https://tools.example.test:0", "Test"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ConnectorEndpointValidator.optionalHttpsBaseUrl("https://tools.example.test:65536", "Test"));
    }

    @Test
    void invalidEndpointFailureDoesNotRetainTheRawUriParserCause() {
        String rawEndpoint = "https" + "://invalid host.example.invalid/private-marker";

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ConnectorEndpointValidator.optionalHttpsBaseUrl(rawEndpoint, "Test"))
                .satisfies(failure -> {
                    assertThat(failure).hasMessage("Test base URL is invalid").hasNoCause();
                    assertThat(failure.getMessage())
                            .doesNotContain("invalid host", "private-marker", rawEndpoint);
                });
    }
}
