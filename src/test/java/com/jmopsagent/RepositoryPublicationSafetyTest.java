package com.jmopsagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryPublicationSafetyTest {
    private static final Pattern URL = Pattern.compile("https?://[^\\s\\\"'`<>\\[\\](){}/\\\\]+(?:/[^\\s\\\"'`<>\\[\\](){}\\\\]*)?",
            Pattern.CASE_INSENSITIVE);
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("-----BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-----"),
            Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
            Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{20,}\\b"),
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\b"),
            Pattern.compile("(?i)\\b(?:authorization\\s*:\\s*(?:bearer|basic|splunk)|"
                    + "(?:api[_-]?key|access[_-]?token|client[_-]?secret|password)\\s*[:=])"
                    + "\\s*[\\\"']?[A-Za-z0-9+/_.~^=-]{32,}"));
    private static final List<Pattern> PRIVATE_NETWORK_PATTERNS = List.of(
            Pattern.compile("(?i)\\b[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.(?:corp|internal|intranet|lan|local)"
                    + "\\b(?!\\.[A-Za-z0-9])"),
            Pattern.compile("\\b10(?:\\.[0-9]{1,3}){3}\\b"),
            Pattern.compile("\\b192\\.168(?:\\.[0-9]{1,3}){2}\\b"),
            Pattern.compile("\\b172\\.(?:1[6-9]|2[0-9]|3[01])(?:\\.[0-9]{1,3}){2}\\b"));
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "127.0.0.1", "localhost", "maven.apache.org", "repo.maven.apache.org",
            "www.apache.org", "www.thymeleaf.org", "www.w3.org");

    @Test
    void publishableSourceContainsOnlyApprovedPublicHostsAndNoHighConfidenceSecrets() throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        List<String> violations = new ArrayList<>();
        List<String> denyTerms = configuredDenyTerms();
        for (Path path : publicationCandidates(root)) {
            inspectContent("publication path", relative(root, path), denyTerms, violations);
            inspect(root, path, denyTerms, violations);
        }

        assertThat(violations).as("publication-safety violations").isEmpty();
    }

    @Test
    void optionallyScansReachableGitHistoryBeforePublication() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty(
                "jmops.publication.scanHistory", "false")));
        Path root = Path.of("").toAbsolutePath().normalize();
        Assumptions.assumeTrue(isGitCheckout(root));
        String historyRef = validatedHistoryRef(System.getProperty("jmops.publication.historyRef", "--all"));
        String history = historyPublicationContent(root, historyRef);
        String references = referencePublicationContent(root, historyRef);
        List<String> violations = new ArrayList<>();
        List<String> denyTerms = configuredDenyTerms();
        inspectContent("reachable Git history", history, denyTerms, violations);
        inspectContent("reachable Git references and annotated tag metadata", references, denyTerms, violations);
        assertThat(violations).as("publication-safety history violations").isEmpty();
    }

    @Test
    void historyInputsIncludeCommitIdentityAndAnnotatedTagMetadata(@TempDir Path repository) throws Exception {
        Assumptions.assumeTrue(commandAvailable("git", "--version"));
        runGit(repository, 10, "temporary Git initialization", "init");
        runGit(repository, 10, "temporary Git author configuration",
                "config", "user.name", "Publication Metadata Sentinel");
        runGit(repository, 10, "temporary Git email configuration",
                "config", "user.email", "publication-sentinel@example.invalid");
        Files.writeString(repository.resolve("fixture.txt"), "fictional fixture\n", StandardCharsets.UTF_8);
        runGit(repository, 10, "temporary Git add", "add", "fixture.txt");
        runGit(repository, 10, "temporary Git commit", "commit", "-m", "Fictional publication fixture");
        runGit(repository, 10, "temporary annotated Git tag", "tag", "-a", "demo-publication-tag",
                "-m", "Annotated Publication Sentinel");

        assertThat(historyPublicationContent(repository, "HEAD"))
                .contains("Publication Metadata Sentinel", "publication-sentinel@example.invalid");
        assertThat(referencePublicationContent(repository, "HEAD"))
                .contains("refs/tags/demo-publication-tag", "Annotated Publication Sentinel");
    }

    private static String historyPublicationContent(Path root, String historyRef) throws Exception {
        return runGit(root, 30, "history publication scan",
                "log", "--format=%an%n%ae%n%cn%n%ce%n%B", "--no-ext-diff", "-p", historyRef);
    }

    private static String referencePublicationContent(Path root, String historyRef) throws Exception {
        return historyRef.equals("--all")
                ? runGit(root, 10, "Git reference publication scan",
                        "for-each-ref", "--format=%(refname)%n%(taggername)%n%(taggeremail)%n%(contents)")
                : runGit(root, 10, "Git reference publication scan",
                        "for-each-ref", "--format=%(refname)%n%(taggername)%n%(taggeremail)%n%(contents)",
                        "--merged=" + historyRef);
    }

    private static boolean commandAvailable(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String runGit(Path root, int timeoutSeconds, String operation, String... arguments)
            throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread reader = Thread.startVirtualThread(() -> {
            try {
                process.getInputStream().transferTo(output);
            } catch (IOException ex) {
                readFailure.set(ex);
            }
        });
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException(operation + " timed out");
        }
        reader.join(TimeUnit.SECONDS.toMillis(5));
        if (reader.isAlive() || readFailure.get() != null || process.exitValue() != 0) {
            throw new IllegalStateException(operation + " failed safely");
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String validatedHistoryRef(String value) {
        String ref = value == null ? "" : value.trim();
        if (ref.equals("--all")) return ref;
        if (!ref.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")) {
            throw new IllegalArgumentException("publication history ref is invalid");
        }
        return ref;
    }

    private static List<Path> publicationCandidates(Path root) throws Exception {
        if (!isGitCheckout(root)) return archiveCandidates(root);
        Process process = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z",
                "--cached", "--others", "--exclude-standard").start();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("git publication candidate scan timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("git publication candidate scan failed (exit "
                    + process.exitValue() + ", diagnostic bytes " + stderr.length + ")");
        }
        return Pattern.compile(String.valueOf((char) 0))
                .splitAsStream(new String(stdout, StandardCharsets.UTF_8))
                .filter(value -> !value.isBlank())
                .map(root::resolve)
                .map(Path::normalize)
                .filter(path -> path.startsWith(root))
                .filter(RepositoryPublicationSafetyTest::isPublicationFile)
                .toList();
    }

    private static List<Path> archiveCandidates(Path root) throws IOException {
        List<Path> candidates = new ArrayList<>();
        for (String relative : List.of(".github", ".mvn", "docs", "src")) {
            Path directory = root.resolve(relative);
            if (!Files.isDirectory(directory)) continue;
            try (var paths = Files.walk(directory)) {
                paths.filter(RepositoryPublicationSafetyTest::isPublicationFile)
                        .forEach(candidates::add);
            }
        }
        for (String relative : List.of(".env.example", ".gitignore", "pom.xml", "README.md",
                "WINDOWS_CLAUDE_HANDOFF.md", "mvnw", "mvnw.cmd")) {
            Path file = root.resolve(relative);
            if (isPublicationFile(file)) candidates.add(file);
        }
        return List.copyOf(candidates);
    }

    private static boolean isPublicationFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path);
    }

    private static boolean isGitCheckout(Path root) {
        return Files.exists(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
    }

    private static void inspect(Path root, Path path, List<String> denyTerms, List<String> violations) {
        try {
            if (Files.isSymbolicLink(path)) {
                inspectContent(relative(root, path) + " symbolic-link target",
                        Files.readSymbolicLink(path).toString(), denyTerms, violations);
                return;
            }
            if (Files.size(path) > 2_000_000) {
                violations.add(relative(root, path) + ": file exceeds publication inspection bound");
                return;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            inspectContent(relative(root, path), content, denyTerms, violations);
        } catch (IOException | IllegalArgumentException ex) {
            violations.add(relative(root, path) + ": could not safely inspect text");
        }
    }

    private static void inspectContent(String label, String content, List<String> denyTerms, List<String> violations) {
        try {
            for (Pattern pattern : SECRET_PATTERNS) {
                if (pattern.matcher(content).find()) {
                    violations.add(label + ": credential-like content");
                }
            }
            for (Pattern pattern : PRIVATE_NETWORK_PATTERNS) {
                if (pattern.matcher(content).find()) {
                    violations.add(label + ": private-network reference");
                }
            }
            String normalized = content.toLowerCase(Locale.ROOT);
            for (String denyTerm : denyTerms) {
                if (normalized.contains(denyTerm)) {
                    violations.add(label + ": organization-specific marker");
                }
            }
            Matcher urls = URL.matcher(content);
            while (urls.find()) {
                validateUrl(label, urls.group(), violations);
            }
        } catch (IllegalArgumentException ex) {
            violations.add(label + ": could not safely inspect text");
        }
    }

    private static void validateUrl(String label, String value, List<String> violations) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        if (uri.getUserInfo() != null || host == null || !isAllowedHost(host)) {
            violations.add(label + ": non-public or credential-bearing URL");
        }
    }

    private static List<String> configuredDenyTerms() {
        String configured = System.getenv().getOrDefault("JMOPS_PUBLICATION_DENY_TERMS", "");
        return Pattern.compile("\\|").splitAsStream(configured)
                .map(String::trim)
                .filter(term -> term.length() >= 3)
                .map(term -> term.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static boolean isAllowedHost(String value) {
        String host = value.toLowerCase(Locale.ROOT);
        return ALLOWED_HOSTS.contains(host) || host.endsWith(".invalid") || host.endsWith(".test");
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }
}
