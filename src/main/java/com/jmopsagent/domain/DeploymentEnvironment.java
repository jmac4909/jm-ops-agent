package com.jmopsagent.domain;

import java.util.Locale;

/** The complete MVP environment allow-list. */
public enum DeploymentEnvironment {
    DEV,
    TEST;

    public static DeploymentEnvironment parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Environment is required and must be DEV or TEST");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported environment '" + value + "'. Only DEV and TEST are allowed", exception);
        }
    }
}
