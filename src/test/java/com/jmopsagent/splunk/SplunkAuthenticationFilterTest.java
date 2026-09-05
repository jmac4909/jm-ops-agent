package com.jmopsagent.splunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

class SplunkAuthenticationFilterTest {

    @Test
    void preservesFullSessionCookieAndCsrfHeadersWithoutReencoding() {
        SplunkProperties properties = new SplunkProperties();
        properties.setAuthMode(SplunkAuthMode.SESSION_CSRF);
        properties.setSessionCookie(SplunkPropertiesTest.completeCookie());
        properties.setFormKey("synthetic^form^key");
        SplunkAuthenticationFilter filter = new SplunkAuthenticationFilter(
                URI.create("https://logs.example.invalid:9443/root"), properties.validatedCredentials());
        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        filter.filter(request("https://logs.example.invalid:9443/services/search"), request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }).block();

        assertThat(captured.get().headers().getFirst(HttpHeaders.COOKIE))
                .isEqualTo(SplunkPropertiesTest.completeCookie());
        assertThat(captured.get().headers().getFirst("X-Splunk-Form-Key"))
                .isEqualTo("synthetic^form^key");
        assertThat(captured.get().headers().getFirst("X-Requested-With")).isEqualTo("XMLHttpRequest");
        assertThat(captured.get().headers().get(HttpHeaders.AUTHORIZATION)).isNull();
    }

    @Test
    void bearerTokenIsAttachedOnlyToExactOrigin() {
        SplunkProperties properties = new SplunkProperties();
        properties.setToken("synthetic-token");
        SplunkAuthenticationFilter filter = new SplunkAuthenticationFilter(
                URI.create("https://logs.example.invalid/root"), properties.validatedCredentials());
        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        filter.filter(request("https://logs.example.invalid/services/search"), request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }).block();

        assertThat(captured.get().headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer synthetic-token");
        assertThat(captured.get().headers().get(HttpHeaders.COOKIE)).isNull();

        Throwable rejected = org.assertj.core.api.Assertions.catchThrowable(() ->
                filter.filter(request("https://redirect.example.invalid/services/search"),
                        ignored -> Mono.just(ClientResponse.create(HttpStatus.OK).build())).block());
        assertThat(rejected)
                .hasMessage("Splunk request origin is not allowed")
                .hasStackTraceContaining("origin is not allowed");
        assertThat(rejected.toString()).doesNotContain("synthetic-token");
    }

    @Test
    void sessionKeyUsesTheExplicitSplunkAuthorizationScheme() {
        SplunkProperties properties = new SplunkProperties();
        properties.setAuthMode(SplunkAuthMode.SESSION_KEY);
        properties.setToken("synthetic-session-key");
        SplunkAuthenticationFilter filter = new SplunkAuthenticationFilter(
                URI.create("https://logs.example.invalid"), properties.validatedCredentials());
        AtomicReference<ClientRequest> captured = new AtomicReference<>();

        filter.filter(request("https://logs.example.invalid/services/search"), request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }).block();

        assertThat(captured.get().headers().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Splunk synthetic-session-key");
    }

    @Test
    void treatsDefaultHttpsPortAsTheSameOriginButRejectsSchemeOrPortChanges() {
        URI origin = URI.create("https://logs.example.invalid");

        assertThat(SplunkAuthenticationFilter.sameOrigin(origin,
                URI.create("https://logs.example.invalid:443/search"))).isTrue();
        assertThat(SplunkAuthenticationFilter.sameOrigin(origin,
                URI.create("http://logs.example.invalid/search"))).isFalse();
        assertThat(SplunkAuthenticationFilter.sameOrigin(origin,
                URI.create("https://logs.example.invalid:9443/search"))).isFalse();
    }

    @Test
    void reactorNettyTransmitsCaretCookieValuesWithoutReencoding() {
        AtomicReference<String> receivedCookie = new AtomicReference<>();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> {
                    receivedCookie.set(request.requestHeaders().get(HttpHeaders.COOKIE));
                    return response.sendString(Mono.just("{}"));
                }).bindNow();
        try {
            String baseUrl = "http://127.0.0.1:" + server.port();
            SplunkProperties properties = new SplunkProperties();
            properties.setAuthMode(SplunkAuthMode.SESSION_CSRF);
            properties.setSessionCookie(SplunkPropertiesTest.completeCookie());
            properties.setFormKey("synthetic^form^key");
            SplunkAuthenticationFilter filter = new SplunkAuthenticationFilter(
                    URI.create(baseUrl), properties.validatedCredentials());
            WebClient client = WebClient.builder().baseUrl(baseUrl).filter(filter)
                    .clientConnector(new ReactorClientHttpConnector(HttpClient.create()))
                    .build();

            client.post().uri("/read-only-search").retrieve().bodyToMono(String.class).block();

            assertThat(receivedCookie.get()).isEqualTo(SplunkPropertiesTest.completeCookie());
        } finally {
            server.disposeNow();
        }
    }

    private static ClientRequest request(String url) {
        return ClientRequest.create(HttpMethod.POST, URI.create(url)).build();
    }
}
