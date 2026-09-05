package com.jmopsagent.config.diagnostics;

import java.time.Instant;
import java.util.List;

public record DiagnosticsSnapshot(Instant checkedAt, List<DiagnosticItem> items) {
    public DiagnosticsSnapshot {
        items = List.copyOf(items);
    }
}
