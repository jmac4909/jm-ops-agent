package com.jmopsagent.gitlab;

/** Safe failure categories; never contains endpoint, response body, or credential data. */
public enum GitLabFailureKind {
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    TIMEOUT,
    TLS_FAILURE,
    REMOTE_FAILURE
}
