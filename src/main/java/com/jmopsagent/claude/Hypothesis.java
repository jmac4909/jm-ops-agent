package com.jmopsagent.claude;

import java.util.List;

public record Hypothesis(String cause, double confidence, List<String> evidenceIds) {

    public Hypothesis {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
