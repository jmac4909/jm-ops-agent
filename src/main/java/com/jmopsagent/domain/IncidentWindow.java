package com.jmopsagent.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Resolved historical scope, inferred from user clues or retained failure evidence. */
public record IncidentWindow(Instant start, Instant end) {
    public IncidentWindow {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("Incident start must be before incident end; provide both times");
        }
        if (Duration.between(start, end).compareTo(Duration.ofDays(366)) > 0) {
            throw new IllegalArgumentException("Choose an incident window of at most one year");
        }
    }

    public static IncidentWindow parse(String start, String end) {
        if ((start == null || start.isBlank()) && (end == null || end.isBlank())) return null;
        return new IncidentWindow(parseTime(start), parseTime(end));
    }

    private static Instant parseTime(String value) {
        if (value == null || value.isBlank() || value.length() > 50) {
            throw new IllegalArgumentException("Provide both incident start and end in UTC");
        }
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                // HTML datetime-local fields are explicitly labeled UTC.
                return LocalDateTime.parse(value.trim()).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("Incident times must be valid UTC dates and times");
            }
        }
    }

    public Instant recoveryEnd(Instant collectedAt) {
        Instant bound = end.plus(Duration.ofHours(72));
        return bound.isBefore(collectedAt) ? bound : collectedAt;
    }
}
