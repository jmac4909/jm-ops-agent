package com.jmopsagent.connector.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
public class JavaProcessRunner implements ProcessRunner {
    private static final long GRACEFUL_TERMINATION_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long FORCED_TERMINATION_NANOS = TimeUnit.MILLISECONDS.toNanos(750);
    private static final long TERMINATION_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    private static final Set<String> CLAUDE_ENVIRONMENT_ALLOWLIST = Set.of(
            "PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "TEMP", "TMP", "TMPDIR",
            "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "XDG_CONFIG_HOME", "CLAUDE_CONFIG_DIR",
            "LANG", "LC_ALL", "TERM", "NO_COLOR", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
            "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS", "CLOUDSDK_CONFIG",
            "GOOGLE_APPLICATION_CREDENTIALS", "GOOGLE_CLOUD_PROJECT", "GOOGLE_CLOUD_QUOTA_PROJECT",
            "GCLOUD_PROJECT", "CLAUDE_CODE_USE_VERTEX", "ANTHROPIC_VERTEX_PROJECT_ID", "CLOUD_ML_REGION",
            "ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL",
            "ANTHROPIC_DEFAULT_HAIKU_MODEL", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC",
            "DISABLE_TELEMETRY", "DISABLE_ERROR_REPORTING");

    @Override
    public ProcessResult execute(ProcessRequest request) {
        Instant startedAt = Instant.now();
        List<String> command = new ArrayList<>(request.arguments().size() + 1);
        command.add(request.executable());
        command.addAll(request.arguments());

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            applyEnvironmentPolicy(builder.environment(), request.environmentPolicy(), request.environmentOverrides());
            process = builder.start();
        } catch (IOException ex) {
            return new ProcessResult(false, -1, "", "", false, false,
                    safeStartupMessage(ex), startedAt, Instant.now());
        }

