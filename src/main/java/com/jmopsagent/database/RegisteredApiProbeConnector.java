package com.jmopsagent.database;

import com.jmopsagent.connector.*;
import com.jmopsagent.registry.ServiceDefinition;
import com.jmopsagent.registry.ServiceRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit operator-approved GET fixtures only; model/log content never supplies endpoints or inputs. */
@Component
@Profile("local-live")
public final class RegisteredApiProbeConnector implements DependencyConnector {
    private static final int MAX_BYTES = 65_536;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ServiceRegistry registry;
    private final WebClient client;

    public RegisteredApiProbeConnector(ServiceRegistry registry, WebClient.Builder builder) {
        this.registry = registry;
        this.client = builder.clone().defaultHeaders(HttpHeaders::clear)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().followRedirect(false)))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_BYTES)).build();
    }

    @Override
    public boolean supports(DependencyType type) { return type == DependencyType.DOWNSTREAM_API; }

    @Override
    public List<ConnectorEvidence> inspect(String service, Environment environment, DependencyType type, EvidenceQuery query) {
        String safeService = ConnectorInputValidator.service(service);
        if (environment == null) throw new IllegalArgumentException("Environment is required");
        if (!supports(type)) return List.of();
        var definition = registry.resolve(safeService);
        if (definition.isEmpty() || !"true".equalsIgnoreCase(setting(definition.get(), "enabled", environment))) return List.of();
        List<ConnectorEvidence> results = new ArrayList<>();
        try {
            ServiceDefinition config = definition.get();
            URI base = URI.create(ConnectorEndpointValidator.optionalHttpsBaseUrl(
                    setting(config, "baseUrl", environment), "API probe"));
            if (base.getHost() == null || (base.getPath() != null && !base.getPath().isEmpty() && !base.getPath().equals("/")))
                throw new IllegalArgumentException("API probe requires an HTTPS origin");
            List<String> fields = config.attributeValues("apiProbes.responseFields");
            if (fields.size() > 20 || fields.stream().anyMatch(field -> !field.matches("[A-Za-z][A-Za-z0-9_.]{0,119}")))
                throw new IllegalArgumentException("Invalid response field selection");
            // Validate every fixture before issuing any requests.
            List<Fixture> fixtures = new ArrayList<>();
            for (String name : List.of("health", "knownGood", "knownBad")) {
                String path = setting(config, name + "Path", environment);
                if (path.isBlank()) continue;
                if (!path.matches("/[A-Za-z0-9/_.?=&:@,-]*") || path.startsWith("//") || path.contains(".."))
                    throw new IllegalArgumentException("Invalid registered probe path");
                URI target = base.resolve(path);
                fixtures.add(new Fixture(name, target));
            }
            String tokenEnv = setting(config, "bearerTokenEnv", environment);
            if (!tokenEnv.isBlank() && !tokenEnv.matches("[A-Z][A-Z0-9_]{0,79}"))
                throw new IllegalArgumentException("Invalid token environment reference");
            String token = tokenEnv.isBlank() ? "" : System.getenv(tokenEnv);
            if (!tokenEnv.isBlank() && (token == null || token.isBlank()))
                throw new IllegalArgumentException("Probe credentials unavailable");
            for (Fixture fixture : fixtures.stream().limit(query.maxResults()).toList()) {
                String outcome;
                try {
                    var request = client.get().uri(fixture.uri());
                    if (token != null && !token.isBlank()) request.headers(headers -> headers.setBearerAuth(token));
                    outcome = request.exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        if (status >= 300 && status < 400) return response.releaseBody()
                                .thenReturn("HTTP " + status + "; redirect rejected");
                        return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                                .map(bytes -> describe(status, bytes, fields));
                    }).block(TIMEOUT);
                } catch (RuntimeException ignored) {
                    outcome = "Probe unavailable (timeout, transport, response limit, or unsupported JSON); no response retained";
                }
                String bounded = outcome == null ? "Probe unavailable" : outcome.substring(0,
                        Math.min(outcome.length(), query.maxContentCharacters() / Math.max(1, fixtures.size())));
                results.add(evidence(safeService, environment, fixture.name(), bounded));
            }
        } catch (IllegalArgumentException ignored) {
            return List.of(evidence(safeService, environment, "configuration", "Registered API probes unavailable: invalid or incomplete configuration"));
        }
        return List.copyOf(results);
    }

    private static String describe(int status, byte[] bytes, List<String> fields) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Response limit");
        StringBuilder result = new StringBuilder("HTTP " + status);
        if (bytes.length == 0) return result.append("; empty response").toString();
        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (RuntimeException ignored) {
            return result.append("; response was not valid JSON; response fields unavailable").toString();
        }
        if (root == null) return result.append("; empty JSON response").toString();
        for (String field : fields) {
            JsonNode value = root;
            for (String part : field.split("\\.")) {
                value = value.isArray() && part.matches("[0-9]{1,4}")
                        ? value.path(Integer.parseInt(part)) : value.path(part);
            }
            result.append('\n').append(field).append(": ");
            if (value.isMissingNode()) result.append("missing");
            else if (value.isNull()) result.append("null");
            else if (value.isArray() || value.isObject()) result.append(value.isArray() ? "array" : "object")
                    .append(" size=").append(value.size());
            else result.append("present (").append(value.getNodeType()).append(')');
        }
        return result.toString();
    }

    private static String setting(ServiceDefinition definition, String key, Environment environment) {
        // Environment-specific configuration is mandatory for endpoints and fixture inputs.
        return definition.attributeValue("apiProbes." + key + "." + environment.name()).orElse("");
    }

    private static ConnectorEvidence evidence(String service, Environment environment, String fixture, String content) {
        return new ConnectorEvidence("api-probe-" + service + "-" + fixture, EvidenceSource.SERVICE_REGISTRY,
                EvidenceType.DEPENDENCY_HEALTH, Instant.now(), service, environment,
                "Registered GET probe: " + fixture, content, null,
                Map.of("fixture", fixture, "readOnly", "true", "responseValuesRetained", "false"), 0.8);
    }

    private record Fixture(String name, URI uri) { }
}
