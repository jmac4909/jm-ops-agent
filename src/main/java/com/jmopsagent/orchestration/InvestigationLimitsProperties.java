package com.jmopsagent.orchestration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("jmops.limits")
public class InvestigationLimitsProperties {
    private int maxClaudeIterations = 3;
    private Duration maxWallClock = Duration.ofMinutes(2);
    private int maxEvidenceItems = 50;
    private int maxLogEvents = 200;
    private int maxSplunkSearches = 5;
    private int maxCodeFiles = 8;
    private int maxHistoricalIncidents = 5;
    private int maxFollowUps = 10;
    private int maxFollowUpEvidenceCollections = 1;
    private Duration trackingSearchWindow = Duration.ofHours(72);

    public int getMaxClaudeIterations() { return maxClaudeIterations; }
    public void setMaxClaudeIterations(int value) { this.maxClaudeIterations = bounded(value, 10, "maxClaudeIterations"); }
    public Duration getMaxWallClock() { return maxWallClock; }
    public void setMaxWallClock(Duration value) { this.maxWallClock = duration(value, "maxWallClock"); }
    public int getMaxEvidenceItems() { return maxEvidenceItems; }
    public void setMaxEvidenceItems(int value) { this.maxEvidenceItems = bounded(value, 500, "maxEvidenceItems"); }
    public int getMaxLogEvents() { return maxLogEvents; }
    public void setMaxLogEvents(int value) { this.maxLogEvents = bounded(value, 10_000, "maxLogEvents"); }
    public int getMaxSplunkSearches() { return maxSplunkSearches; }
    public void setMaxSplunkSearches(int value) { this.maxSplunkSearches = bounded(value, 20, "maxSplunkSearches"); }
    public int getMaxCodeFiles() { return maxCodeFiles; }
    public void setMaxCodeFiles(int value) { this.maxCodeFiles = bounded(value, 50, "maxCodeFiles"); }
    public int getMaxHistoricalIncidents() { return maxHistoricalIncidents; }
    public void setMaxHistoricalIncidents(int value) { this.maxHistoricalIncidents = bounded(value, 50, "maxHistoricalIncidents"); }
    public int getMaxFollowUps() { return maxFollowUps; }
    public void setMaxFollowUps(int value) { this.maxFollowUps = bounded(value, 50, "maxFollowUps"); }
    public int getMaxFollowUpEvidenceCollections() { return maxFollowUpEvidenceCollections; }
    public void setMaxFollowUpEvidenceCollections(int value) {
        this.maxFollowUpEvidenceCollections = bounded(value, 10, "maxFollowUpEvidenceCollections");
    }
    public Duration getTrackingSearchWindow() { return trackingSearchWindow; }
    public void setTrackingSearchWindow(Duration value) {
        if (value == null || value.compareTo(Duration.ofMinutes(1)) < 0
                || value.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("trackingSearchWindow must be between one minute and 30 days");
        }
        this.trackingSearchWindow = value;
    }

    private int bounded(int value, int maximum, String field) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(field + " must be between 1 and " + maximum);
        }
        return value;
    }

    private Duration duration(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException(field + " must be between zero and 30 minutes");
        }
        return value;
    }
}
