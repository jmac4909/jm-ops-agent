package com.jmopsagent.connector;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

public record TraceEvent(
        Instant timestamp,
        String trackingId,
        String service,
        String operation,
        String outcome,
        Integer httpStatus,
        String downstreamService,
        String summary,
        URI sourceUrl,
        Map<String, String> metadata) {

    public TraceEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
