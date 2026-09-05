package com.jmopsagent.connector;

import java.time.Instant;
import java.util.List;

public record CommitChange(
        String commitSha,
        String title,
        String author,
        Instant committedAt,
        List<String> changedPaths,
        String boundedDiff) {
    public CommitChange {
        changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
        boundedDiff = boundedDiff == null ? "" : boundedDiff;
    }
}
