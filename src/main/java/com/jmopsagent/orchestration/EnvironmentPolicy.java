package com.jmopsagent.orchestration;

import com.jmopsagent.connector.Environment;
import com.jmopsagent.domain.DeploymentEnvironment;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Single fail-closed environment boundary used before any connector call. */
@Component
public class EnvironmentPolicy {

    public DeploymentEnvironment requireAllowed(String value) {
        if (value == null) throw new IllegalArgumentException("Environment is required");
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "DEV" -> DeploymentEnvironment.DEV;
            case "TEST" -> DeploymentEnvironment.TEST;
            default -> throw new IllegalArgumentException("Only DEV and TEST environments are allowed");
        };
    }

    public Environment connectorEnvironment(DeploymentEnvironment value) {
        if (value == null) throw new IllegalArgumentException("Environment is required");
        return switch (value) {
            case DEV -> Environment.DEV;
            case TEST -> Environment.TEST;
        };
    }
}
