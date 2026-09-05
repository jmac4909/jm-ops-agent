package com.jmopsagent.claude;

import com.jmopsagent.connector.process.ProcessRequest;
import com.jmopsagent.connector.process.ProcessEnvironmentPolicy;
import com.jmopsagent.connector.process.ProcessResult;
import com.jmopsagent.connector.process.ProcessRunner;
import com.jmopsagent.sanitization.EvidenceSanitizer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@Profile("local-live")
public class CliClaudeCodeClient implements ClaudeCodeClient {
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,256}");
    private static final Set<String> REQUIRED_SAFETY_FLAGS = Set.of(
            "--print", "--output-format", "--tools", "--disallowedTools", "--bare", "--safe-mode",
            "--system-prompt");

    private final ProcessRunner processRunner;
    private final ClaudeProperties properties;
    private final ClaudeCliCapabilityInspector capabilityInspector;
    private final ClaudePromptFactory promptFactory;
    private final ClaudeResponseParser responseParser;
    private final EvidenceSanitizer sanitizer;
    private volatile boolean schemaTransportRejected;

    public CliClaudeCodeClient(ProcessRunner processRunner, ClaudeProperties properties,
                               ClaudeCliCapabilityInspector capabilityInspector,
                               ClaudePromptFactory promptFactory,
                               ClaudeResponseParser responseParser,
                               EvidenceSanitizer sanitizer) {
        this.processRunner = processRunner;
        this.properties = properties;
        this.capabilityInspector = capabilityInspector;
        this.promptFactory = promptFactory;
        this.responseParser = responseParser;
        this.sanitizer = sanitizer;
    }

    @Override
    public ClaudeInvocationResult analyze(ClaudeReasoningRequest request) {
        Set<String> evidenceIds = new LinkedHashSet<>();
        request.evidence().forEach(item -> evidenceIds.add(item.id()));
        return invoke(promptFactory.investigationPrompt(request), request.sessionId(), evidenceIds);
    }

    @Override
    public ClaudeInvocationResult followUp(ClaudeFollowUpRequest request) {
        Set<String> evidenceIds = new LinkedHashSet<>();
        request.evidence().forEach(item -> evidenceIds.add(item.id()));
        return invoke(promptFactory.followUpPrompt(request), request.sessionId(), evidenceIds);
    }

    private ClaudeInvocationResult invoke(String prompt, String sessionId, Set<String> evidenceIds) {
        Instant start = Instant.now();
        ClaudeCliCapabilities capabilities = capabilityInspector.inspect();
        if (!capabilities.available()) {
            return failure(start, "Claude Code is unavailable: " + capabilities.reason());
        }
        if (!capabilities.flags().containsAll(REQUIRED_SAFETY_FLAGS)) {
            Set<String> missing = new LinkedHashSet<>(REQUIRED_SAFETY_FLAGS);
            missing.removeAll(capabilities.flags());
            return failure(start, "Claude Code invocation blocked because required safety flags are unavailable: " + missing);
        }
        if (sessionId != null && (!capabilities.supports("--resume") || !SESSION_ID.matcher(sessionId).matches())) {
            return failure(start, "Claude session cannot be resumed safely");
        }

        ClaudeStructuredOutputMode outputMode = properties.getStructuredOutputMode();
        boolean schemaSupported = capabilities.supports("--json-schema") && !schemaTransportRejected;
        if (outputMode == ClaudeStructuredOutputMode.SCHEMA_REQUIRED && !schemaSupported) {
            return failure(start, "Claude Code structured output schema is required but unavailable");
        }
        if (!properties.getModel().isBlank() && !capabilities.supports("--model")) {
            return failure(start, "Claude Code model override was configured but --model is unavailable");
        }
        boolean useSchema = outputMode != ClaudeStructuredOutputMode.PROMPT_ONLY && schemaSupported;
        List<String> args = arguments(capabilities, sessionId, useSchema);
        ProcessResult process = execute(args, prompt);
        if (!process.successful() && outputMode == ClaudeStructuredOutputMode.AUTO && useSchema
                && isSchemaTransportFailure(process)) {
            schemaTransportRejected = true;
            process = execute(arguments(capabilities, sessionId, false), prompt);
        }
        if (!process.successful()) {
            String reason = process.timedOut() ? "timed out" : process.startupError() != null
                    ? "could not be started" : "exited with code " + process.exitCode();
            String safeError = process.stderr() == null || process.stderr().isBlank() ? ""
                    : ": " + sanitizer.sanitize(process.stderr()).sanitizedContent();
            if (safeError.length() > 1_000) safeError = safeError.substring(0, 1_000);
            return failure(start, "Claude Code " + reason + safeError);
        }
        if (process.outputTruncated()) return failure(start, "Claude Code output exceeded the configured bound");
        return responseParser.parse(process.stdout(), start, process.endedAt(), evidenceIds);
    }

    private List<String> arguments(ClaudeCliCapabilities capabilities, String sessionId, boolean useSchema) {
        List<String> args = new ArrayList<>();
        // Bare mode prevents workstation/project hooks, skills, plugins, MCP configuration,
        // memory, and CLAUDE.md from entering this evidence-only reasoning process.
        args.add("--bare");
        args.add("-p");
        args.add("--output-format");
        args.add("json");
        args.add("--tools");
        args.add("");
        args.add("--disallowedTools");
        args.add("mcp__*");
        args.add("--safe-mode");
        if (capabilities.supports("--restricted")) args.add("--restricted");
        if (capabilities.supports("--disable-slash-commands")) args.add("--disable-slash-commands");
        if (capabilities.supports("--permission-mode")) {
            args.add("--permission-mode");
            args.add("dontAsk");
        }
        if (capabilities.supports("--permission-prompts")) {
            args.add("--permission-prompts");
            args.add("none");
        }
        if (capabilities.supports("--max-turns")) {
            args.add("--max-turns");
            args.add(Integer.toString(properties.getMaxTurnsPerInvocation()));
        }
        if (capabilities.supports("--no-chrome")) args.add("--no-chrome");
        if (!properties.getModel().isBlank()) {
            args.add("--model");
            args.add(properties.getModel());
        }
        if (useSchema) {
            args.add("--json-schema");
            args.add(ClaudeDecisionSchema.JSON);
        }
        args.add("--system-prompt");
        args.add(ClaudePromptFactory.SYSTEM_PROMPT);
        if (sessionId != null) {
            args.add("--resume");
            args.add(sessionId);
        }
        return args;
    }

    private ProcessResult execute(List<String> args, String prompt) {
        return processRunner.execute(new ProcessRequest(properties.getExecutable(), args,
                properties.getTimeout(), 1_000_000, prompt, ProcessEnvironmentPolicy.CLAUDE_VERTEX_ALLOWLIST));
    }

    private static boolean isSchemaTransportFailure(ProcessResult process) {
        String combined = ((process.stderr() == null ? "" : process.stderr()) + "\n"
                + (process.stdout() == null ? "" : process.stdout())).toLowerCase(java.util.Locale.ROOT);
        if (!combined.contains("json-schema")) return false;
        return combined.contains("not valid json") || combined.contains("invalid json")
                || combined.contains("invalid schema") || combined.contains("schema is invalid");
    }

    private ClaudeInvocationResult failure(Instant startedAt, String message) {
        Instant ended = Instant.now();
        return new ClaudeInvocationResult(null, null, startedAt, ended,
                java.time.Duration.between(startedAt, ended), null, null, java.util.Map.of(), message, false);
    }
}
