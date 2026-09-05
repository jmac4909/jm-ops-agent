package com.jmopsagent.connector.process;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An argv-based process request. No shell is involved. */
public record ProcessRequest(
        String executable,
        List<String> arguments,
        Duration timeout,
        int maxOutputCharacters,
        String stdinContent,
        ProcessEnvironmentPolicy environmentPolicy,
        Map<String, String> environmentOverrides) {
    public ProcessRequest(String executable, List<String> arguments, Duration timeout, int maxOutputCharacters) {
        this(executable, arguments, timeout, maxOutputCharacters, null, ProcessEnvironmentPolicy.INHERIT, Map.of());
    }

    public ProcessRequest(String executable, List<String> arguments, Duration timeout, int maxOutputCharacters,
                          String stdinContent) {
        this(executable, arguments, timeout, maxOutputCharacters, stdinContent, ProcessEnvironmentPolicy.INHERIT,
                Map.of());
    }

    public ProcessRequest(String executable, List<String> arguments, Duration timeout, int maxOutputCharacters,
                          String stdinContent, ProcessEnvironmentPolicy environmentPolicy) {
        this(executable, arguments, timeout, maxOutputCharacters, stdinContent, environmentPolicy, Map.of());
    }

    public ProcessRequest {
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("Executable is required");
        }
        if (executable.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Executable contains an invalid character");
        }
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        if (arguments.stream().anyMatch(arg -> arg == null || arg.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("Process arguments cannot be null or contain NUL");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Timeout must be between zero and five minutes");
        }
        if (maxOutputCharacters < 1 || maxOutputCharacters > 1_000_000) {
            throw new IllegalArgumentException("maxOutputCharacters must be between 1 and 1000000");
        }
        if (stdinContent != null && stdinContent.length() > 2_000_000) {
            throw new IllegalArgumentException("stdinContent exceeds the two million character safety limit");
        }
        environmentPolicy = environmentPolicy == null ? ProcessEnvironmentPolicy.INHERIT : environmentPolicy;
        environmentOverrides = environmentOverrides == null ? Map.of() : Map.copyOf(environmentOverrides);
        validateEnvironmentOverrides(environmentPolicy, environmentOverrides);
    }

    private static void validateEnvironmentOverrides(ProcessEnvironmentPolicy policy, Map<String, String> overrides) {
        Set<String> allowedKeys = policy == ProcessEnvironmentPolicy.CF_CLI_ISOLATED_HOME
                ? Set.of("CF_HOME") : Set.of();
        if (!overrides.keySet().equals(allowedKeys)) {
            throw new IllegalArgumentException("Process environment overrides are not allowed for this policy");
        }
        overrides.forEach((key, value) -> {
            if (value == null || value.isBlank() || value.length() > 4_096
                    || value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(key + " environment override is invalid");
            }
        });
    }
}
