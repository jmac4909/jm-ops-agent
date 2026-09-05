package com.jmopsagent.connector.process;

/** Infrastructure-only boundary. Connectors expose semantic operations, never this runner. */
public interface ProcessRunner {
    ProcessResult execute(ProcessRequest request);
}
