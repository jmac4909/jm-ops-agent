package com.jmopsagent.history;

import com.jmopsagent.domain.EvidenceItem;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.persistence.InvestigationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class DeterministicHistoricalIncidentMatcher implements HistoricalIncidentMatcher {

    private static final int CANDIDATE_LIMIT = 200;
    private static final int HARD_RESULT_LIMIT = 20;
    private static final double MINIMUM_SCORE = 0.15;
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9/_-]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "and", "the", "for", "with", "from", "this", "that", "was", "were", "error",
            "exception", "failed", "failure", "service", "http", "into", "when", "then");

    private final InvestigationRepository investigations;

    public DeterministicHistoricalIncidentMatcher(InvestigationRepository investigations) {
        this.investigations = Objects.requireNonNull(investigations, "investigations");
    }

    @Override
    @Transactional(readOnly = true)
    public List<HistoricalIncidentMatch> findMatches(HistoricalIncidentQuery query, int maximumResults) {
        Objects.requireNonNull(query, "query");
        if (maximumResults <= 0) {
            return List.of();
        }

        Set<String> queryTokens = tokens(join(query.errorSignature(), query.context()));
        String normalizedSignature = normalize(query.errorSignature());
        List<ScoredCandidate> matches = new ArrayList<>();
        for (Investigation candidate : investigations.findByStatusOrderByCompletedAtDesc(
                InvestigationStatus.COMPLETED, PageRequest.of(0, CANDIDATE_LIMIT))) {
            score(query, queryTokens, normalizedSignature, candidate).ifPresent(matches::add);
        }

        return matches.stream()
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                        .thenComparing(ScoredCandidate::confirmed, Comparator.reverseOrder())
                        .thenComparing(candidate -> candidate.investigation().getCompletedAt(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.min(maximumResults, HARD_RESULT_LIMIT))
                .map(this::toMatch)
                .toList();
    }

    private java.util.Optional<ScoredCandidate> score(HistoricalIncidentQuery query, Set<String> queryTokens,
                                                       String normalizedSignature, Investigation candidate) {
        double score = 0;
        boolean structuredMatch = false;

        if (sameText(query.service(), candidate.getService())) {
            score += 0.32;
            structuredMatch = true;
        }
        if (query.environment() != null && query.environment() == candidate.getEnvironment()) {
            score += 0.08;
            structuredMatch = true;
        }
        if (query.category() != null && query.category() == candidate.getRootCauseCategory()) {
            score += 0.12;
            structuredMatch = true;
        }

        String candidateText = candidateText(candidate);
        Set<String> candidateTokens = tokens(candidateText);
        Set<String> matchedTerms = new LinkedHashSet<>(queryTokens);
        matchedTerms.retainAll(candidateTokens);
        if (!queryTokens.isEmpty()) {
            score += 0.30 * ((double) matchedTerms.size() / queryTokens.size());
        }
        if (normalizedSignature != null && normalize(candidateText).contains(normalizedSignature)) {
            score += 0.20;
            structuredMatch = true;
        }

        boolean confirmed = candidate.hasConfirmedOutcome();
        if (confirmed && (structuredMatch || !matchedTerms.isEmpty())) {
            score += 0.16;
        }
        score = Math.min(1, score);
        if (score < MINIMUM_SCORE || (!structuredMatch && matchedTerms.isEmpty())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ScoredCandidate(candidate, score, confirmed,
                matchedTerms.stream().sorted().toList()));
    }

    private HistoricalIncidentMatch toMatch(ScoredCandidate match) {
        Investigation incident = match.investigation();
        return new HistoricalIncidentMatch(
                incident.getId(), match.score(), match.confirmed(), incident.getService(), incident.getEnvironment(),
                incident.getRootCauseCategory(), incident.getFinalDiagnosis(), incident.getActualRootCause(),
                incident.getSuccessfulRemediation(), incident.getCompletedAt(), match.matchedTerms());
    }

    private static String candidateText(Investigation investigation) {
        StringBuilder text = new StringBuilder();
        append(text, investigation.getUserProblem());
        append(text, investigation.getFinalDiagnosis());
        append(text, investigation.getActualRootCause());
        append(text, investigation.getSuccessfulRemediation());
        investigation.getRecommendedActions().forEach(value -> append(text, value));
        for (EvidenceItem evidence : investigation.getEvidenceItems()) {
            append(text, evidence.getSummary());
            // Bounded to avoid making matching unexpectedly expensive on large historical logs.
            String content = evidence.getSanitizedContent();
            append(text, content == null || content.length() <= 2_000 ? content : content.substring(0, 2_000));
        }
        return text.toString();
    }

    private static Set<String> tokens(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String token : TOKEN_SPLIT.split(normalized)) {
            if (token.length() >= 3 && !STOP_WORDS.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private static void append(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(' ').append(value);
        }
    }

    private static String join(String first, String second) {
        return (first == null ? "" : first) + " " + (second == null ? "" : second);
    }

    private static boolean sameText(String first, String second) {
        String normalizedFirst = normalize(first);
        return normalizedFirst != null && normalizedFirst.equals(normalize(second));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private record ScoredCandidate(Investigation investigation, double score, boolean confirmed,
                                   List<String> matchedTerms) {
    }
}
