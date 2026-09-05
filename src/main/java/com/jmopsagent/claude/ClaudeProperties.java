package com.jmopsagent.claude;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.regex.Pattern;

@ConfigurationProperties("jmops.claude")
public class ClaudeProperties {
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,199}");
    private String executable = "claude";
    private boolean enabled = true;
    private Duration timeout = Duration.ofMinutes(2);
    private int maxTurnsPerInvocation = 1;
    private String model = "";
    private ClaudeStructuredOutputMode structuredOutputMode = ClaudeStructuredOutputMode.AUTO;

    public String getExecutable() { return executable; }
    public void setExecutable(String executable) {
        if (executable == null || executable.isBlank() || executable.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Claude executable is invalid");
        }
        this.executable = executable;
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Claude timeout must be between zero and five minutes");
        }
        this.timeout = timeout;
    }
    public int getMaxTurnsPerInvocation() { return maxTurnsPerInvocation; }
    public void setMaxTurnsPerInvocation(int maxTurnsPerInvocation) {
        if (maxTurnsPerInvocation < 1 || maxTurnsPerInvocation > 20) {
            throw new IllegalArgumentException("Claude max turns must be between 1 and 20");
        }
        this.maxTurnsPerInvocation = maxTurnsPerInvocation;
    }
    public String getModel() { return model; }
    public void setModel(String model) {
        String value = model == null ? "" : model.trim();
        if (!value.isEmpty() && !MODEL.matcher(value).matches()) {
            throw new IllegalArgumentException("Claude model identifier is invalid");
        }
        this.model = value;
    }
    public ClaudeStructuredOutputMode getStructuredOutputMode() { return structuredOutputMode; }
    public void setStructuredOutputMode(ClaudeStructuredOutputMode structuredOutputMode) {
        if (structuredOutputMode == null) {
            throw new IllegalArgumentException("Claude structured output mode is required");
        }
        this.structuredOutputMode = structuredOutputMode;
    }
}
