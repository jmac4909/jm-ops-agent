package com.jmopsagent.config.diagnostics;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Objects;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.springframework.stereotype.Component;

/** Performs a bounded, credential-free HTTPS HEAD request without following redirects. */
@Component
final class HttpsEndpointProbe implements EndpointProbe {
    static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HeadRequestTransport transport;

    HttpsEndpointProbe() {
        this(new JdkCredentialFreeHeadTransport());
    }

    HttpsEndpointProbe(HeadRequestTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public Outcome probe(String endpoint) {
        URI uri = validatedHttpsEndpoint(endpoint);
        if (uri == null) return Outcome.INVALID_ENDPOINT;

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
        } catch (IllegalArgumentException ignored) {
            return Outcome.INVALID_ENDPOINT;
        }

        try {
            transport.send(request);
            return Outcome.REACHABLE;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return Outcome.UNREACHABLE;
        } catch (IOException | RuntimeException failure) {
            return classify(failure);
        }
    }

    static Outcome classify(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SSLHandshakeException
                    || current instanceof SSLPeerUnverifiedException
                    || current instanceof CertificateException
                    || current instanceof CertPathValidatorException
                    || current instanceof CertPathBuilderException) {
                return Outcome.TLS_CERTIFICATE_FAILURE;
            }
            if (current instanceof HttpTimeoutException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return Outcome.TIMEOUT;
            }
            if (current instanceof ConnectException
                    || current instanceof NoRouteToHostException
                    || current instanceof UnknownHostException) {
                return Outcome.UNREACHABLE;
            }
            current = current.getCause();
        }
        return Outcome.UNREACHABLE;
    }

    private static URI validatedHttpsEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return null;
        try {
            URI uri = URI.create(endpoint.trim());
            int port = uri.getPort();
            if (!uri.isAbsolute()
                    || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || port < -1
                    || port == 0
                    || port > 65_535) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

@FunctionalInterface
interface HeadRequestTransport {
    void send(HttpRequest request) throws IOException, InterruptedException;
}

final class JdkCredentialFreeHeadTransport implements HeadRequestTransport {
    private final HttpClient client;

    JdkCredentialFreeHeadTransport() {
        this(HttpClient.newBuilder()
                .connectTimeout(HttpsEndpointProbe.TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    JdkCredentialFreeHeadTransport(HttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void send(HttpRequest request) throws IOException, InterruptedException {
        client.send(request, HttpResponse.BodyHandlers.discarding());
    }

    HttpClient.Redirect redirectPolicy() {
        return client.followRedirects();
    }
}
