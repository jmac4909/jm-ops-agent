package com.jmopsagent.jenkins;

/** Safe, credential-free reasons a Jenkins operation can fail. */
public enum JenkinsFailureKind {
    UNCONFIGURED,
    INVALID_CONFIGURATION,
    AMBIGUOUS_CONTROLLER,
    UNKNOWN_CONTROLLER,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    TIMEOUT,
    TLS_FAILURE,
    REMOTE_FAILURE,
    RESPONSE_INVALID
}
