package com.jmopsagent.ui;

import java.time.Instant;

public record TimelineEntry(Instant occurredAt, String message, String status, String type) {
}
