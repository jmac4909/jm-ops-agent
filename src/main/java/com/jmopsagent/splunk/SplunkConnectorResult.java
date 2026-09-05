package com.jmopsagent.splunk;

import com.jmopsagent.connector.SplunkSearchResult;
import java.util.List;
import java.util.Objects;

/** A search result paired with a typed, safe connector outcome. */
public record SplunkConnectorResult(SplunkSearchResult result, SplunkSearchOutcome outcome) {
    public SplunkConnectorResult {
        result = Objects.requireNonNullElseGet(result,
                () -> new SplunkSearchResult(List.of(), List.of(), 0, false));
        outcome = Objects.requireNonNull(outcome, "outcome");
    }

    public static SplunkConnectorResult fromLegacy(SplunkSearchResult result) {
        SplunkSearchResult safe = Objects.requireNonNullElseGet(result,
                () -> new SplunkSearchResult(List.of(), List.of(), 0, false));
        SplunkSearchOutcome outcome = safe.evidence().isEmpty() && safe.traceEvents().isEmpty()
                ? SplunkSearchOutcome.NO_DATA : SplunkSearchOutcome.SUCCESS;
        return new SplunkConnectorResult(safe, outcome);
    }

    public static SplunkConnectorResult limitReached() {
        return new SplunkConnectorResult(
                new SplunkSearchResult(List.of(), List.of(), 0, false), SplunkSearchOutcome.LIMIT_REACHED);
    }
}
