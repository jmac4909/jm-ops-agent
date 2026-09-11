package com.jmopsagent.database;

import com.jmopsagent.connector.*;
import com.jmopsagent.registry.YamlServiceRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;

class RegisteredApiProbeConnectorTest {
    private static final EvidenceQuery QUERY = EvidenceQuery.recent(Duration.ofMinutes(30), 10);

    @Test
    void comparesExplicitFixturesWithoutRetainingResponseValuesOrRequestIdentifiers() {
        List<ClientRequest> requests = new ArrayList<>();
        var connector = connector("/detail?id=good-fixture", "/detail?id=bad-fixture", WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body(request.url().getQuery().contains("good")
                            ? "{\"data\":{\"state\":\"ZZ\",\"items\":[{\"address\":\"private-address\"}]}}" : "{\"data\":{}}")
                    .build());
        }));
        var result = connector.inspect("sample-api", Environment.TEST, DependencyType.DOWNSTREAM_API, QUERY);
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().content()).contains("HTTP 200", "data.state: present (STRING)",
                "data.items.0.address: present (STRING)");
        assertThat(result.getLast().content()).contains("data.state: missing");
        assertThat(result.toString()).doesNotContain("private-address", "good-fixture", "bad-fixture", "\"MA\"");
        assertThat(requests).allSatisfy(request -> assertThat(request.method().name()).isEqualTo("GET"));
        assertThat(connector.inspect("sample-api", Environment.DEV, DependencyType.DOWNSTREAM_API, QUERY)).isEmpty();
        assertThat(connector.inspect("unknown", Environment.TEST, DependencyType.DOWNSTREAM_API, QUERY)).isEmpty();
        assertThat(requests).hasSize(2);
    }

    @Test
    void validatesEveryFixtureBeforeAnyCallAndRejectsOriginEscapes() {
        for (String bad : List.of("//other.example.invalid/detail", "/../detail", "/detail%2f..", "/detail#fragment")) {
            var requests = new ArrayList<ClientRequest>();
            var connector = connector("/detail", bad, WebClient.builder().exchangeFunction(request -> {
                requests.add(request);
                return Mono.just(ClientResponse.create(HttpStatus.OK).body("{}").build());
            }));
            var result = connector.inspect("sample-api", Environment.TEST, DependencyType.DOWNSTREAM_API, QUERY);
            assertThat(requests).isEmpty();
            assertThat(result.getFirst().content()).contains("invalid or incomplete configuration");
        }
    }

    @Test
    void cancelsAnUnresponsiveRequestAtItsTimeout() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var connector = connector("/detail", "", WebClient.builder().exchangeFunction(request ->
                Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true))));
        var result = connector.inspect("sample-api", Environment.TEST, DependencyType.DOWNSTREAM_API, QUERY);
        assertThat(result.getFirst().content()).contains("Probe unavailable", "timeout");
        assertThat(cancelled).isTrue();
    }

    @Test
    void rejectsRedirectsUnsupportedBodiesAndOversizedResponsesWithoutRetainingThem() {
        for (String kind : List.of("redirect", "invalid", "large", "transport")) {
            var requests = new ArrayList<ClientRequest>();
            var connector = connector("/detail", "", WebClient.builder().exchangeFunction(request -> {
                requests.add(request);
                if (kind.equals("transport")) return Mono.error(new IllegalStateException("sensitive-error-detail"));
                return Mono.just(ClientResponse.create(kind.equals("redirect") ? HttpStatus.FOUND : HttpStatus.SERVICE_UNAVAILABLE)
                        .header("Location", "https://unregistered.example.invalid/")
                        .body(kind.equals("large") ? "x".repeat(70_000) : "sensitive-invalid-body").build());
            }));
            var result = connector.inspect("sample-api", Environment.TEST, DependencyType.DOWNSTREAM_API, QUERY);
            assertThat(requests).hasSize(1);
            assertThat(result.getFirst().content()).contains(switch (kind) {
                case "redirect" -> "redirect rejected";
                case "invalid" -> "HTTP 503; response was not valid JSON";
                default -> "Probe unavailable";
            });
            assertThat(result.toString()).doesNotContain("sensitive-invalid", "sensitive-error", "unregistered");
        }
    }

    private static RegisteredApiProbeConnector connector(String good, String bad, WebClient.Builder builder) {
        String yaml = """
                services:
                  - service: sample-api
                    apiProbes:
                      enabled:
                        TEST: true
                      baseUrl:
                        TEST: https://api.example.invalid
                      knownGoodPath:
                        TEST: '%s'
                      knownBadPath:
                        TEST: '%s'
                      responseFields: [data.state, data.items.0.address]
                """.formatted(good, bad);
        var registry = new YamlServiceRegistry(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        registry.load();
        return new RegisteredApiProbeConnector(registry, builder);
    }
}
