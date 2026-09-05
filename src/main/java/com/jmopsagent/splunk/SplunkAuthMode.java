package com.jmopsagent.splunk;

/** Authentication mechanisms accepted by the read-only Splunk connector. */
public enum SplunkAuthMode {
    /** A Splunk authentication token sent with the Bearer scheme. This is the preferred mode. */
    BEARER_TOKEN,

    /** A login/session key sent with the Splunk scheme. Prefer a scoped authentication token. */
    SESSION_KEY,

    /**
     * An explicitly configured Splunk Web session. This compatibility mode requires the complete
     * cookie set and matching anti-forgery form key; it should be replaced with BEARER_TOKEN when possible.
     */
    SESSION_CSRF
}
