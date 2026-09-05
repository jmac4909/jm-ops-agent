package com.jmopsagent.connector.process;

import java.time.Duration;
import java.time.Instant;

public record ProcessResult(
        boolean started,
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean outputTruncated,
        String startupError,
        Instant startedAt,
        Instant endedAt) {

    public boolean successful() {
        return started && !timedOut && exitCode == 0;
    }

    public Duration duration() {
        return Duration.between(startedAt, endedAt);
    }
}