        boolean timedOut = false;
        boolean terminationVerified = true;
        int exitCode = -1;
        BoundedText stdout = new BoundedText("Unable to capture process output", true);
        BoundedText stderr = new BoundedText("Unable to capture process output", true);
        ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
        Future<BoundedText> stdoutFuture = readers.submit(
                () -> readBounded(process.getInputStream(), request.maxOutputCharacters()));
        Future<BoundedText> stderrFuture = readers.submit(
                () -> readBounded(process.getErrorStream(), request.maxOutputCharacters()));
        Future<?> stdinFuture = readers.submit(() -> {
                try (var stdin = process.getOutputStream()) {
                    if (request.stdinContent() != null) {
                        stdin.write(request.stdinContent().getBytes(StandardCharsets.UTF_8));
                    }
                }
                return null;
            });
        try {
            if (!process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                terminationVerified = terminateProcessTree(process);
                closeProcessStreams(process);
            }
            if (!process.isAlive()) {
                exitCode = process.exitValue();
            }
            awaitWriter(stdinFuture);
            stdout = getReaderResult(stdoutFuture);
            stderr = getReaderResult(stderrFuture);
        } catch (InterruptedException ex) {
            boolean treeTerminated = terminateProcessTree(process);
            closeProcessStreams(process);
            Thread.currentThread().interrupt();
            String message = treeTerminated
                    ? "Process interrupted"
                    : "Process interrupted; process termination could not be verified";
            return new ProcessResult(true, -1, "", message, true, false,
                    null, startedAt, Instant.now());
        } finally {
            closeProcessStreams(process);
            stdinFuture.cancel(true);
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            readers.shutdownNow();
            try {
                readers.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        if (timedOut && !terminationVerified) {
            stderr = appendTerminationWarning(stderr, request.maxOutputCharacters());
        }
        return new ProcessResult(true, exitCode, stdout.text(), stderr.text(), timedOut,
                stdout.truncated() || stderr.truncated(), null, startedAt, Instant.now());
    }

    static void applyEnvironmentPolicy(Map<String, String> environment, ProcessEnvironmentPolicy policy) {
        applyEnvironmentPolicy(environment, policy, Map.of());
    }

    static void applyEnvironmentPolicy(Map<String, String> environment, ProcessEnvironmentPolicy policy,
                                       Map<String, String> overrides) {
        if (policy == ProcessEnvironmentPolicy.CLAUDE_VERTEX_ALLOWLIST) {
            environment.entrySet().removeIf(entry -> {
                String key = entry.getKey().toUpperCase(Locale.ROOT);
                return !CLAUDE_ENVIRONMENT_ALLOWLIST.contains(key) && !key.startsWith("VERTEX_REGION_CLAUDE_");
            });
        }
        if (policy == ProcessEnvironmentPolicy.CF_CLI_ISOLATED_HOME) {
            String isolatedHome = overrides.get("CF_HOME");
            if (isolatedHome == null || isolatedHome.isBlank()) {
                throw new IllegalArgumentException("CF_HOME is required for the isolated CF environment policy");
            }
            environment.keySet().removeIf(key -> key.equalsIgnoreCase("CF_HOME"));
            environment.put("CF_HOME", isolatedHome);
        }
    }

    private static void awaitWriter(Future<?> future) throws InterruptedException {
        try {
            future.get(1, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException ignored) {
            // Process completion/termination can close stdin while the writer is active.
        }
    }

    private static BoundedText getReaderResult(Future<BoundedText> future) throws InterruptedException {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException ex) {
            return new BoundedText("Unable to capture process output", true);
        }
    }

    /**
     * Terminates a process tree children-first and verifies every captured handle with one shared deadline per phase.
     * This method deliberately waits without propagating interruption so cleanup cannot be skipped; it restores the
     * calling thread's interrupt flag before returning.
     */
    private static boolean terminateProcessTree(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            DescendantSnapshot initialSnapshot = snapshotDescendants(process);
            List<ProcessHandle> descendants = initialSnapshot.handles();
            boolean enumerationVerified = initialSnapshot.complete();
            ProcessHandle parent = process.toHandle();

            destroyChildrenFirst(descendants, false);
            DescendantSnapshot preParentSnapshot = snapshotDescendants(process);
            enumerationVerified &= preParentSnapshot.complete();
            descendants = mergeByPid(descendants, preParentSnapshot.handles());
            destroyChildrenFirst(descendants, false);
            destroy(parent, false);
            long gracefulDeadline = deadlineAfter(GRACEFUL_TERMINATION_NANOS);
            WaitOutcome gracefulWait = waitUntilExited(descendants, parent, gracefulDeadline);
            interrupted |= gracefulWait.interrupted();

            if (!gracefulWait.allExited()) {
                // Capture once more before forcing the still-live parent so a late child cannot escape the first
                // snapshot. Existing handles are de-duplicated by PID.
                DescendantSnapshot finalSnapshot = snapshotDescendants(process);
                enumerationVerified &= finalSnapshot.complete();
                descendants = mergeByPid(descendants, finalSnapshot.handles());
                destroyChildrenFirst(descendants, true);
                destroy(parent, true);
                long forcedDeadline = deadlineAfter(FORCED_TERMINATION_NANOS);
                WaitOutcome forcedWait = waitUntilExited(descendants, parent, forcedDeadline);
                interrupted |= forcedWait.interrupted();
                return enumerationVerified && forcedWait.allExited();
            }
            return enumerationVerified;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static DescendantSnapshot snapshotDescendants(Process process) {
        try {
            return new DescendantSnapshot(process.descendants().toList(), true);
        } catch (RuntimeException ignored) {
            return new DescendantSnapshot(List.of(), false);
        }
    }

    private static List<ProcessHandle> mergeByPid(List<ProcessHandle> first, List<ProcessHandle> second) {
        List<ProcessHandle> merged = new ArrayList<>(first);
        for (ProcessHandle candidate : second) {
            boolean known = merged.stream().anyMatch(handle -> handle.pid() == candidate.pid());
            if (!known) {
                merged.add(candidate);
            }
        }
        return merged;
    }

    private static void destroyChildrenFirst(List<ProcessHandle> descendants, boolean forcibly) {
        for (int index = descendants.size() - 1; index >= 0; index--) {
            destroy(descendants.get(index), forcibly);
        }
    }

    private static void destroy(ProcessHandle handle, boolean forcibly) {
        try {
            if (handle.isAlive()) {
                if (forcibly) {
                    handle.destroyForcibly();
                } else {
                    handle.destroy();
                }
            }
        } catch (RuntimeException ignored) {
            // A failed graceful signal is retried forcibly; a failed force is reflected by verification below.
        }
    }

    private static WaitOutcome waitUntilExited(List<ProcessHandle> descendants, ProcessHandle parent,
                                                long deadlineNanos) {
        boolean interrupted = false;
        while (!allExited(descendants, parent)) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return new WaitOutcome(false, interrupted);
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TERMINATION_POLL_NANOS));
            } catch (InterruptedException ignored) {
                interrupted = true;
                // Continue within the same absolute deadline so interruption cannot bypass tree cleanup.
            }
        }
        return new WaitOutcome(true, interrupted);
    }

    private static boolean allExited(List<ProcessHandle> descendants, ProcessHandle parent) {
        if (isAlive(parent)) {
            return false;
        }
        return descendants.stream().noneMatch(JavaProcessRunner::isAlive);
    }

    private static boolean isAlive(ProcessHandle handle) {
        try {
            return handle.isAlive();
        } catch (RuntimeException ignored) {
            // Inability to query a captured handle is not proof that it exited.
            return true;
        }
    }

    private static long deadlineAfter(long durationNanos) {
        long now = System.nanoTime();
        long deadline = now + durationNanos;
        return deadline < now ? Long.MAX_VALUE : deadline;
    }

    private static BoundedText appendTerminationWarning(BoundedText stderr, int maxCharacters) {
        String warning = "Process termination could not be verified";
        String separator = stderr.text().isBlank() ? "" : System.lineSeparator();
        String combined = stderr.text() + separator + warning;
        if (combined.length() <= maxCharacters) {
            return new BoundedText(combined, stderr.truncated());
        }
        return new BoundedText(combined.substring(0, maxCharacters), true);
    }

    private static void closeProcessStreams(Process process) {
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Closing is a best-effort unblock after completion or termination.
        }
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Closing is a best-effort unblock after completion or termination.
        }
    }

    static BoundedText readBounded(InputStream inputStream, int maxCharacters) throws IOException {
        StringBuilder value = new StringBuilder(Math.min(maxCharacters, 8_192));
        boolean truncated = false;
        char[] buffer = new char[8_192];
        int read;
        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            while ((read = reader.read(buffer)) != -1) {
                int remaining = maxCharacters - value.length();
                if (remaining > 0) {
                    int toAppend = Math.min(remaining, read);
                    if (toAppend < read && Character.isHighSurrogate(buffer[toAppend - 1])
                            && Character.isLowSurrogate(buffer[toAppend])) {
                        toAppend--;
                    }
                    value.append(buffer, 0, toAppend);
                }
                if (read > remaining) {
                    truncated = true;
                }
            }
        }
        return new BoundedText(value.toString(), truncated);
    }

    private static String safeStartupMessage(IOException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "Executable could not be started";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    record BoundedText(String text, boolean truncated) {
    }

    private record WaitOutcome(boolean allExited, boolean interrupted) {
    }

    private record DescendantSnapshot(List<ProcessHandle> handles, boolean complete) {
    }
}
