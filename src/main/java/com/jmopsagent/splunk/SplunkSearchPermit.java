package com.jmopsagent.splunk;

/**
 * Acquires one investigation-wide permit immediately before one outbound Splunk search.
 * Connectors use a permit per HTTP request so internal fallbacks cannot bypass the persisted limit.
 */
@FunctionalInterface
public interface SplunkSearchPermit {
    boolean tryAcquire();

    static SplunkSearchPermit unlimited() {
        return () -> true;
    }
}
