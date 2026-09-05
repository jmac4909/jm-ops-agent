package com.jmopsagent.history;

import java.util.List;

public interface HistoricalIncidentMatcher {
    List<HistoricalIncidentMatch> findMatches(HistoricalIncidentQuery query, int maximumResults);
}
