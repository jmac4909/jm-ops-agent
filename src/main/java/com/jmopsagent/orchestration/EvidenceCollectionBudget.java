package com.jmopsagent.orchestration;

import com.jmopsagent.domain.Investigation;

/** Cumulative ceilings fixed once per user turn and shared across all collection/model iterations. */
public record EvidenceCollectionBudget(int searches, int sourceFiles, int evidenceItems) {
    public static EvidenceCollectionBudget initial(InvestigationLimitsProperties limits) {
        return new EvidenceCollectionBudget(limits.getMaxSplunkSearches(), limits.getMaxCodeFiles(), limits.getMaxEvidenceItems());
    }

    public static EvidenceCollectionBudget followUp(Investigation snapshot, InvestigationLimitsProperties limits) {
        return new EvidenceCollectionBudget(snapshot.getSplunkSearchCount() + limits.getMaxSplunkSearches(),
                snapshot.getSourceFileReadCount() + limits.getMaxCodeFiles(),
                snapshot.getEvidenceItems().size() + limits.getMaxEvidenceItems());
    }
}
