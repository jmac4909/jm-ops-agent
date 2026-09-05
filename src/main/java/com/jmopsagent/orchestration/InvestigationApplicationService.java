package com.jmopsagent.orchestration;

import com.jmopsagent.domain.EvidenceItem;
import com.jmopsagent.domain.FeedbackRating;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationEvent;
import com.jmopsagent.persistence.EvidenceItemRepository;
import com.jmopsagent.persistence.InvestigationEventRepository;
import com.jmopsagent.persistence.InvestigationRepository;
import com.jmopsagent.connector.ConnectorInputValidator;
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

    public InvestigationApplicationService(InvestigationRepository investigations,
                                           EvidenceItemRepository evidenceItems,
                                           InvestigationEventRepository events,
                                           EnvironmentPolicy environmentPolicy,
                                           EvidenceSanitizer sanitizer) {
        this.investigations = investigations;
        this.evidenceItems = evidenceItems;
        this.events = events;
        this.environmentPolicy = environmentPolicy;
        this.sanitizer = sanitizer;
    }

    @Transactional
    public Investigation createTrackingInvestigation(String trackingId, String environment) {
        String id = ConnectorInputValidator.trackingId(boundedRequired(trackingId, "Tracking ID", 256));
        return investigations.save(Investigation.forTrackingId(id, environmentPolicy.requireAllowed(environment)));
    }

    @Transactional
    public Investigation createServiceInvestigation(String service, String environment, String problem) {
        String serviceName = ConnectorInputValidator.service(boundedRequired(service, "Service", 160));
        String description = boundedRequired(problem, "Problem description", 8_000);
        var sanitized = sanitizer.sanitize(description);
        Investigation investigation = Investigation.forServiceTriage(serviceName,
                environmentPolicy.requireAllowed(environment), sanitized.sanitizedContent());
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
