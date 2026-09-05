package com.jmopsagent.jenkins;

import com.jmopsagent.connector.ConnectorEndpointValidator;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

/** Builds one independently authenticated client for each configured Jenkins controller. */
final class JenkinsControllerRegistry {
    private static final Pattern CONTROLLER_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final String LEGACY_CONTROLLER_ID = "default";

    private final Map<String, JenkinsControllerClient> clients;
    private final boolean invalidConfiguration;

    JenkinsControllerRegistry(WebClient.Builder prototype, JenkinsProperties properties) {
        this(prototype, configurations(properties), true);
    }

    /** Package-private HTTP allowance exists only for local in-process contract tests. */
    JenkinsControllerRegistry(WebClient.Builder prototype, Map<String, JenkinsProperties.Controller> configurations,
            boolean requireHttps) {
        Objects.requireNonNull(prototype, "prototype");
        Map<String, JenkinsControllerClient> configured = new LinkedHashMap<>();
        configurations.forEach((rawId, configuration) -> {
            String id = normalizeId(rawId);
            if (configured.containsKey(id)) {
                throw configurationFailure("Duplicate Jenkins controller identifier");
            }
            if (configuration == null || configuration.getBaseUrl().isBlank()
                    || configuration.getUsername().isBlank() || configuration.getToken().isBlank()) {
                throw configurationFailure("A Jenkins controller configuration is incomplete");
            }
            String baseUrl = validatedBaseUrl(configuration.getBaseUrl(), requireHttps);
            WebClient client;
            try {
                client = prototype.clone()
                        .baseUrl(baseUrl)
                        .defaultHeaders(headers -> {
                            headers.remove(HttpHeaders.AUTHORIZATION);
                            headers.setBasicAuth(configuration.getUsername(), configuration.getToken());
                        })
                        .build();
            } catch (RuntimeException ignored) {
                throw configurationFailure("A Jenkins controller configuration is invalid");
            }
            configured.put(id, new JenkinsControllerClient(id, URI.create(baseUrl), client));
        });
        this.clients = Map.copyOf(configured);
        this.invalidConfiguration = false;
    }

    private JenkinsControllerRegistry(boolean invalidConfiguration) {
        this.clients = Map.of();
        this.invalidConfiguration = invalidConfiguration;
    }

    /**
     * Keeps a malformed external configuration from aborting application startup. The strict
     * constructors remain available for tests and explicit validation; requests still fail closed.
     */
    static JenkinsControllerRegistry startupTolerant(WebClient.Builder prototype, JenkinsProperties properties) {
        try {
            return new JenkinsControllerRegistry(prototype, properties);
        } catch (JenkinsConnectorException failure) {
            if (failure.kind() != JenkinsFailureKind.INVALID_CONFIGURATION) throw failure;
            return new JenkinsControllerRegistry(true);
        }
    }

    JenkinsControllerClient resolve(Optional<String> requestedId) {
        if (invalidConfiguration) {
            throw configurationFailure("Jenkins connector configuration is invalid");
        }
        if (requestedId.isPresent()) {
            String id = normalizeId(requestedId.get());
            JenkinsControllerClient client = clients.get(id);
            if (client == null) {
                throw new JenkinsConnectorException(JenkinsFailureKind.UNKNOWN_CONTROLLER,
                        "The service references an unknown Jenkins controller");
            }
            return client;
        }
        if (clients.isEmpty()) {
            throw new JenkinsConnectorException(JenkinsFailureKind.UNCONFIGURED,
                    "No Jenkins controller is configured");
        }
        if (clients.size() != 1) {
            throw new JenkinsConnectorException(JenkinsFailureKind.AMBIGUOUS_CONTROLLER,
                    "The service must select a Jenkins controller when multiple controllers are configured");
        }
        return clients.values().iterator().next();
    }

    int size() {
        return clients.size();
    }

    private static Map<String, JenkinsProperties.Controller> configurations(JenkinsProperties properties) {
        Objects.requireNonNull(properties, "properties");
        Map<String, JenkinsProperties.Controller> configured = new LinkedHashMap<>(properties.getControllers());
        if (!configured.isEmpty() && properties.hasLegacyValues()) {
            throw configurationFailure("Legacy and multi-controller Jenkins settings cannot be combined");
        }
        if (configured.isEmpty() && properties.hasLegacyValues()) {
            JenkinsProperties.Controller legacy = new JenkinsProperties.Controller();
            legacy.setBaseUrl(properties.getBaseUrl());
            legacy.setUsername(properties.getUsername());
            legacy.setToken(properties.getToken());
            configured.put(LEGACY_CONTROLLER_ID, legacy);
        }
        return configured;
    }

    private static String normalizeId(String value) {
        if (value == null || value.isBlank()) {
            throw configurationFailure("Jenkins controller identifier is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!CONTROLLER_ID.matcher(normalized).matches()) {
            throw configurationFailure("Jenkins controller identifier is invalid");
        }
        return normalized;
    }

    private static String localTestBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || !uri.isAbsolute()
                    || !("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()))
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw configurationFailure("Local Jenkins test endpoint is invalid");
            }
            String normalized = uri.toString();
            while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw configurationFailure("Local Jenkins test endpoint is invalid");
        }
    }

    private static String validatedBaseUrl(String value, boolean requireHttps) {
        try {
            return requireHttps
                    ? ConnectorEndpointValidator.optionalHttpsBaseUrl(value, "Jenkins")
                    : localTestBaseUrl(value);
        } catch (JenkinsConnectorException ex) {
            throw ex;
        } catch (RuntimeException ignored) {
            throw configurationFailure("A Jenkins controller endpoint is invalid");
        }
    }

    private static JenkinsConnectorException configurationFailure(String message) {
        return new JenkinsConnectorException(JenkinsFailureKind.INVALID_CONFIGURATION, message);
    }
}

record JenkinsControllerClient(String id, URI baseUri, WebClient client) {
    JenkinsControllerClient {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(client, "client");
    }

    URI safeResultUri(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI candidate = baseUri.resolve(value.trim());
            if (!sameOrigin(baseUri, candidate) || candidate.getUserInfo() != null
                    || candidate.getQuery() != null || candidate.getFragment() != null
                    || !underConfiguredPath(baseUri, candidate)) {
                return null;
            }
            return candidate;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean sameOrigin(URI expected, URI candidate) {
        return expected.getScheme().equalsIgnoreCase(candidate.getScheme())
                && expected.getHost().equalsIgnoreCase(candidate.getHost())
                && effectivePort(expected) == effectivePort(candidate);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean underConfiguredPath(URI expected, URI candidate) {
        String root = Optional.ofNullable(expected.getPath()).orElse("");
        if (root.isBlank() || "/".equals(root)) return true;
        String normalizedRoot = root.endsWith("/") ? root : root + "/";
        String path = Optional.ofNullable(candidate.getPath()).orElse("");
        return path.equals(root) || path.startsWith(normalizedRoot);
    }
}
