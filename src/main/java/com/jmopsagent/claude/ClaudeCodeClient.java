package com.jmopsagent.claude;

/**
 * Narrow reasoning boundary around Claude Code. Implementations receive only already-sanitized evidence
 * and never expose command or connector execution to the model.
 */
public interface ClaudeCodeClient {

    ClaudeInvocationResult analyze(ClaudeReasoningRequest request);

    ClaudeInvocationResult followUp(ClaudeFollowUpRequest request);
}
