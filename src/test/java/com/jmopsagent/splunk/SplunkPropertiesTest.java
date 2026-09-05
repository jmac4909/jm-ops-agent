package com.jmopsagent.splunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SplunkPropertiesTest {

    @Test
    void defersRawRequestTimeoutParsingAndRejectsInvalidValuesWithoutRetainingTheirDetails() {
        String rawInvalidValue = "not-a-duration-with-sensitive-detail";
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "jmops.integrations.splunk.request-timeout", rawInvalidValue));

        SplunkProperties properties = new Binder(source)
                .bind("jmops.integrations.splunk", Bindable.of(SplunkProperties.class))
                .orElseThrow(() -> new AssertionError("Splunk properties did not bind"));

        assertThat(properties.getRequestTimeout()).isEqualTo(rawInvalidValue);
        assertThat(properties.hasValidRequestTimeout()).isFalse();
        assertThat(properties.toString()).doesNotContain(rawInvalidValue);
        assertThatThrownBy(properties::validatedRequestTimeout)
                .isInstanceOfSatisfying(IllegalArgumentException.class, failure -> {
                    assertThat(failure)
                            .hasMessage("Splunk request timeout must be greater than zero and at most two minutes")
                            .hasNoCause();
                    assertThat(failure.getMessage()).doesNotContain(rawInvalidValue);
                });

        SplunkProperties defaults = new SplunkProperties();
        assertThat(defaults.validatedRequestTimeout()).isEqualTo(Duration.ofSeconds(45));
        defaults.setRequestTimeout("PT1M");
        assertThat(defaults.validatedRequestTimeout()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void enforcesTheRequestTimeoutRangeAfterParsing() {
        SplunkProperties properties = new SplunkProperties();

        properties.setRequestTimeout("0s");
        assertThat(properties.hasValidRequestTimeout()).isFalse();
        properties.setRequestTimeout("121s");
        assertThat(properties.hasValidRequestTimeout()).isFalse();
        properties.setRequestTimeout("120s");
        assertThat(properties.validatedRequestTimeout()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void bearerTokenModeIsPreferredAndRejectsSessionCredentials() {
        SplunkProperties properties = new SplunkProperties();
        properties.setToken("synthetic-api-token");

        SplunkProperties.Credentials credentials = properties.validatedCredentials();

        assertThat(credentials.mode()).isEqualTo(SplunkAuthMode.BEARER_TOKEN);
        assertThat(credentials.present()).isTrue();
        assertThat(properties.toString()).contains("credentials=[REDACTED]")
                .doesNotContain("synthetic-api-token");

        properties.setSessionCookie(completeCookie());
        assertThatThrownBy(properties::validatedCredentials)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be configured");
    }

    @Test
    void sessionKeyModeUsesTheTokenSlotWithoutCookieCredentials() {
        SplunkProperties properties = new SplunkProperties();
        properties.setAuthMode(SplunkAuthMode.SESSION_KEY);
        properties.setToken("synthetic-session-key");

        assertThat(properties.validatedCredentials().mode()).isEqualTo(SplunkAuthMode.SESSION_KEY);
        assertThat(properties.validatedCredentials().present()).isTrue();
    }

    @Test
    void sessionModeRequiresCompleteMutuallyExclusiveCredentialSet() {
        SplunkProperties properties = new SplunkProperties();
        properties.setAuthMode(SplunkAuthMode.SESSION_CSRF);
        properties.setSessionCookie(completeCookie());

        assertThatThrownBy(properties::validatedCredentials)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a complete cookie set");

        properties.setFormKey("synthetic^form^key");
        assertThat(properties.validatedCredentials().present()).isTrue();

        properties.setToken("also-present");
        assertThatThrownBy(properties::validatedCredentials)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be configured");
    }

    @Test
    void rejectsIncompleteMalformedControlCharacterAndOversizedCredentials() {
        SplunkProperties incomplete = sessionProperties("splunkd_9000=a; session_id_9000=b", "form-key");
        assertThatThrownBy(incomplete::validatedCredentials).hasMessageContaining("incomplete");

        SplunkProperties malformed = sessionProperties(completeCookie() + "; broken", "form-key");
        assertThatThrownBy(malformed::validatedCredentials).hasMessageContaining("malformed");

        SplunkProperties control = new SplunkProperties();
        control.setToken("token\r\ninjected-header: value");
        assertThatThrownBy(control::validatedCredentials).hasMessageContaining("control character");

        SplunkProperties oversized = new SplunkProperties();
        oversized.setToken("x".repeat(8_193));
        assertThatThrownBy(oversized::validatedCredentials).hasMessageContaining("too large");
    }

    @Test
    void validatesDeclarativeProfilesAndRejectsExecutableFragments() {
        SplunkFieldProfile profile = new SplunkFieldProfile();
        profile.setName("platform-json");
        profile.setSourcetype("platform:log");
        profile.setFields(Map.of("tracking-id", List.of("msg.traceId"), "message", List.of("msg.detail")));
        SplunkProperties properties = new SplunkProperties();
        properties.setFieldProfiles(List.of(profile));

        assertThat(properties.validatedFieldProfiles()).singleElement().satisfies(validated ->
                assertThat(validated.fields()).containsKeys(
                        SplunkCanonicalField.TRACKING_ID, SplunkCanonicalField.MESSAGE));

        profile.setFields(Map.of("message", List.of("msg.detail | delete")));
        assertThatThrownBy(properties::validatedFieldProfiles)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported characters");
    }

    @Test
    void supportsFixedTextExtractionAndAlternateNestedTrackingFieldWithoutConfigurableSpl() {
        SplunkFieldProfile plainText = new SplunkFieldProfile();
        plainText.setName("prefixed-text");
        plainText.setSourcetype("cf:logmessage");
        plainText.setTrackingIdExtraction(SplunkTrackingIdExtraction.PREFIXED_TEXT);
        plainText.setFields(Map.of("message", List.of("message")));

        SplunkFieldProfile alternateJson = new SplunkFieldProfile();
        alternateJson.setName("alternate-json");
        alternateJson.setSourcetype("cf:logmessage");
        alternateJson.setFields(Map.of("tracking-id", List.of("msg.trackingId"),
                "message", List.of("msg.detail")));

        SplunkProperties properties = new SplunkProperties();
        properties.setFieldProfiles(List.of(plainText, alternateJson));

        assertThat(properties.validatedFieldProfiles()).extracting(SplunkFieldProfile.Validated::name)
                .containsExactly("prefixed-text", "alternate-json");
        assertThat(properties.validatedFieldProfiles().getFirst().trackingIdExtraction())
                .isEqualTo(SplunkTrackingIdExtraction.PREFIXED_TEXT);
        assertThat(properties.validatedFieldProfiles().get(1).fields().get(SplunkCanonicalField.TRACKING_ID))
                .containsExactly("msg.trackingId");
    }

    @Test
    void bindsSeparateGatewayIndexFamiliesWithoutEmbeddingConcreteIndexes() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "jmops.integrations.splunk.dev-gateway-indexes", "gateway_dev",
                "jmops.integrations.splunk.test-gateway-indexes", "gateway_test"));

        SplunkProperties properties = new Binder(source)
                .bind("jmops.integrations.splunk", Bindable.of(SplunkProperties.class))
                .orElseThrow(() -> new AssertionError("Splunk properties did not bind"));

        assertThat(properties.getDevGatewayIndexes()).isEqualTo("gateway_dev");
        assertThat(properties.getTestGatewayIndexes()).isEqualTo("gateway_test");
    }

    private static SplunkProperties sessionProperties(String cookie, String formKey) {
        SplunkProperties properties = new SplunkProperties();
        properties.setAuthMode(SplunkAuthMode.SESSION_CSRF);
        properties.setSessionCookie(cookie);
        properties.setFormKey(formKey);
        return properties;
    }

    static String completeCookie() {
        return "splunkd_9000=alpha^one; session_id_9000=beta^two; "
                + "splunkweb_csrf_token_9000=gamma^three";
    }
}
