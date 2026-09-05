package com.jmopsagent.splunk;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;

/** External-only configuration for the read-only Splunk integration. */
@ConfigurationProperties("jmops.integrations.splunk")
public class SplunkProperties {
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration MAX_REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_REQUEST_TIMEOUT_CHARACTERS = 64;
    private static final int MAX_TOKEN_CHARACTERS = 8_192;
    private static final int MAX_COOKIE_CHARACTERS = 8_192;
    private static final int MAX_FORM_KEY_CHARACTERS = 4_096;
    private static final Pattern COOKIE_NAME = Pattern.compile("[!#$%&'*+.^_`|~A-Za-z0-9-]{1,128}");

    private String baseUrl = "";
    private SplunkAuthMode authMode = SplunkAuthMode.BEARER_TOKEN;
    private String token = "";
    private String sessionCookie = "";
    private String formKey = "";
    private String devIndexes = "";
    private String testIndexes = "";
    private String devGatewayIndexes = "";
    private String testGatewayIndexes = "";
    private String requestTimeout = "45s";
    private List<SplunkFieldProfile> fieldProfiles = new ArrayList<>();

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = valueOrEmpty(baseUrl); }
    public SplunkAuthMode getAuthMode() { return authMode; }
    public void setAuthMode(SplunkAuthMode authMode) { this.authMode = authMode; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = valueOrEmpty(token); }
    public String getSessionCookie() { return sessionCookie; }
    public void setSessionCookie(String sessionCookie) { this.sessionCookie = valueOrEmpty(sessionCookie); }
    public String getFormKey() { return formKey; }
    public void setFormKey(String formKey) { this.formKey = valueOrEmpty(formKey); }
    public String getDevIndexes() { return devIndexes; }
    public void setDevIndexes(String devIndexes) { this.devIndexes = valueOrEmpty(devIndexes); }
    public String getTestIndexes() { return testIndexes; }
    public void setTestIndexes(String testIndexes) { this.testIndexes = valueOrEmpty(testIndexes); }
    public String getDevGatewayIndexes() { return devGatewayIndexes; }
    public void setDevGatewayIndexes(String devGatewayIndexes) {
        this.devGatewayIndexes = valueOrEmpty(devGatewayIndexes);
    }
    public String getTestGatewayIndexes() { return testGatewayIndexes; }
    public void setTestGatewayIndexes(String testGatewayIndexes) {
        this.testGatewayIndexes = valueOrEmpty(testGatewayIndexes);
    }
    public String getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(String requestTimeout) { this.requestTimeout = valueOrEmpty(requestTimeout); }
    public List<SplunkFieldProfile> getFieldProfiles() { return List.copyOf(fieldProfiles); }
    public void setFieldProfiles(List<SplunkFieldProfile> fieldProfiles) {
        this.fieldProfiles = fieldProfiles == null ? new ArrayList<>() : new ArrayList<>(fieldProfiles);
    }

    List<SplunkFieldProfile.Validated> validatedFieldProfiles() {
        if (fieldProfiles.size() > 20) {
            throw new IllegalArgumentException("At most 20 Splunk field profiles are allowed");
        }
        List<SplunkFieldProfile.Validated> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (int index = 0; index < fieldProfiles.size(); index++) {
            SplunkFieldProfile profile = fieldProfiles.get(index);
            if (profile == null) throw new IllegalArgumentException("Splunk field profile cannot be null");
            SplunkFieldProfile.Validated validated = profile.validate(index);
            if (!names.add(validated.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate Splunk field profile name");
            }
            result.add(validated);
        }
        return List.copyOf(result);
    }

    /**
     * Parses the externally supplied timeout only after configuration binding has completed. Invalid
     * values fail with a fixed message and no cause so the raw setting cannot enter logs or diagnostics.
     */
    public Duration validatedRequestTimeout() {
        String raw = valueOrEmpty(requestTimeout).trim();
        if (raw.isEmpty() || raw.length() > MAX_REQUEST_TIMEOUT_CHARACTERS) {
            throw invalidRequestTimeout();
        }
        Duration parsed;
        try {
            parsed = DurationStyle.detectAndParse(raw);
        } catch (RuntimeException ignored) {
            throw invalidRequestTimeout();
        }
        if (parsed.isNegative() || parsed.isZero() || parsed.compareTo(MAX_REQUEST_TIMEOUT) > 0) {
            throw invalidRequestTimeout();
        }
        return parsed;
    }

    public boolean hasValidRequestTimeout() {
        try {
            validatedRequestTimeout();
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    Credentials validatedCredentials() {
        SplunkAuthMode mode = authMode == null ? SplunkAuthMode.BEARER_TOKEN : authMode;
        String safeToken = validateHeaderValue(token, MAX_TOKEN_CHARACTERS, "Splunk token");
        String safeCookie = validateHeaderValue(sessionCookie, MAX_COOKIE_CHARACTERS, "Splunk session cookie");
        String safeFormKey = validateHeaderValue(formKey, MAX_FORM_KEY_CHARACTERS, "Splunk form key");

        if (mode == SplunkAuthMode.BEARER_TOKEN || mode == SplunkAuthMode.SESSION_KEY) {
            if (!safeCookie.isBlank() || !safeFormKey.isBlank()) {
                throw new IllegalArgumentException("Cookie/CSRF credentials cannot be configured in a token-based mode");
            }
            return Credentials.token(mode, safeToken);
        }
        if (!safeToken.isBlank()) {
            throw new IllegalArgumentException("Token or session-key credentials cannot be configured in SESSION_CSRF mode");
        }
        if (safeCookie.isBlank() || safeFormKey.isBlank()) {
            throw new IllegalArgumentException("SESSION_CSRF mode requires a complete cookie set and form key");
        }
        validateRequiredCookies(safeCookie);
        return Credentials.session(safeCookie, safeFormKey);
    }

    @Override
    public String toString() {
        return "SplunkProperties{baseUrlConfigured=" + !baseUrl.isBlank()
                + ", authMode=" + authMode
                + ", credentials=[REDACTED], fieldProfiles=" + fieldProfiles.size() + "}";
    }

    private static String validateHeaderValue(String value, int maximum, String label) {
        String safe = valueOrEmpty(value).trim();
        if (safe.length() > maximum) throw new IllegalArgumentException(label + " is too large");
        for (int index = 0; index < safe.length(); index++) {
            char character = safe.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException(label + " contains a control character");
            }
        }
        return safe;
    }

    private static void validateRequiredCookies(String cookieHeader) {
        Set<String> names = new LinkedHashSet<>();
        Arrays.stream(cookieHeader.split(";", -1)).forEach(part -> {
            String candidate = part.trim();
            int separator = candidate.indexOf('=');
            if (separator < 1 || separator == candidate.length() - 1) {
                throw new IllegalArgumentException("Splunk session cookie set is malformed");
            }
            String name = candidate.substring(0, separator).trim();
            if (!COOKIE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Splunk session cookie name is invalid");
            }
            names.add(name.toLowerCase(Locale.ROOT));
        });
        if (names.stream().noneMatch(name -> name.startsWith("splunkd_"))
                || names.stream().noneMatch(name -> name.startsWith("session_id_"))
                || names.stream().noneMatch(name -> name.startsWith("splunkweb_csrf_token_"))) {
            throw new IllegalArgumentException("Splunk session cookie set is incomplete");
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static IllegalArgumentException invalidRequestTimeout() {
        return new IllegalArgumentException(
                "Splunk request timeout must be greater than zero and at most two minutes");
    }

    static final class Credentials {
        private final SplunkAuthMode mode;
        private final String token;
        private final String cookie;
        private final String formKey;

        private Credentials(SplunkAuthMode mode, String token, String cookie, String formKey) {
            this.mode = mode;
            this.token = token;
            this.cookie = cookie;
            this.formKey = formKey;
        }

        static Credentials token(SplunkAuthMode mode, String token) {
            if (mode != SplunkAuthMode.BEARER_TOKEN && mode != SplunkAuthMode.SESSION_KEY) {
                throw new IllegalArgumentException("Token credentials require an explicit token authentication mode");
            }
            return new Credentials(mode, token, "", "");
        }

        static Credentials session(String cookie, String formKey) {
            return new Credentials(SplunkAuthMode.SESSION_CSRF, "", cookie, formKey);
        }

        SplunkAuthMode mode() { return mode; }
        String token() { return token; }
        String cookie() { return cookie; }
        String formKey() { return formKey; }
        boolean present() { return mode == SplunkAuthMode.SESSION_CSRF ? !cookie.isBlank() : !token.isBlank(); }

        @Override
        public String toString() {
            return "SplunkCredentials{mode=" + mode + ", values=[REDACTED]}";
        }
    }
}
