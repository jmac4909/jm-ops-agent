package com.jmopsagent.tas;

import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.ConnectorInputValidator;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.connector.EvidenceSource;
import com.jmopsagent.connector.EvidenceType;
import com.jmopsagent.connector.process.ProcessEnvironmentPolicy;
import com.jmopsagent.connector.process.ProcessRequest;
import com.jmopsagent.connector.process.ProcessResult;
import com.jmopsagent.connector.process.ProcessRunner;
import com.jmopsagent.connector.process.ReadOnlyCliPolicy;
import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.registry.ServiceRegistry;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local-live")
public class LiveTasConnector implements TasConnector {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern SAFE_TARGET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,249}");
    private static final Pattern TARGET_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Set<String> ENVIRONMENT_HEADINGS = Set.of(
            "system-provided:", "user-provided:", "running environment variable groups:",
            "staging environment variable groups:", "no user-defined env variables have been set",
            "no running env variables have been set", "no staging env variables have been set");
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    private final ProcessRunner runner;
    private final String executable;
    private final String appPattern;
    private final TargetCatalog targetCatalog;
    private final ServiceRegistry serviceRegistry;
    private final boolean environmentMetadataEnabled;
    private final ConcurrentHashMap<String, ReentrantLock> homeLocks = new ConcurrentHashMap<>();

    @Autowired
    public LiveTasConnector(ProcessRunner runner, ServiceRegistry serviceRegistry, TasProperties properties) {
        this(runner, serviceRegistry, properties.getExecutable(), properties.getAppPattern(),
                TargetCatalog.from(properties), properties.isEnvironmentMetadataEnabled());
    }

    LiveTasConnector(ProcessRunner runner, ServiceRegistry serviceRegistry, String executable, String appPattern,
            String devApi, String devOrg, String devSpace, String devHome,
            String testApi, String testOrg, String testSpace, String testHome,
            boolean environmentMetadataEnabled) {
        this(runner, serviceRegistry, executable, appPattern,
                TargetCatalog.legacy(devApi, devOrg, devSpace, devHome,
                        testApi, testOrg, testSpace, testHome),
                environmentMetadataEnabled);
    }

    private LiveTasConnector(ProcessRunner runner, ServiceRegistry serviceRegistry, String executable,
            String appPattern, TargetCatalog targetCatalog, boolean environmentMetadataEnabled) {
        this.runner = runner;
        this.serviceRegistry = serviceRegistry;
        this.executable = executable;
        this.appPattern = appPattern == null || appPattern.isBlank() ? "{service}-{environment}" : appPattern;
        this.targetCatalog = targetCatalog;
        this.environmentMetadataEnabled = environmentMetadataEnabled;
    }

    @Override
    public List<ConnectorEvidence> getApplicationStatus(String service, Environment environment) {
        return List.of(execute(service, environment, EvidenceType.WORKLOAD_HEALTH, "TAS application status",
                List.of("app", applicationName(service, environment)), 30_000, UnaryOperator.identity()));
    }

    @Override
    public List<ConnectorEvidence> getRecentLogs(String service, Environment environment, EvidenceQuery query) {
        return List.of(execute(service, environment, EvidenceType.APPLICATION_LOG, "recent TAS logs",
                List.of("logs", applicationName(service, environment), "--recent"), query.maxContentCharacters(),
                UnaryOperator.identity()));
    }

    @Override
    public List<ConnectorEvidence> getEnvironmentMetadata(String service, Environment environment) {
        if (!environmentMetadataEnabled) {
            return List.of(unavailable(ConnectorInputValidator.service(service), requireEnvironment(environment),
                    EvidenceType.CONFIGURATION, "TAS environment metadata collection is disabled",
                    "Enable this operation explicitly only when its sensitive output is required.", "DISABLED"));
        }
        return List.of(execute(service, environment, EvidenceType.CONFIGURATION, "TAS environment metadata",
                List.of("env", applicationName(service, environment)), 30_000, this::redactEnvironmentAtSource));
    }

    @Override
    public List<ConnectorEvidence> getRoutes(String service, Environment environment) {
        String appName = applicationName(service, environment);
        return List.of(execute(service, environment, EvidenceType.NETWORK, "TAS routes", List.of("routes"), 30_000,
                output -> output.lines().filter(line -> line.contains(appName)).reduce("", (a, b) -> a + b + "\n")));
    }

    @Override
    public List<ConnectorEvidence> getInstances(String service, Environment environment) {
        return List.of(execute(service, environment, EvidenceType.WORKLOAD_HEALTH, "TAS instances",
                List.of("app", applicationName(service, environment)), 30_000, UnaryOperator.identity()));
    }

    private ConnectorEvidence execute(String service, Environment environment, EvidenceType type, String label,
            List<String> operation, int maxCharacters, UnaryOperator<String> filter) {
        String safeService = ConnectorInputValidator.service(service);
        if (environment == null) throw new IllegalArgumentException("Environment is required");
        TargetResolution resolution = targetCatalog.resolve(targetReference(safeService, environment), environment);
        if (!resolution.available()) {
            return unavailable(safeService, environment, type, label + " unavailable",
                    resolution.message(), resolution.status());
        }
        Target target = resolution.target();
        ReentrantLock lock = homeLocks.computeIfAbsent(target.lockKey(), ignored -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return unavailable(safeService, environment, type, label + " unavailable",
                    "Interrupted while waiting for the isolated CF CLI state.", "INTERRUPTED");
        }
        if (!acquired) {
            return unavailable(safeService, environment, type, label + " unavailable",
                    "The isolated CF CLI state is busy; no command was executed.", "BUSY");
        }
        ProcessResult result;
        try {
            TargetVerification verification = verifyTarget(target);
            if (!verification.verified()) {
                return unavailable(safeService, environment, type, label + " unavailable",
                        verification.message(), verification.status());
            }
            result = run(target, operation, Math.min(100_000, Math.max(1, maxCharacters)));
        } finally {
            lock.unlock();
        }
        String raw = result.successful() ? result.stdout()
                : (result.stderr().isBlank() ? result.startupError() : result.stderr());
        String content = filter.apply(raw == null ? "No command output" : raw);
        Instant now = Instant.now();
        return new ConnectorEvidence("tas-" + type.name().toLowerCase(Locale.ROOT) + "-" + now.toEpochMilli(),
                EvidenceSource.TAS, type, now, safeService, environment,
                result.successful() ? label + " collected" : label + " unavailable", content, null,
                Map.of("status", result.successful() ? "SUCCESS" : "ERROR",
                        "exitCode", Integer.toString(result.exitCode()),
                        "timedOut", Boolean.toString(result.timedOut()),
                        "truncated", Boolean.toString(result.outputTruncated())), 0.85);
    }

    private TargetVerification verifyTarget(Target expected) {
        ProcessResult result = run(expected, List.of("target"), 10_000);
        if (!result.successful()) {
            return new TargetVerification(false, "TARGET_UNAVAILABLE",
                    "The CF CLI target could not be verified; no application command was executed.");
        }
        Map<String, String> actual = parseTarget(result.stdout());
        boolean matches = sameApi(expected.api(), actual.get("api endpoint"))
                && expected.org().equals(actual.get("org"))
                && expected.space().equals(actual.get("space"));
        if (!matches) {
            return new TargetVerification(false, "TARGET_MISMATCH",
                    "The isolated CF CLI target does not match the configured target; no application command was executed.");
        }
        return new TargetVerification(true, "SUCCESS", "Target verified");
    }

    private ProcessResult run(Target target, List<String> arguments, int maxCharacters) {
        ReadOnlyCliPolicy.validateCf(arguments);
        return runner.execute(new ProcessRequest(executable, arguments, COMMAND_TIMEOUT, maxCharacters, null,
                ProcessEnvironmentPolicy.CF_CLI_ISOLATED_HOME, Map.of("CF_HOME", target.home())));
    }

    private static Map<String, String> parseTarget(String output) {
        Map<String, String> values = new HashMap<>();
        if (output == null) return values;
        output.lines().forEach(line -> {
            int separator = line.indexOf(':');
            if (separator <= 0) return;
            String key = line.substring(0, separator).strip().toLowerCase(Locale.ROOT);
            if (key.equals("api endpoint") || key.equals("org") || key.equals("space")) {
                values.putIfAbsent(key, line.substring(separator + 1).strip());
            }
        });
        return values;
    }

    private static boolean sameApi(String expected, String actual) {
        if (actual == null) return false;
        return withoutTrailingSlash(expected).equalsIgnoreCase(withoutTrailingSlash(actual));
    }

    private static String withoutTrailingSlash(String value) {
        String result = value.strip();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static Environment requireEnvironment(Environment environment) {
        if (environment == null) throw new IllegalArgumentException("Environment is required");
        return environment;
    }

    private static ConnectorEvidence unavailable(String service, Environment environment, EvidenceType type,
            String summary, String content, String status) {
        Instant now = Instant.now();
        return new ConnectorEvidence("tas-" + status.toLowerCase(Locale.ROOT) + "-" + now.toEpochMilli(),
                EvidenceSource.TAS, type, now, service, environment, summary, content, null,
                Map.of("status", status), 0.5);
    }

    private Optional<String> targetReference(String service, Environment environment) {
        return serviceRegistry.resolve(service)
                .flatMap(definition -> definition.attributeForEnvironment("tas.target",
                        DeploymentEnvironment.valueOf(environment.name())));
    }

    private String applicationName(String service, Environment environment) {
        String safeService = ConnectorInputValidator.service(service);
        if (environment == null) throw new IllegalArgumentException("Environment is required");
        String pattern = serviceRegistry.resolve(safeService)
                .flatMap(definition -> definition.attributeForEnvironment("tas.appPattern",
                        DeploymentEnvironment.valueOf(environment.name())))
                .orElse(appPattern);
        String value = pattern.replace("{service}", safeService)
                .replace("{environment}", environment.name().toLowerCase(Locale.ROOT));
        return ConnectorInputValidator.kubernetesName(value, "TAS application name");
    }

    private String redactEnvironmentAtSource(String output) {
        StringBuilder safe = new StringBuilder();
        output.lines().limit(500).forEach(line -> {
            String stripped = line.strip();
            if (stripped.isEmpty() || isHeading(stripped)) {
                safe.append(line).append('\n');
                return;
            }
            int separator = firstSeparator(line);
            if (separator >= 0) {
                safe.append(line, 0, separator + 1).append(" [REDACTED_AT_SOURCE]\n");
            } else {
                safe.append("[environment metadata omitted]\n");
            }
        });
        return safe.toString();
    }

    private static boolean isHeading(String line) {
        return ENVIRONMENT_HEADINGS.contains(line.toLowerCase(Locale.ROOT));
    }

    private static int firstSeparator(String line) {
        int colon = line.indexOf(':');
        int equals = line.indexOf('=');
        if (colon < 0) return equals;
        if (equals < 0) return colon;
        return Math.min(colon, equals);
    }

    private record Target(Environment environment, String api, String org, String space, String home,
                          String lockKey) {
        private Target(Environment environment, String api, String org, String space, String home) {
            this(environment, validate(api, "API endpoint"), validate(org, "org"), validate(space, "space"),
                    validateHome(home), normalizeHome(home));
        }

        private Target {
            if (environment == null) {
                throw new IllegalArgumentException("Configured TAS target environment is required");
            }
            if (home.isBlank() != lockKey.isBlank()) {
                throw new IllegalArgumentException("Configured TAS CF home is invalid");
            }
        }

        private static String validate(String value, String label) {
            String result = value == null ? "" : value.trim();
            if (!result.isBlank() && !SAFE_TARGET.matcher(result).matches()) {
                throw new IllegalArgumentException("Configured TAS " + label + " contains unsupported characters");
            }
            return result;
        }

        private static String validateHome(String value) {
            String result = value == null ? "" : value.trim();
            if (result.length() > 4_096 || result.indexOf('\0') >= 0 || result.indexOf('\r') >= 0
                    || result.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Configured TAS CF home contains unsupported characters");
            }
            if (!result.isBlank()) {
                try {
                    Path path = Path.of(result);
                    if (!path.isAbsolute()) {
                        throw new IllegalArgumentException("Configured TAS CF home must be an absolute path");
                    }
                    return path.normalize().toString();
                } catch (InvalidPathException ex) {
                    throw new IllegalArgumentException("Configured TAS CF home is not a valid path", ex);
                }
            }
            return result;
        }

        private static String normalizeHome(String value) {
            String home = validateHome(value);
            if (home.isBlank()) return "";
            String normalized = Path.of(home).normalize().toString();
            return WINDOWS ? normalized.toLowerCase(Locale.ROOT) : normalized;
        }

        private boolean configured() {
            return !api.isBlank() && !org.isBlank() && !space.isBlank() && !home.isBlank();
        }
    }

    private record TargetCatalog(Map<String, Target> targets, boolean invalidConfiguration) {
        private static final String DEV_DEFAULT = "dev-default";
        private static final String TEST_DEFAULT = "test-default";

        private TargetCatalog {
            targets = Map.copyOf(targets);
        }

        private static TargetCatalog from(TasProperties properties) {
            if (properties == null) return invalid();
            Map<String, TasProperties.Target> configured = properties.getTargets();
            if (configured != null && !configured.isEmpty()) {
                if (properties.hasLegacyValues()) return invalid();
                try {
                    Map<String, Target> targets = new LinkedHashMap<>();
                    configured.forEach((rawId, value) -> {
                        String id = normalizeTargetId(rawId);
                        if (value == null || targets.containsKey(id)) {
                            throw new IllegalArgumentException("Configured TAS target is invalid");
                        }
                        Environment environment = parseEnvironment(value.getEnvironment());
                        Target target = new Target(environment, value.getApi(), value.getOrg(), value.getSpace(),
                                value.getHome());
                        if (!target.configured()) {
                            throw new IllegalArgumentException("Configured TAS target is incomplete");
                        }
                        targets.put(id, target);
                    });
                    return hasConflictingHomes(targets.values())
                            ? invalid()
                            : new TargetCatalog(targets, false);
                } catch (RuntimeException ignored) {
                    return invalid();
                }
            }
            return legacy(properties.getDevApi(), properties.getDevOrg(), properties.getDevSpace(),
                    properties.getDevHome(), properties.getTestApi(), properties.getTestOrg(),
                    properties.getTestSpace(), properties.getTestHome());
        }

        private static TargetCatalog legacy(String devApi, String devOrg, String devSpace, String devHome,
                String testApi, String testOrg, String testSpace, String testHome) {
            Map<String, Target> targets = new LinkedHashMap<>();
            if (!addLegacy(targets, DEV_DEFAULT, Environment.DEV, devApi, devOrg, devSpace, devHome)) {
                return invalid();
            }
            if (!addLegacy(targets, TEST_DEFAULT, Environment.TEST, testApi, testOrg, testSpace, testHome)) {
                return invalid();
            }
            return hasConflictingHomes(targets.values())
                    ? invalid()
                    : new TargetCatalog(targets, false);
        }

        private static boolean addLegacy(Map<String, Target> targets, String id, Environment environment,
                String api, String org, String space, String home) {
            int present = present(api) + present(org) + present(space) + present(home);
            if (present == 0) return true;
            if (present != 4) return false;
            try {
                targets.put(id, new Target(environment, api, org, space, home));
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        private TargetResolution resolve(Optional<String> requestedId, Environment environment) {
            if (invalidConfiguration) {
                return TargetResolution.failure("INVALID_CONFIGURATION",
                        "The TAS target configuration is invalid; no command was executed.");
            }
            if (requestedId.isPresent()) {
                String id;
                try {
                    id = normalizeTargetId(requestedId.get());
                } catch (IllegalArgumentException ignored) {
                    return TargetResolution.failure("INVALID_TARGET_REFERENCE",
                            "The service has an invalid TAS target mapping; no command was executed.");
                }
                Target selected = targets.get(id);
                if (selected == null) {
                    return TargetResolution.failure("UNKNOWN_TARGET",
                            "The service references an unknown TAS target; no command was executed.");
                }
                if (selected.environment() != environment) {
                    return TargetResolution.failure("TARGET_ENVIRONMENT_MISMATCH",
                            "The service TAS target is not approved for the requested environment; no command was executed.");
                }
                return TargetResolution.success(selected);
            }
            List<Target> candidates = targets.values().stream()
                    .filter(target -> target.environment() == environment)
                    .toList();
            if (candidates.isEmpty()) {
                return TargetResolution.failure("UNCONFIGURED",
                        "Configure an isolated CF home and expected target before enabling live TAS evidence.");
            }
            if (candidates.size() > 1) {
                return TargetResolution.failure("AMBIGUOUS_TARGET",
                        "The service must select a TAS target when multiple targets exist for the environment; no command was executed.");
            }
            return TargetResolution.success(candidates.getFirst());
        }

        private static String normalizeTargetId(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Configured TAS target identifier is required");
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!TARGET_ID.matcher(normalized).matches()) {
                throw new IllegalArgumentException("Configured TAS target identifier is invalid");
            }
            return normalized;
        }

        private static Environment parseEnvironment(String value) {
            try {
                return Environment.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                throw new IllegalArgumentException("Configured TAS target environment must be DEV or TEST");
            }
        }

        private static int present(String value) {
            return value == null || value.isBlank() ? 0 : 1;
        }

        private static boolean hasConflictingHomes(Iterable<Target> targets) {
            Map<String, Target> homes = new HashMap<>();
            for (Target target : targets) {
                Target existing = homes.putIfAbsent(target.lockKey(), target);
                if (existing != null && (existing.environment() != target.environment()
                        || !sameApi(existing.api(), target.api())
                        || !existing.org().equals(target.org())
                        || !existing.space().equals(target.space()))) {
                    return true;
                }
            }
            return false;
        }

        private static TargetCatalog invalid() {
            return new TargetCatalog(Map.of(), true);
        }
    }

    private record TargetResolution(Target target, String status, String message) {
        private static TargetResolution success(Target target) {
            return new TargetResolution(target, "SUCCESS", "Target selected");
        }

        private static TargetResolution failure(String status, String message) {
            return new TargetResolution(null, status, message);
        }

        private boolean available() {
            return target != null;
        }
    }

    private record TargetVerification(boolean verified, String status, String message) {
    }
}
