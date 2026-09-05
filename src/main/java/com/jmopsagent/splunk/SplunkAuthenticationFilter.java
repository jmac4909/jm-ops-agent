package com.jmopsagent.splunk;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/** Attaches credentials only after an exact-origin check. */
final class SplunkAuthenticationFilter implements ExchangeFilterFunction {
    private final URI allowedOrigin;
    private final SplunkProperties.Credentials credentials;

    SplunkAuthenticationFilter(URI baseUri, SplunkProperties.Credentials credentials) {
        this.allowedOrigin = baseUri;
        this.credentials = credentials;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        if (!sameOrigin(allowedOrigin, request.url())) {
            return Mono.error(new IllegalStateException("Splunk request origin is not allowed"));
        }
        ClientRequest.Builder authenticated = ClientRequest.from(request);
        if (credentials.mode() == SplunkAuthMode.BEARER_TOKEN) {
            authenticated.header(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.token());
        } else if (credentials.mode() == SplunkAuthMode.SESSION_KEY) {
            authenticated.header(HttpHeaders.AUTHORIZATION, "Splunk " + credentials.token());
        } else {
            // Use the raw Cookie header so legal cookie characters are not parsed or re-encoded.
            authenticated.header(HttpHeaders.COOKIE, credentials.cookie());
            authenticated.header("X-Splunk-Form-Key", credentials.formKey());
            authenticated.header("X-Requested-With", "XMLHttpRequest");
        }
        return next.exchange(authenticated.build());
    }

    static boolean sameOrigin(URI expected, URI actual) {
        if (expected == null || actual == null) return false;
        return expected.getScheme() != null && actual.getScheme() != null
                && expected.getScheme().equalsIgnoreCase(actual.getScheme())
                && expected.getHost() != null && actual.getHost() != null
                && expected.getHost().toLowerCase(Locale.ROOT).equals(actual.getHost().toLowerCase(Locale.ROOT))
                && effectivePort(expected) == effectivePort(actual);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
