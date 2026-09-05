package com.jmopsagent.connector;

import java.util.Locale;

/** The complete environment allowlist for the MVP. */
public enum Environment {
    DEV,
    TEST;

    public static Environment parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Environment is required; allowed values are DEV and TEST");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Environment '" + value + "' is not allowed; allowed values are DEV and TEST");
        }
    }
}
