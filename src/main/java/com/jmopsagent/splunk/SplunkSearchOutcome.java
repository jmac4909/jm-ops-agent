package com.jmopsagent.splunk;

/** Safe, non-sensitive outcome categories for a Splunk search. */
public enum SplunkSearchOutcome {
    SUCCESS,
    PARTIAL_PARSE,
    NO_DATA,
    LIMIT_REACHED,
    UNCONFIGURED,
    UNAUTHORIZED,
    FORBIDDEN,
    REDIRECT_REJECTED,
    TIMEOUT,
    TLS_FAILURE,
    REMOTE_FAILURE,
    PARSE_FAILURE
}
