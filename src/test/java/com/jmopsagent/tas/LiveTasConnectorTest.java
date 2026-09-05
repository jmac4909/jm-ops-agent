package com.jmopsagent.tas;

import static org.assertj.core.api.Assertions.assertThat;

import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.process.ProcessRequest;
import com.jmopsagent.connector.process.ProcessResult;
import com.jmopsagent.connector.process.ProcessRunner;
import com.jmopsagent.registry.RegistryDiscoveryUpdate;
import com.jmopsagent.registry.ServiceDefinition;
import com.jmopsagent.registry.ServiceRegistry;
import com.jmopsagent.registry.YamlServiceRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;

class LiveTasConnectorTest {
    private static final String API = "https://api.test.example.invalid";
    private static final String ORG = "sample-org";
    private static final String SPACE = "sample-space";

    @TempDir
    Path tempDirectory;

    @Test
    void verifiesTheIsolatedTargetBeforeExecutingAReadOnlyOperation() {
        ConcurrentLinkedQueue<ProcessRequest> requests = new ConcurrentLinkedQueue<>();
        ProcessRunner runner = request -> {
            requests.add(request);
            return request.arguments().equals(List.of("target"))
                    ? success(targetOutput(API, ORG, SPACE))
                    : success("requested state: started");
        };
        String devHome = tempDirectory.resolve("dev-home").toString();
        LiveTasConnector connector = connector(runner, devHome, tempDirectory.resolve("test-home").toString(), false);

        ConnectorEvidence evidence = connector.getApplicationStatus("sample-api", Environment.DEV).getFirst();

        assertThat(evidence.metadata()).containsEntry("status", "SUCCESS");
        assertThat(requests).extracting(ProcessRequest::arguments)
                .containsExactly(List.of("target"), List.of("app", "sample-api-dev"));
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.environmentOverrides()).containsExactlyEntriesOf(java.util.Map.of("CF_HOME", devHome));
            assertThat(request.arguments()).doesNotContain("-a", "-o", "-s", "login", "auth");
        });
    }

    @Test
    void failsClosedWhenTheCurrentTargetDoesNotMatch() {
        ConcurrentLinkedQueue<ProcessRequest> requests = new ConcurrentLinkedQueue<>();
        ProcessRunner runner = request -> {
            requests.add(request);
            return success(targetOutput("https://different.example.invalid", ORG, SPACE));
        };
        LiveTasConnector connector = connector(runner, tempDirectory.resolve("dev-home").toString(),
                tempDirectory.resolve("test-home").toString(), false);

        ConnectorEvidence evidence = connector.getApplicationStatus("sample-api", Environment.DEV).getFirst();

        assertThat(evidence.metadata()).containsEntry("status", "TARGET_MISMATCH");
        assertThat(evidence.content()).doesNotContain(API, "different.example.invalid", ORG, SPACE);
        assertThat(requests).extracting(ProcessRequest::arguments).containsExactly(List.of("target"));
    }

    @Test
    void environmentMetadataIsDisabledUnlessExplicitlyEnabled() {
        AtomicInteger calls = new AtomicInteger();
        LiveTasConnector connector = connector(request -> {
            calls.incrementAndGet();
            return success("");
        }, tempDirectory.resolve("dev-home").toString(), tempDirectory.resolve("test-home").toString(), false);

        ConnectorEvidence evidence = connector.getEnvironmentMetadata("sample-api", Environment.TEST).getFirst();

        assertThat(evidence.metadata()).containsEntry("status", "DISABLED");
        assertThat(calls).hasValue(0);
    }

    @Test
    void explicitlyEnabledEnvironmentMetadataIsRedactedAtTheSourceBoundary() {
        ProcessRunner runner = request -> request.arguments().equals(List.of("target"))
                ? success(targetOutput(API, ORG, SPACE))
                : success("System-Provided:\nCREDENTIAL: sensitive-value\nSETTING=private-value\n"
                        + "CALLBACK: https://private.example.invalid:\n");
        LiveTasConnector connector = connector(runner, tempDirectory.resolve("dev-home").toString(),
                tempDirectory.resolve("test-home").toString(), true);

        ConnectorEvidence evidence = connector.getEnvironmentMetadata("sample-api", Environment.DEV).getFirst();

        assertThat(evidence.metadata()).containsEntry("status", "SUCCESS");
        assertThat(evidence.content()).contains("[REDACTED_AT_SOURCE]")
                .doesNotContain("sensitive-value", "private-value", "private.example.invalid");
    }

    @Test
    void serializesCommandsThatShareTheSameNormalizedCfHome() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        ProcessRunner runner = request -> {
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(40);
                return request.arguments().equals(List.of("target"))
                        ? success(targetOutput(API, ORG, SPACE)) : success("ok");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return failure();
            } finally {
                active.decrementAndGet();
            }
        };
        Path home = tempDirectory.resolve("shared-home");
        String firstSpelling = home.resolve("..").resolve("shared-home").toString();
        TasProperties properties = new TasProperties();
        properties.setTargets(Map.of(
                "primary-test", target("TEST", API, ORG, SPACE, firstSpelling),
                "primary-test-alias", target("TEST", API, ORG, SPACE, home.toString())));
        LiveTasConnector connector = new LiveTasConnector(runner, registry("""
                services:
                  - service: first-api
                    tas:
                      target:
                        TEST: primary-test
                  - service: second-api
                    tas:
                      target:
                        TEST: primary-test-alias
                """), properties);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<ConnectorEvidence>> first = executor.submit(() ->
                    connector.getApplicationStatus("first-api", Environment.TEST));
            Future<List<ConnectorEvidence>> second = executor.submit(() ->
                    connector.getApplicationStatus("second-api", Environment.TEST));
            assertThat(first.get(5, TimeUnit.SECONDS).getFirst().metadata()).containsEntry("status", "SUCCESS");
            assertThat(second.get(5, TimeUnit.SECONDS).getFirst().metadata()).containsEntry("status", "SUCCESS");
        }

        assertThat(maximumActive).hasValue(1);
    }

    @Test
    void allowsDifferentCfHomesToRunConcurrently() throws Exception {
        CountDownLatch bothTargetsEntered = new CountDownLatch(2);
        AtomicBoolean concurrentTargetsObserved = new AtomicBoolean();
        String devHome = tempDirectory.resolve("dev-home").toString();
        String testHome = tempDirectory.resolve("test-home").toString();
        ProcessRunner runner = request -> {
            if (request.arguments().equals(List.of("target"))) {
                bothTargetsEntered.countDown();
                try {
                    if (bothTargetsEntered.await(2, TimeUnit.SECONDS)) concurrentTargetsObserved.set(true);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return failure();
                }
            }
            return success(targetOutput(API, ORG, SPACE));
        };
        LiveTasConnector connector = connector(runner, devHome, testHome, false);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> dev = executor.submit(() -> connector.getApplicationStatus("sample-api", Environment.DEV));
            Future<?> test = executor.submit(() -> connector.getApplicationStatus("sample-api", Environment.TEST));
            dev.get(5, TimeUnit.SECONDS);
            test.get(5, TimeUnit.SECONDS);
        }

        assertThat(concurrentTargetsObserved).isTrue();
    }

    @Test
    void routesServicesInTheSameEnvironmentToSeparateRegistrySelectedTargets() {
        String firstHome = tempDirectory.resolve("group-one-test-home").toString();
        String secondHome = tempDirectory.resolve("group-two-test-home").toString();
        String firstOrg = "group-one";
        String secondOrg = "group-two";
        ConcurrentLinkedQueue<ProcessRequest> requests = new ConcurrentLinkedQueue<>();
        ProcessRunner runner = request -> {
            requests.add(request);
            if (!request.arguments().equals(List.of("target"))) return success("requested state: started");
            String home = request.environmentOverrides().get("CF_HOME");
            return success(targetOutput(API, home.equals(firstHome) ? firstOrg : secondOrg, SPACE));
        };
        TasProperties properties = new TasProperties();
        properties.setTargets(Map.of(
                "group-one-test", target("TEST", API, firstOrg, SPACE, firstHome),
                "group-two-test", target("TEST", API, secondOrg, SPACE, secondHome)));
        LiveTasConnector connector = new LiveTasConnector(runner, registry("""
                services:
                  - service: first-api
                    tas:
                      target:
                        TEST: group-one-test
                  - service: second-api
                    tas:
                      target:
                        TEST: group-two-test
                """), properties);

        ConnectorEvidence first = connector.getApplicationStatus("first-api", Environment.TEST).getFirst();
        ConnectorEvidence second = connector.getApplicationStatus("second-api", Environment.TEST).getFirst();

        assertThat(first.metadata()).containsEntry("status", "SUCCESS");
        assertThat(second.metadata()).containsEntry("status", "SUCCESS");
        assertThat(requests).filteredOn(request -> request.arguments().equals(List.of("app", "first-api-test")))
                .singleElement().satisfies(request ->
                        assertThat(request.environmentOverrides()).containsEntry("CF_HOME", firstHome));
        assertThat(requests).filteredOn(request -> request.arguments().equals(List.of("app", "second-api-test")))
                .singleElement().satisfies(request ->
                        assertThat(request.environmentOverrides()).containsEntry("CF_HOME", secondHome));
    }

    @Test
    void allowsSeparateTargetsWithinTheSameEnvironmentToRunConcurrently() throws Exception {
        String firstHome = tempDirectory.resolve("group-one-test-home").toString();
        String secondHome = tempDirectory.resolve("group-two-test-home").toString();
        CountDownLatch bothTargetsEntered = new CountDownLatch(2);
        AtomicBoolean concurrentTargetsObserved = new AtomicBoolean();
        ProcessRunner runner = request -> {
            if (request.arguments().equals(List.of("target"))) {
                bothTargetsEntered.countDown();
                try {
                    if (bothTargetsEntered.await(2, TimeUnit.SECONDS)) concurrentTargetsObserved.set(true);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return failure();
                }
            }
            String home = request.environmentOverrides().get("CF_HOME");
            return success(targetOutput(API, home.equals(firstHome) ? "group-one" : "group-two", SPACE));
        };
        TasProperties properties = new TasProperties();
        properties.setTargets(Map.of(
                "group-one-test", target("TEST", API, "group-one", SPACE, firstHome),
                "group-two-test", target("TEST", API, "group-two", SPACE, secondHome)));
        LiveTasConnector connector = new LiveTasConnector(runner, registry("""
                services:
                  - service: first-api
                    tas:
                      target:
                        TEST: group-one-test
                  - service: second-api
                    tas:
                      target:
                        TEST: group-two-test
                """), properties);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<ConnectorEvidence>> first = executor.submit(() ->
                    connector.getApplicationStatus("first-api", Environment.TEST));
            Future<List<ConnectorEvidence>> second = executor.submit(() ->
                    connector.getApplicationStatus("second-api", Environment.TEST));
            assertThat(first.get(5, TimeUnit.SECONDS).getFirst().metadata()).containsEntry("status", "SUCCESS");
            assertThat(second.get(5, TimeUnit.SECONDS).getFirst().metadata()).containsEntry("status", "SUCCESS");
        }

        assertThat(concurrentTargetsObserved).isTrue();
    }

    @Test
    void failsClosedWhenMultipleTargetsExistWithoutAServiceMapping() {
        AtomicInteger calls = new AtomicInteger();
        TasProperties properties = new TasProperties();
        properties.setTargets(Map.of(
                "group-one-test", target("TEST", API, "group-one", SPACE,
                        tempDirectory.resolve("group-one-test-home").toString()),
                "group-two-test", target("TEST", API, "group-two", SPACE,
                        tempDirectory.resolve("group-two-test-home").toString())));
        LiveTasConnector connector = new LiveTasConnector(request -> {
            calls.incrementAndGet();
            return success("");
        }, emptyRegistry(), properties);

        ConnectorEvidence evidence = connector.getApplicationStatus("sample-api", Environment.TEST).getFirst();

        assertThat(evidence.metadata()).containsEntry("status", "AMBIGUOUS_TARGET");
        assertThat(evidence.content()).doesNotContain("group-one", "group-two", API);
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsUnknownAndCrossEnvironmentTargetMappingsWithoutExecutingCf() {
        AtomicInteger calls = new AtomicInteger();
        TasProperties properties = new TasProperties();
        properties.setTargets(Map.of(
                "group-one-dev", target("DEV", API, "group-one", SPACE,
                        tempDirectory.resolve("group-one-dev-home").toString())));
        ProcessRunner runner = request -> {
            calls.incrementAndGet();
            return success("");
        };
        LiveTasConnector unknown = new LiveTasConnector(runner, registry("""
                services:
                  - service: unknown-api
                    tas:
                      target:
                        TEST: missing-test-target
                """), properties);
        LiveTasConnector wrongEnvironment = new LiveTasConnector(runner, registry("""
                services:
                  - service: wrong-environment-api
                    tas:
                      target:
                        TEST: group-one-dev
                """), properties);

        ConnectorEvidence unknownEvidence = unknown.getApplicationStatus(
                "unknown-api", Environment.TEST).getFirst();
        ConnectorEvidence environmentEvidence = wrongEnvironment.getApplicationStatus(
                "wrong-environment-api", Environment.TEST).getFirst();

        assertThat(unknownEvidence.metadata()).containsEntry("status", "UNKNOWN_TARGET");
        assertThat(environmentEvidence.metadata()).containsEntry("status", "TARGET_ENVIRONMENT_MISMATCH");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsMixedLegacyAndTargetMapConfigurationWithoutExecutingCf() {
        AtomicInteger calls = new AtomicInteger();
        TasProperties properties = new TasProperties();
        properties.setTestApi(API);
        properties.setTestOrg(ORG);
        properties.setTestSpace(SPACE);
        properties.setTestHome(tempDirectory.resolve("legacy-test-home").toString());
        properties.setTargets(Map.of("group-one-test", target("TEST", API, "group-one", SPACE,
                tempDirectory.resolve("group-one-test-home").toString())));
        LiveTasConnector connector = new LiveTasConnector(request -> {
            calls.incrementAndGet();
            return success("");
        }, emptyRegistry(), properties);

        ConnectorEvidence evidence = connector.getApplicationStatus("sample-api", Environment.TEST).getFirst();

        assertThat(evidence.metadata()).containsEntry("status", "INVALID_CONFIGURATION");
        assertThat(evidence.content()).doesNotContain(API, ORG, "group-one");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsLogicalTargetsThatShareAHomeButExpectDifferentCfState() {
        AtomicInteger calls = new AtomicInteger();
        String sharedHome = tempDirectory.resolve("shared-target-home").toString();
        TasProperties properties = new TasProperties();
        properties.setTargets(Map.of(
                "group-one-test", target("TEST", API, "group-one", SPACE, sharedHome),
                "group-two-test", target("TEST", API, "group-two", SPACE, sharedHome)));
        LiveTasConnector connector = new LiveTasConnector(request -> {
            calls.incrementAndGet();
            return success("");
        }, registry("""
                services:
                  - service: sample-api
                    tas:
                      target:
                        TEST: group-one-test
                """), properties);

        ConnectorEvidence evidence = connector.getApplicationStatus("sample-api", Environment.TEST).getFirst();

        assertThat(evidence.metadata()).containsEntry("status", "INVALID_CONFIGURATION");
        assertThat(evidence.content()).doesNotContain(API, "group-one", "group-two", sharedHome);
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsDevAndTestTargetsSharingAHomeEvenWhenTheirRemoteTargetIsIdentical() {
        AtomicInteger calls = new AtomicInteger();
        String sharedHome = tempDirectory.resolve("cross-environment-home").toString();
        TasProperties properties = new TasProperties();
        properties.setTargets(Map.of(
                "primary-dev", target("DEV", API, ORG, SPACE, sharedHome),
                "primary-test", target("TEST", API, ORG, SPACE, sharedHome)));
        LiveTasConnector connector = new LiveTasConnector(request -> {
            calls.incrementAndGet();
            return success("");
        }, registry("""
                services:
                  - service: sample-api
                    tas:
                      target:
                        TEST: primary-test
                """), properties);

        ConnectorEvidence evidence = connector.getApplicationStatus("sample-api", Environment.TEST).getFirst();

        assertThat(evidence.metadata()).containsEntry("status", "INVALID_CONFIGURATION");
        assertThat(evidence.content()).doesNotContain(API, ORG, SPACE, sharedHome);
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsTargetsOutsideTheDevTestAllowlistWithoutExecutingCf() {
        AtomicInteger calls = new AtomicInteger();
        TasProperties properties = new TasProperties();
        properties.setTargets(Map.of("unsupported-target", target("PROD", API, ORG, SPACE,
                tempDirectory.resolve("unsupported-home").toString())));
        LiveTasConnector connector = new LiveTasConnector(request -> {
            calls.incrementAndGet();
            return success("");
        }, emptyRegistry(), properties);

        ConnectorEvidence evidence = connector.getApplicationStatus("sample-api", Environment.TEST).getFirst();

        assertThat(evidence.metadata()).containsEntry("status", "INVALID_CONFIGURATION");
        assertThat(calls).hasValue(0);
    }

    private LiveTasConnector connector(ProcessRunner runner, String devHome, String testHome,
                                       boolean environmentMetadataEnabled) {
        return new LiveTasConnector(runner, emptyRegistry(), "cf", "{service}-{environment}",
                API, ORG, SPACE, devHome, API, ORG, SPACE, testHome, environmentMetadataEnabled);
    }

    private static TasProperties.Target target(String environment, String api, String org, String space,
            String home) {
        TasProperties.Target target = new TasProperties.Target();
        target.setEnvironment(environment);
        target.setApi(api);
        target.setOrg(org);
        target.setSpace(space);
        target.setHome(home);
        return target;
    }

    private static YamlServiceRegistry registry(String yaml) {
        YamlServiceRegistry registry = new YamlServiceRegistry(
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        registry.load();
        return registry;
    }

    private static String targetOutput(String api, String org, String space) {
        return "api endpoint: " + api + "\norg: " + org + "\nspace: " + space + "\n";
    }

    private static ProcessResult success(String stdout) {
        Instant now = Instant.now();
        return new ProcessResult(true, 0, stdout, "", false, false, null, now, now);
    }

    private static ProcessResult failure() {
        Instant now = Instant.now();
        return new ProcessResult(true, 1, "", "unavailable", false, false, null, now, now);
    }

    private static ServiceRegistry emptyRegistry() {
        return new ServiceRegistry() {
            @Override
            public Optional<ServiceDefinition> resolve(String serviceOrAlias) {
                return Optional.empty();
            }

            @Override
            public Collection<ServiceDefinition> all() {
                return List.of();
            }

            @Override
            public ServiceDefinition applyDiscovery(RegistryDiscoveryUpdate update) {
                throw new UnsupportedOperationException("Discovery is not used by this test");
            }
        };
    }
}
