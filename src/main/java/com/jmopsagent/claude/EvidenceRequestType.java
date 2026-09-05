package com.jmopsagent.claude;

/** Approved semantic requests that the orchestrator can map to read-only connector calls. */
public enum EvidenceRequestType {
    WORKLOAD_HEALTH,
    RECENT_RUNTIME_EVENTS,
    RECENT_LOGS,
    DEPLOYMENT_METADATA,
    LATEST_DEPLOYMENT,
    RECENT_CHANGES,
    EFFECTIVE_CONFIGURATION,
    SERVICE_EVENTS,
    RECENT_ACTIVITY,
    RECENT_BUSINESS_CALLS,
    ERROR_PATTERNS,
    DEPENDENCY_EVIDENCE,
    HISTORICAL_INCIDENTS,
    RELEVANT_CODE_FILES
}
