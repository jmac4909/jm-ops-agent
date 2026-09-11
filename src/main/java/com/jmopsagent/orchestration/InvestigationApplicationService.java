package com.jmopsagent.orchestration;

import com.jmopsagent.domain.EvidenceItem;
import com.jmopsagent.domain.FeedbackRating;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationEvent;
import com.jmopsagent.persistence.EvidenceItemRepository;
import com.jmopsagent.persistence.InvestigationEventRepository;
import com.jmopsagent.persistence.InvestigationRepository;
import com.jmopsagent.sanitization.EvidenceSanitizer;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class InvestigationApplicationService {
    private final InvestigationRepository investigations;
    private final EvidenceItemRepository evidenceItems;
    private final InvestigationEventRepository events;
    private final EnvironmentPolicy environmentPolicy;
    private final EvidenceSanitizer sanitizer;
    private final InvestigationClues clues;

    public InvestigationApplicationService(InvestigationRepository investigations,
                                           EvidenceItemRepository evidenceItems,
                                           InvestigationEventRepository events,
                                           EnvironmentPolicy environmentPolicy,
                                           EvidenceSanitizer sanitizer,
                                           InvestigationClues clues) {
        this.investigations = investigations;
        this.evidenceItems = evidenceItems;
        this.events = events;
        this.environmentPolicy = environmentPolicy;
        this.sanitizer = sanitizer;
        this.clues = clues;
    }

    @Transactional
    public Investigation createTrackingInvestigation(String trackingId, String environment) {
        return createTrackingInvestigation(trackingId, environment, null);
    }

    @Transactional
    public Investigation createTrackingInvestigation(String trackingId, String environment,
                                                      com.jmopsagent.domain.IncidentWindow window) {
        String hint = trackingId != null && trackingId.trim().matches("[A-Za-z0-9_.:-]{1,256}") ? trackingId.trim() : null;
        return createInvestigation(trackingId, null, hint, environment, window);
    }

    @Transactional
    public Investigation createServiceInvestigation(String service, String environment, String problem) {
        return createServiceInvestigation(service, environment, problem, null);
    }

    @Transactional
    public Investigation createServiceInvestigation(String service, String environment, String problem,
                                                     com.jmopsagent.domain.IncidentWindow window) {
        return createInvestigation(problem, service, null, environment, window);
    }

    @Transactional
    public Investigation createInvestigation(String problem, String serviceHint, String trackingHint,
                                              String environment, com.jmopsagent.domain.IncidentWindow explicitWindow) {
        String description = boundedOptional(problem, 8_000);
        if (description == null) description = boundedRequired(serviceHint == null || serviceHint.isBlank()
                ? trackingHint : serviceHint, "Issue description, service or tracking ID", 8_000);
        var resolved = clues.resolve(description, boundedOptional(serviceHint, 160), boundedOptional(trackingHint, 256),
                environment, java.time.Instant.now());
        var sanitized = sanitizer.sanitize(description);
        var env = environmentPolicy.requireAllowed(resolved.environment());
        Investigation investigation = resolved.service() != null
                ? Investigation.forServiceTriage(resolved.service(), env, sanitized.sanitizedContent())
                : Investigation.forTrackingId(resolved.trackingId(), env);
        investigation.setUserProblem(sanitized.sanitizedContent());
        investigation.setUserTrackingId(resolved.trackingId());
        investigation.setIncidentWindow(explicitWindow == null ? resolved.window() : explicitWindow);
        if (explicitWindow == null && resolved.window() != null) {
            investigation.addEvent(InvestigationEvent.note(com.jmopsagent.domain.InvestigationEventType.NOTE,
                    "Incident window inferred from the description in UTC: " + resolved.window().start() + " to " + resolved.window().end()));
        }
        if (sanitized.redactionApplied()) {
            investigation.addEvent(InvestigationEvent.note(com.jmopsagent.domain.InvestigationEventType.NOTE,
                    "Sensitive values were redacted from the submitted problem description"));
        }
        return investigations.save(investigation);
    }

    @Transactional(readOnly = true)
    public Investigation get(UUID id) {
        return investigations.findById(id).orElseThrow(() -> new EntityNotFoundException("Investigation not found"));
    }

    @Transactional(readOnly = true)
    public List<Investigation> recent(int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return investigations.findAllByOrderByStartedAtDesc(PageRequest.of(0, bounded));
    }

    @Transactional(readOnly = true)
    public List<EvidenceItem> evidence(UUID id) {
        requireExists(id);
        return evidenceItems.findByInvestigation_IdOrderByOccurredAtAscCollectedAtAsc(id);
    }

    @Transactional(readOnly = true)
    public List<InvestigationEvent> timeline(UUID id) {
        requireExists(id);
        return events.findByInvestigation_IdOrderByOccurredAtAsc(id);
    }

    @Transactional
    public Investigation recordFeedback(UUID id, String rating, String actualRootCause, String remediation) {
        Investigation investigation = requireExists(id);
        if (investigation.getStatus() != com.jmopsagent.domain.InvestigationStatus.COMPLETED) {
            throw new IllegalStateException("Feedback is available only after an investigation completes");
        }
        FeedbackRating feedback;
        try {
            feedback = FeedbackRating.valueOf(boundedRequired(rating, "Feedback", 16).toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Feedback must be Yes, No, or Partially");
        }
        String actual = boundedOptional(actualRootCause, 8_000);
        if (feedback != FeedbackRating.YES && actual == null) {
            throw new IllegalArgumentException("Actual root cause is required for No or Partially feedback");
        }
        var safeActual = sanitizer.sanitize(actual);
        var safeRemediation = sanitizer.sanitize(boundedOptional(remediation, 8_000));
        investigation.recordFeedback(feedback,
                actual == null ? null : safeActual.sanitizedContent(),
                safeRemediation.sanitizedContent().isBlank() ? null : safeRemediation.sanitizedContent());
        if (safeActual.redactionApplied() || safeRemediation.redactionApplied()) {
            investigation.addEvent(InvestigationEvent.note(com.jmopsagent.domain.InvestigationEventType.NOTE,
                    "Sensitive values were redacted from submitted feedback"));
        }
        return investigations.save(investigation);
    }

    private Investigation requireExists(UUID id) {
        return investigations.findById(id).orElseThrow(() -> new EntityNotFoundException("Investigation not found"));
    }

    private String boundedRequired(String value, String label, int max) {
        String result = boundedOptional(value, max);
        if (result == null) throw new IllegalArgumentException(label + " is required");
        return result;
    }

    private String boundedOptional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > max) throw new IllegalArgumentException("Input exceeds " + max + " characters");
        return result;
    }
}
