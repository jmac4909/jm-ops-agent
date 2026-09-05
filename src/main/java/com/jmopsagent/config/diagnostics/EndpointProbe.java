package com.jmopsagent.config.diagnostics;

/**
 * Credential-free connectivity boundary used by startup diagnostics.
 *
 * <p>The deliberately small result type prevents endpoint values and transport exception details
 * from crossing into user-visible diagnostic results.</p>
 */
public interface EndpointProbe {
    Outcome probe(String endpoint);

    enum Outcome {
        REACHABLE,
        INVALID_ENDPOINT,
        TLS_CERTIFICATE_FAILURE,
        TIMEOUT,
        UNREACHABLE
    }
}
