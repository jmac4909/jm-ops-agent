package com.jmopsagent.splunk;

/** Fixed, read-only tracking-ID extraction strategies; arbitrary SPL is never accepted as configuration. */
public enum SplunkTrackingIdExtraction {
    FIELD_ALIASES,
    PREFIXED_TEXT
}
