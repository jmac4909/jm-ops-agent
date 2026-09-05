package com.jmopsagent.claude;

import java.util.Set;

public record ClaudeCliCapabilities(boolean available, String version, Set<String> flags, String reason) {
    public ClaudeCliCapabilities {
        flags = flags == null ? Set.of() : Set.copyOf(flags);
    }

    public boolean supports(String flag) {
        return flags.contains(flag);
    }
}
