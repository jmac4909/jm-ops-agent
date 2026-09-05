package com.jmopsagent.connector.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaProcessRunnerTest {
    private final JavaProcessRunner runner = new JavaProcessRunner();

    @Test
    void passesArgumentsWithoutShellParsingAndWritesStdin() {
        ProcessResult result = runner.execute(new ProcessRequest(javaExecutable(), List.of(
                "-cp", fixtureClasspath(), ProcessEchoFixture.class.getName(), "literal;not-a-shell"),
                Duration.ofSeconds(10), 10_000, "prompt over stdin"));

        assertThat(result.successful()).isTrue();
        assertThat(result.stdout()).isEqualTo("arg=literal;not-a-shell;stdin=prompt over stdin");
        assertThat(result.stderr()).isEqualTo("fixture-stderr");
        assertThat(result.outputTruncated()).isFalse();
    }

    @Test
    void terminatesAProcessAtTheConfiguredTimeout() {
        ProcessResult result = runner.execute(new ProcessRequest(javaExecutable(), List.of(
                "-cp", fixtureClasspath(), ProcessEchoFixture.class.getName(), "sleep"),
                Duration.ofMillis(100), 1_000));

        assertThat(result.started()).isTrue();
        assertThat(result.timedOut()).isTrue();
        assertThat(result.successful()).isFalse();
    }

    @Test
    void timeoutTerminatesParentAndDescendantWithinABoundedInterval(@TempDir Path temporaryDirectory)
            throws IOException {
        Path pidFile = temporaryDirectory.resolve("timeout-processes.pid");
        Instant startedAt = Instant.now();

        try {
            ProcessResult result = runner.execute(new ProcessRequest(javaExecutable(), List.of(
                    "-cp", fixtureClasspath(), ProcessEchoFixture.class.getName(),
                    "spawn-child", javaExecutable(), pidFile.toString()),
                    Duration.ofSeconds(2), 1_000));

            assertThat(result.timedOut())
                    .as("fixture should time out (startupError=%s, stderr=%s)",
                            result.startupError(), result.stderr())
                    .isTrue();
            assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(5));
            assertRecordedProcessesExited(pidFile);
        } finally {
            terminateRecordedProcesses(pidFile);
        }
    }

    @Test
    void interruptionTerminatesParentAndDescendantAndRestoresInterruptStatus(@TempDir Path temporaryDirectory)
            throws Exception {
        Path pidFile = temporaryDirectory.resolve("interrupted-processes.pid");
        AtomicReference<ProcessResult> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread executingThread = Thread.ofPlatform().start(() -> {
            try {
                result.set(runner.execute(new ProcessRequest(javaExecutable(), List.of(
                        "-cp", fixtureClasspath(), ProcessEchoFixture.class.getName(),
                        "spawn-child", javaExecutable(), pidFile.toString()),
                        Duration.ofSeconds(30), 1_000)));
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        try {
            assertThat(waitForRecordedPids(pidFile, Duration.ofSeconds(5)))
                    .as("fixture should start and record its parent and child PIDs")
                    .isTrue();
            Instant interruptedAt = Instant.now();
            executingThread.interrupt();
            executingThread.join(Duration.ofSeconds(3));

            assertThat(executingThread.isAlive()).isFalse();
            assertThat(Duration.between(interruptedAt, Instant.now())).isLessThan(Duration.ofSeconds(3));
            assertThat(failure.get()).isNull();
            assertThat(result.get()).isNotNull();
            assertThat(result.get().timedOut()).isTrue();
            assertThat(result.get().stderr()).startsWith("Process interrupted");
            assertThat(interruptRestored).isTrue();
            assertRecordedProcessesExited(pidFile);
        } finally {
            executingThread.interrupt();
            terminateRecordedProcesses(pidFile);
        }
    }

    @Test
    void reportsMissingExecutableWithoutThrowing() {
        ProcessResult result = runner.execute(new ProcessRequest("jmops-executable-that-does-not-exist",
                List.of("--version"), Duration.ofSeconds(1), 1_000));

        assertThat(result.started()).isFalse();
        assertThat(result.startupError()).isNotBlank();
    }

    @Test
    void claudeEnvironmentPolicyKeepsVertexRuntimeButRemovesConnectorCredentials() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "PATH", "/tools",
                "GOOGLE_APPLICATION_CREDENTIALS", "/approved/vertex.json",
                "CLAUDE_CODE_USE_VERTEX", "1",
                "GITLAB_TOKEN", "gitlab-secret",
                "JENKINS_TOKEN", "jenkins-secret",
                "SPLUNK_TOKEN", "splunk-secret",
                "UNRELATED_SECRET", "secret"));

        JavaProcessRunner.applyEnvironmentPolicy(environment, ProcessEnvironmentPolicy.CLAUDE_VERTEX_ALLOWLIST);

        assertThat(environment).containsEntry("PATH", "/tools")
                .containsEntry("GOOGLE_APPLICATION_CREDENTIALS", "/approved/vertex.json")
                .containsEntry("CLAUDE_CODE_USE_VERTEX", "1")
                .doesNotContainKeys("GITLAB_TOKEN", "JENKINS_TOKEN", "SPLUNK_TOKEN", "UNRELATED_SECRET");
    }

    @Test
    void cfPolicyOverridesOnlyTheIsolatedHome() {
        ProcessResult result = runner.execute(new ProcessRequest(javaExecutable(), List.of(
                "-cp", fixtureClasspath(), ProcessEchoFixture.class.getName(),
                "environment", "CF_HOME"), Duration.ofSeconds(10), 10_000, null,
                ProcessEnvironmentPolicy.CF_CLI_ISOLATED_HOME, Map.of("CF_HOME", "isolated-cf-home")));

        assertThat(result.successful()).isTrue();
        assertThat(result.stdout()).isEqualTo("isolated-cf-home");
    }

    @Test
    void rejectsEnvironmentOverridesOutsideTheSelectedPolicy() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProcessRequest(javaExecutable(), List.of(),
                Duration.ofSeconds(1), 1_000, null, ProcessEnvironmentPolicy.INHERIT,
                Map.of("CF_HOME", "unexpected")));
        assertThatIllegalArgumentException().isThrownBy(() -> new ProcessRequest(javaExecutable(), List.of(),
                Duration.ofSeconds(1), 1_000, null, ProcessEnvironmentPolicy.CF_CLI_ISOLATED_HOME,
                Map.of("UNRELATED", "value")));
    }

    @Test
    void decodesUtf8AcrossArbitraryByteReadBoundaries() throws IOException {
        String expected = "status: café 👍";
        byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
        FilterInputStream oneByteAtATime = new FilterInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return super.read(buffer, offset, Math.min(1, length));
            }
        };

        JavaProcessRunner.BoundedText result = JavaProcessRunner.readBounded(oneByteAtATime, 1_000);

        assertThat(result.text()).isEqualTo(expected);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void truncationDoesNotLeaveAnUnpairedSurrogate() throws IOException {
        byte[] bytes = "A👍B".getBytes(StandardCharsets.UTF_8);

        JavaProcessRunner.BoundedText result = JavaProcessRunner.readBounded(new ByteArrayInputStream(bytes), 2);

        assertThat(result.text()).isEqualTo("A");
        assertThat(result.truncated()).isTrue();
    }

    private static String javaExecutable() {
        String file = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", file).toString();
    }

    /**
     * The fixture only depends on JDK classes, so launch it from its own code-source location instead of passing
     * Surefire's complete dependency classpath. The latter can exceed Windows' CreateProcess command-line limit,
     * especially when it is also forwarded to a child process.
     */
    private static String fixtureClasspath() {
        try {
            return Path.of(ProcessEchoFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to resolve process test fixture classpath", ex);
        }
    }

    private static boolean waitForRecordedPids(Path path, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (recordedPids(path).size() == 2) {
                    return true;
                }
            } catch (IOException | NumberFormatException ignored) {
                // The fixture may be between creating and finishing its tiny PID file; retry until the deadline.
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static void assertRecordedProcessesExited(Path pidFile) throws IOException {
        List<Long> pids = recordedPids(pidFile);
        assertThat(pids).as("fixture records its parent and child PIDs").hasSize(2);
        assertThat(pids)
                .allSatisfy(pid -> assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                        .as("process %s is no longer alive", pid)
                        .isFalse());
    }

    private static void terminateRecordedProcesses(Path pidFile) {
        try {
            for (long pid : recordedPids(pidFile).reversed()) {
                ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
            }
        } catch (IOException ignored) {
            // Test cleanup is best effort when the fixture did not finish recording its process IDs.
        }
    }

    private static List<Long> recordedPids(Path pidFile) throws IOException {
        if (!Files.isRegularFile(pidFile)) {
            return List.of();
        }
        return Files.readAllLines(pidFile, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(Long::parseLong)
                .toList();
    }
}
