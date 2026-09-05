package com.jmopsagent.registry;

import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.DeploymentInfo;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.jenkins.JenkinsConnector;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Confirms an exact Jenkins service/environment match without retaining build output. */
@Component
@Profile("local-live")
@Order(200)
public class JenkinsServiceRegistryDiscoveryHook implements ServiceRegistryDiscoveryHook {

    private static final int MAX_JOB_CHARACTERS = 500;
    private static final Pattern JOB_SEGMENT = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._ -]{0,99})");
    private static final Pattern CONTROLLER_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    private final JenkinsConnector connector;

    public JenkinsServiceRegistryDiscoveryHook(JenkinsConnector connector) {
        this.connector = connector;
    }

    @Override
    public Optional<RegistryDiscoveryUpdate> discover(ServiceDefinition service,
            DeploymentEnvironment environment) {
        String candidate = ConnectorInputValidator.service(service.service());
        Environment connectorEnvironment = Environment.valueOf(environment.name());
        Optional<DeploymentInfo> latest;
        try {
            latest = connector.getLatestDeployment(candidate, connectorEnvironment);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        if (latest.isEmpty() || !isExactMatch(latest.get(), candidate, connectorEnvironment)) {
            return Optional.empty();
        }

        DeploymentInfo deployment = latest.get();
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("jenkins.job." + environment.name(), deployment.jobName());
        safeController(deployment.metadata().get("controller"))
                .ifPresent(value -> attributes.put("jenkins.controller." + environment.name(), value));
        return Optional.of(RegistryDiscoveryUpdate.of(candidate,
                RegistryProvenance.DISCOVERED_JENKINS, attributes));
    }

    private static boolean isExactMatch(DeploymentInfo deployment, String candidate, Environment environment) {
        if (deployment == null || deployment.environment() != environment || deployment.service() == null) {
            return false;
        }
        try {
            if (!ConnectorInputValidator.service(deployment.service()).equals(candidate)) {
                return false;
            }
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        return safeJob(deployment.jobName()) && followsNamingConvention(deployment.jobName(), candidate, environment);
    }

    private static boolean safeJob(String job) {
        if (job == null || job.isBlank() || job.length() > MAX_JOB_CHARACTERS
                || job.startsWith("/") || job.endsWith("/") || job.contains("//")) {
            return false;
        }
        for (String segment : job.split("/")) {
            if (!JOB_SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static boolean followsNamingConvention(String job, String candidate, Environment environment) {
        String[] segments = job.split("/");
        for (String segment : segments) {
            if (segment.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        String leaf = segments[segments.length - 1];
        return leaf.equalsIgnoreCase(candidate + "-deploy")
                || leaf.equalsIgnoreCase(candidate + "-" + environment.name().toLowerCase() + "-deploy");
    }

    private static Optional<String> safeController(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return CONTROLLER_ID.matcher(normalized).matches() ? Optional.of(normalized) : Optional.empty();
    }
}
