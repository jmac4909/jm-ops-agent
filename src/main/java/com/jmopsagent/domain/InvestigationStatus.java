package com.jmopsagent.domain;

public enum InvestigationStatus {
    CREATED,
    DISCOVERING,
    COLLECTING_EVIDENCE,
    ANALYZING,
    NEEDS_MORE_EVIDENCE,
    CODE_INVESTIGATION,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
