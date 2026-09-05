package com.jmopsagent.config.diagnostics;

public record DiagnosticItem(String component, DiagnosticStatus status, String detail) {
}
