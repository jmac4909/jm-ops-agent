package com.jmopsagent.claude;

/** Controls how the local Claude CLI is asked to produce the validated decision contract. */
public enum ClaudeStructuredOutputMode {
    /** Prefer CLI schema enforcement and safely retry prompt-only if the local launcher rejects schema transport. */
    AUTO,
    /** Require CLI schema enforcement and fail closed when it cannot be used. */
    SCHEMA_REQUIRED,
    /** Use the schema embedded in the system prompt and rely on strict local validation. */
    PROMPT_ONLY
}
