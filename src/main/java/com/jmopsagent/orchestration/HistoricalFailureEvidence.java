package com.jmopsagent.orchestration;

import com.jmopsagent.domain.EvidenceItem;
import com.jmopsagent.domain.EvidenceSource;
import com.jmopsagent.domain.EvidenceType;
import com.jmopsagent.domain.IncidentWindow;
import java.util.regex.Pattern;

/** Concrete log signals only; repository prose and names such as failedCount are not failures. */
final class HistoricalFailureEvidence {
    private static final Pattern FAILURE = Pattern.compile(
            "(?i)(?:\\[ERROR\\]|\\bERROR\\s*[:\\]]|"
                    + "\\bseverity[\\s\"':=]+(?:ERROR|FATAL)\\b|\\boutcome[\\s\"':=]+(?:FAILURE|FAILED|ERROR)\\b|"
                    + "\\b(?:http(?:status)?|statuscode)[\\s\"':=]+5\\d\\d\\b|"
                    + "\\bhttpStatusClass[\\s\"':=]+5xx\\b|"
                    + "(?<!no )\\b[a-zA-Z0-9_$]*Exception\\b|\\bconnection (?:refused|timed out)\\b)");

    private HistoricalFailureEvidence() {}

    static boolean isFailure(EvidenceItem item, IncidentWindow window) {
        var time = item.getOccurredAt();
        if (item.isSupersededByScopeChange() || time == null || time.isBefore(window.start()) || !time.isBefore(window.end())
                || item.getSourceSystem() != EvidenceSource.SPLUNK) return false;
        if (item.getEvidenceType() != EvidenceType.ERROR_LOG && item.getEvidenceType() != EvidenceType.POD_LOG
                && item.getEvidenceType() != EvidenceType.LOG_PATTERN
                && item.getEvidenceType() != EvidenceType.CALL_CHAIN && item.getEvidenceType() != EvidenceType.TRACKING_EVENT
                && item.getEvidenceType() != EvidenceType.RECENT_BUSINESS_CALLS) return false;
        return hasSignal(item.getSummary() + " " + item.getSanitizedContent() + " " + item.getMetadataJson());
    }

    static boolean hasSignal(String value) { return value != null && FAILURE.matcher(value).find(); }

    static boolean belongsTo(EvidenceItem item, String service) {
        if (service == null) return false;
        if (service.equals(item.getService())) return true;
        return item.getEvidenceType() == EvidenceType.CALL_CHAIN && item.getMetadataJson() != null
                && Pattern.compile("\"downstreamService\"\\s*:\\s*\"" + Pattern.quote(service) + "\"")
                        .matcher(item.getMetadataJson()).find();
    }
}
