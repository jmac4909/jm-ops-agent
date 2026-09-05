package com.jmopsagent.connector;

import java.util.List;

public record SplunkSearchResult(
        List<ConnectorEvidence> evidence,
        List<TraceEvent> traceEvents,
        int rawResultCount,
        boolean truncated) {
    public SplunkSearchResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        traceEvents = traceEvents == null ? List.of() : List.copyOf(traceEvents);
    }
}
