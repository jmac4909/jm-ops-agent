package com.jmopsagent.connector;

import java.time.Duration;
import java.time.Instant;

/** Bounded evidence window supplied to connector operations. */
public record EvidenceQuery(Instant from, Instant to, int maxResults, int maxContentCharacters) {
    public EvidenceQuery {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("A valid evidence time window is required");
        }
        if (maxResults < 1 || maxResults > 1_000) {
            throw new IllegalArgumentException("maxResults must be between 1 and 1000");
        }
        if (maxContentCharacters < 1 || maxContentCharacters > 1_000_000) {
            throw new IllegalArgumentException("maxContentCharacters must be between 1 and 1000000");
        }
    }

    public static EvidenceQuery recent(Duration duration, int maxResults) {
        Instant now = Instant.now();
        return new EvidenceQuery(now.minus(duration), now, maxResults, 50_000);
    }
}
