package com.jmopsagent.jenkins;

import com.jmopsagent.connector.Environment;
import com.jmopsagent.registry.ServiceDefinition;
import com.jmopsagent.registry.ServiceRegistry;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Resolves immutable controller and job targets from a service registry snapshot. */
final class JenkinsTargetResolver {
    private static final Pattern JOB_SEGMENT = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._ -]{0,99})");

    private final JenkinsControllerRegistry controllers;
    private final ServiceRegistry serviceRegistry;
    private final String jobPattern;

    JenkinsTargetResolver(JenkinsControllerRegistry controllers, ServiceRegistry serviceRegistry, String jobPattern) {
        this.controllers = controllers;
        this.serviceRegistry = serviceRegistry;
        this.jobPattern = jobPattern == null || jobPattern.isBlank()
                ? "{service}-{environment}-deploy"
                : jobPattern;
    }

    JenkinsResolvedTarget resolve(String service, Environment environment) {
        Optional<ServiceDefinition> definition = serviceRegistry.resolve(service);
        Optional<String> controllerId = attributeForEnvironment(definition, "jenkins.controller", environment);
        String job = attributeForEnvironment(definition, "jenkins.job", environment)
                .orElseGet(() -> jobPattern.replace("{service}", service)
                        .replace("{environment}", environment.name().toLowerCase(Locale.ROOT)));
        return new JenkinsResolvedTarget(controllers.resolve(controllerId), validateJobPath(job));
    }

    private static Optional<String> attributeForEnvironment(Optional<ServiceDefinition> definition, String path,
            Environment environment) {
        return definition.flatMap(value -> value.attributeValue(path + "." + environment.name())
                .or(() -> value.attributeValue(path)))
                .map(value -> value.replace("{environment}", environment.name().toLowerCase(Locale.ROOT)));
    }

    static String validateJobPath(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
            throw new IllegalArgumentException("Jenkins job path is invalid");
        }
        String[] segments = value.split("/");
        for (String segment : segments) {
            if (!JOB_SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Jenkins job path contains an unsupported segment");
            }
        }
        return String.join("/", segments);
    }
}

record JenkinsResolvedTarget(JenkinsControllerClient controller, String job) {}
