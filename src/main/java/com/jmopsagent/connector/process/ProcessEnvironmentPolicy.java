package com.jmopsagent.connector.process;

/** Controls which parent-process environment values a child process may inherit. */
public enum ProcessEnvironmentPolicy {
    /** Trusted operational CLIs keep their normal authentication environment. */
    INHERIT,
    /** CF keeps its normal runtime environment but receives one explicitly selected, isolated CF_HOME. */
    CF_CLI_ISOLATED_HOME,
    /** Claude receives only workstation/runtime and approved Vertex configuration—not connector credentials. */
    CLAUDE_VERTEX_ALLOWLIST
}
