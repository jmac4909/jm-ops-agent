package com.jmopsagent.connector;

import java.net.URI;

/** Validates credential-bearing REST endpoints before a client can attach authentication headers. */
public final class ConnectorEndpointValidator {
    private ConnectorEndpointValidator() {}

    public static String optionalHttpsBaseUrl(String value, String integration) {
        if (value == null || value.isBlank()) return "";
        try {
            URI uri = URI.create(value.trim());
            int port = uri.getPort();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || port == 0 || port > 65_535) {
                throw new IllegalArgumentException(integration + " base URL must be an HTTPS origin/path without credentials, query, or fragment");
            }
            String normalized = uri.toString();
            while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
            return normalized;
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith(integration + " base URL")) throw ex;
            // URI parser messages include their raw input. Do not retain that cause on a
            // configuration failure because startup frameworks may render the full cause chain.
            throw new IllegalArgumentException(integration + " base URL is invalid");
        }
    }
}
