package com.jmopsagent.config.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class HttpsEndpointProbeTest {

    @Test
    void sendsOnlyACredentialFreeBoundedHeadRequest() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpsEndpointProbe probe = new HttpsEndpointProbe(captured::set);

        EndpointProbe.Outcome outcome = probe.probe("https://service.example.invalid/health");

        assertThat(outcome).isEqualTo(EndpointProbe.Outcome.REACHABLE);
        HttpRequest request = captured.get();
        assertThat(request.method()).isEqualTo("HEAD");
        assertThat(request.timeout()).contains(Duration.ofSeconds(5));
        assertThat(request.headers().map()).isEmpty();
        assertThat(request.uri()).hasToString("https://service.example.invalid/health");
    }

    @Test
    void productionTransportNeverFollowsRedirects() {
        assertThat(new JdkCredentialFreeHeadTransport().redirectPolicy())
                .isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test
    void rejectsInvalidOrUnsafeEndpointsWithoutCallingTheTransport() {
        AtomicInteger calls = new AtomicInteger();
        HttpsEndpointProbe probe = new HttpsEndpointProbe(request -> calls.incrementAndGet());

        List<String> endpoints = List.of(
                "http://service.example.invalid",
                "https:" + "//reader:credential@service.example.invalid",
                "https://service.example.invalid/path?credential=value",
                "https://service.example.invalid/path#fragment",
                "https://service.example.invalid:0",
                "not a URI");

        assertThat(endpoints).allSatisfy(endpoint ->
                assertThat(probe.probe(endpoint)).isEqualTo(EndpointProbe.Outcome.INVALID_ENDPOINT));
        assertThat(calls).hasValue(0);
    }

    @Test
    void distinguishesCertificateFailureTimeoutAndUnreachableOutcomes() {
        HttpsEndpointProbe certificateFailure = new HttpsEndpointProbe(request -> {
            throw new IOException("transport detail", new SSLHandshakeException("certificate detail"));
        });
        HttpsEndpointProbe timeout = new HttpsEndpointProbe(request -> {
            throw new HttpTimeoutException("timeout detail");
        });
        HttpsEndpointProbe unreachable = new HttpsEndpointProbe(request -> {
            throw new ConnectException("network detail");
        });

        assertThat(certificateFailure.probe("https://service.example.invalid"))
                .isEqualTo(EndpointProbe.Outcome.TLS_CERTIFICATE_FAILURE);
        assertThat(timeout.probe("https://service.example.invalid"))
                .isEqualTo(EndpointProbe.Outcome.TIMEOUT);
        assertThat(unreachable.probe("https://service.example.invalid"))
                .isEqualTo(EndpointProbe.Outcome.UNREACHABLE);
    }
}
