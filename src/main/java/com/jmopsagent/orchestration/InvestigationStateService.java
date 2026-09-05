package com.jmopsagent.orchestration;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.jmopsagent.claude.ClaudeInvocationResult;
import com.jmopsagent.claude.Hypothesis;
import com.jmopsagent.claude.ReasoningDecision;
import com.jmopsagent.domain.ConfidenceLevel;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationEvent;
import com.jmopsagent.domain.InvestigationEventType;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.persistence.InvestigationRepository;
import com.jmopsagent.sanitization.EvidenceSanitizer;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class InvestigationStateService {
    private final InvestigationRepository investigations;
    private final ObjectMapper objectMapper;
    private final EvidenceSanitizer sanitizer;

    public InvestigationStateService(InvestigationRepository investigations, ObjectMapper objectMapper,
                                     EvidenceSanitizer sanitizer) {
        this.investigations = investigations;
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
    }

    @Transactional
    public void transition(UUID id, InvestigationStatus status, String message) {
        Investigation investigation = get(id);
        investigation.transitionTo(status, message);
        investigations.save(investigation);
    }

    @Transactional
    public void note(UUID id, InvestigationEventType type, String message) {
        Investigation investigation = get(id);
        investigation.addEvent(InvestigationEvent.note(type, message));
        investigations.save(investigation);
    }

    @Transactional
    public void localizeService(UUID id, String service, String reason) {
        Investigation investigation = get(id);
        investigation.setService(service);
        investigation.addEvent(InvestigationEvent.note(InvestigationEventType.ANALYSIS, reason));
        investigations.save(investigation);
    }

    @Transactional
    public void recordClaude(UUID id, ClaudeInvocationResult result) {
        Investigation investigation = get(id);
        String safeUsage = sanitizer.sanitize(json(result.usage())).sanitizedContent();
        String safeError = result.error() == null ? null : sanitizer.sanitize(result.error()).sanitizedContent();
        investigation.recordClaudeExecution(result.sessionId(), result.startedAt(), result.completedAt(),
                result.numberOfTurns(), result.totalCostUsd(), safeUsage, safeError);
        investigations.save(investigation);
    }

    /** Atomically reserves one logical Splunk connector call against the persisted investigation budget. */
    @Transactional
    public boolean tryReserveSplunkSearch(UUID id, int maximum) {
        if (maximum < 1) return false;
        return investigations.reserveSplunkSearch(id, maximum) == 1;
    }

    @Transactional
    public void complete(UUID id, ReasoningDecision decision, String limitation) {
        Investigation investigation = get(id);
        double best = decision.hypotheses().stream().mapToDouble(Hypothesis::confidence).max().orElse(0.0);
        ConfidenceLevel confidence = best >= .75 ? ConfidenceLevel.HIGH : best >= .45 ? ConfidenceLevel.MEDIUM
                : best > 0 ? ConfidenceLevel.LOW : ConfidenceLevel.UNKNOWN;
        String diagnosis = limitation == null ? decision.summary() : decision.summary() + " Limitation: " + limitation;
        String safeDiagnosis = sanitizer.sanitize(diagnosis).sanitizedContent();
        var safeActions = decision.recommendedActions().stream()
                .map(action -> sanitizer.sanitize(action).sanitizedContent()).toList();
        investigation.complete(safeDiagnosis, confidence, decision.rootCauseCategory(), safeActions);
        investigations.save(investigation);
    }

    @Transactional
    public void fail(UUID id, String reason) {
        Investigation investigation = get(id);
        if (!investigation.getStatus().isTerminal()) investigation.fail(safeReason(reason));
        investigations.save(investigation);
    }

    @Transactional
    public void beginCodeInvestigation(UUID id) {
        Investigation investigation = get(id);
        investigation.beginCodeInvestigation();
        investigations.save(investigation);
    }

    @Transactional
    public void completeCodeInvestigationWithLimitation(UUID id, String reason) {
        Investigation investigation = get(id);
        String safe = sanitizer.sanitize(reason).sanitizedContent();
        investigation.completeCodeInvestigationWithLimitation(safe.length() > 1_000 ? safe.substring(0, 1_000) : safe);
        investigations.save(investigation);
    }

    @Transactional(readOnly = true)
    public Investigation snapshotWithEvidence(UUID id) {
        return investigations.findWithEvidenceById(id)
                .orElseThrow(() -> new EntityNotFoundException("Investigation not found"));
    }

    private Investigation get(UUID id) {
        return investigations.findById(id).orElseThrow(() -> new EntityNotFoundException("Investigation not found"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            return "{}";
        }
    }

    private String safeReason(String value) {
        String message = value == null || value.isBlank() ? "Investigation failed" : value;
        String safe = sanitizer.sanitize(message).sanitizedContent();
        return safe.length() > 2_000 ? safe.substring(0, 2_000) : safe;
    }
}
