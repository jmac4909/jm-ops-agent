package com.jmopsagent.sanitization;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "jmops.sanitization")
public class SanitizationProperties {

    /** False strips common request/response body and payload forms before evidence is persisted or analyzed. */
    private boolean includeBodies;

    private int maxContentCharacters = 50_000;

    /** Additional named regular expressions; their entire match is replaced. */
    private Map<String, String> additionalPatterns = new LinkedHashMap<>();

    public boolean isIncludeBodies() {
        return includeBodies;
    }

    public void setIncludeBodies(boolean includeBodies) {
        this.includeBodies = includeBodies;
    }

    public int getMaxContentCharacters() {
        return maxContentCharacters;
    }

    public void setMaxContentCharacters(int maxContentCharacters) {
        if (maxContentCharacters < 1_000 || maxContentCharacters > 500_000) {
            throw new IllegalArgumentException("maxContentCharacters must be between 1000 and 500000");
        }
        this.maxContentCharacters = maxContentCharacters;
    }

    public Map<String, String> getAdditionalPatterns() {
        return additionalPatterns;
    }

    public void setAdditionalPatterns(Map<String, String> additionalPatterns) {
        this.additionalPatterns = additionalPatterns == null ? new LinkedHashMap<>() : new LinkedHashMap<>(additionalPatterns);
    }
}
