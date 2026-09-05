package com.jmopsagent.connector;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ConnectorInputValidator {
    private static final Pattern SERVICE = Pattern.compile("[a-z0-9](?:[-a-z0-9.]{0,126}[a-z0-9])?");
    private static final Pattern KUBERNETES_NAME = Pattern.compile("[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?");
    private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._/-]{0,199})");
    private static final Pattern TRACKING_ID = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._:@/-]{0,127})");

    private ConnectorInputValidator() {
    }

    public static String service(String value) {
        String normalized = require(value, "service").toLowerCase(Locale.ROOT);
        if (!SERVICE.matcher(normalized).matches() || normalized.contains("..")) {
            throw new IllegalArgumentException("Service contains unsupported characters");
        }
        return normalized;
    }

    public static String kubernetesName(String value, String label) {
        String normalized = require(value, label).toLowerCase(Locale.ROOT);
        if (!KUBERNETES_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException(label + " is not a valid Kubernetes name");
        }
        return normalized;
    }

    public static String trackingId(String value) {
        String normalized = require(value, "trackingId");
        if (!TRACKING_ID.matcher(normalized).matches() || normalized.contains("..")) {
            throw new IllegalArgumentException("Tracking ID contains unsupported characters");
        }
        return normalized;
    }

    public static String revision(String value) {
        String normalized = require(value, "revision");
        if (!REVISION.matcher(normalized).matches() || normalized.contains("..") || normalized.startsWith("/")) {
            throw new IllegalArgumentException("Revision contains unsupported characters");
        }
        return normalized;
    }

    public static String repositoryPath(String value) {
        String normalized = require(value, "path").replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.contains("/..")
                || normalized.indexOf('\0') >= 0 || normalized.length() > 500) {
            throw new IllegalArgumentException("Repository path is not allowed");
        }
        return normalized;
    }

    public static int boundedLimit(int value, int maximum) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException("Result limit must be between 1 and " + maximum);
        }
        return value;
    }

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
