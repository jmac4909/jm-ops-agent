package com.jmopsagent.claude;

import com.jmopsagent.connector.process.ProcessRequest;
import com.jmopsagent.connector.process.ProcessEnvironmentPolicy;
import com.jmopsagent.connector.process.ProcessResult;
import com.jmopsagent.connector.process.ProcessRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class ClaudeCliCapabilityInspector {
    private static final List<String> KNOWN_FLAGS = List.of(
            "--print", "--output-format", "--json-schema", "--resume", "--session-id",
            "--tools", "--disallowedTools", "--disable-slash-commands", "--permission-mode",
            "--permission-prompts", "--max-turns", "--bare", "--safe-mode", "--restricted",
            "--no-chrome", "--system-prompt", "--model");

    private final ProcessRunner processRunner;
    private final ClaudeProperties properties;
    private volatile ClaudeCliCapabilities cached;

    public ClaudeCliCapabilityInspector(ProcessRunner processRunner, ClaudeProperties properties) {
        this.processRunner = processRunner;
        this.properties = properties;
    }

    public ClaudeCliCapabilities inspect() {
        ClaudeCliCapabilities value = cached;
        if (value != null) return value;
        synchronized (this) {
            if (cached == null) cached = inspectNow();
            return cached;
        }
    }

    public ClaudeCliCapabilities refresh() {
        synchronized (this) {
            cached = inspectNow();
            return cached;
        }
    }

    private ClaudeCliCapabilities inspectNow() {
        if (!properties.isEnabled()) {
            return new ClaudeCliCapabilities(false, null, Set.of(), "Disabled by configuration");
        }
        ProcessResult version = processRunner.execute(new ProcessRequest(properties.getExecutable(),
                List.of("--version"), Duration.ofSeconds(10), 4_000, null,
                ProcessEnvironmentPolicy.CLAUDE_VERTEX_ALLOWLIST));
        if (!version.successful()) {
            String reason = version.startupError() != null ? "Executable not found" : "Version check failed";
            return new ClaudeCliCapabilities(false, null, Set.of(), reason);
        }
        ProcessResult help = processRunner.execute(new ProcessRequest(properties.getExecutable(),
                List.of("--help"), Duration.ofSeconds(10), 100_000, null,
                ProcessEnvironmentPolicy.CLAUDE_VERTEX_ALLOWLIST));
        if (!help.successful()) {
            return new ClaudeCliCapabilities(false, version.stdout().trim(), Set.of(), "Help inspection failed");
        }
        Set<String> flags = new LinkedHashSet<>();
        for (String flag : KNOWN_FLAGS) if (help.stdout().contains(flag)) flags.add(flag);
        return new ClaudeCliCapabilities(true, firstLine(version.stdout()), flags, null);
    }

    private String firstLine(String value) {
        if (value == null) return null;
        int line = value.indexOf('\n');
        String result = (line < 0 ? value : value.substring(0, line)).trim();
        return result.length() > 200 ? result.substring(0, 200) : result;
    }
}
