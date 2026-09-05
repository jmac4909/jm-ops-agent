package com.jmopsagent.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "investigations")
public class Investigation {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private InvestigationType type;

    @Column(length = 160)
    private String service;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeploymentEnvironment environment;

    @Column(length = 256)
    private String trackingId;

    @Lob
    private String userProblem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private InvestigationStatus status = InvestigationStatus.CREATED;

    @Column(nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    private Instant completedAt;

    @Column(length = 256)
    private String claudeSessionId;

    private Instant claudeStartedAt;

    private Instant claudeCompletedAt;

    private Long claudeDurationMillis;

    private Integer claudeTurns;

    @Column(precision = 18, scale = 8)
    private BigDecimal claudeCostUsd;

    @Lob
    private String claudeUsageMetadata;

    @Lob
    private String claudeError;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int splunkSearchCount;

    @Lob
    private String finalDiagnosis;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ConfidenceLevel confidence = ConfidenceLevel.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private RootCauseCategory rootCauseCategory = RootCauseCategory.UNKNOWN;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "investigation_recommended_actions", joinColumns = @JoinColumn(name = "investigation_id"))
    @OrderColumn(name = "action_order")
    @Column(name = "action", length = 2000, nullable = false)
    private List<String> recommendedActions = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private FeedbackRating userFeedback;

    @Lob
    private String actualRootCause;

    @Lob
    private String successfulRemediation;

    @Lob
    private String failureReason;

    @OneToMany(mappedBy = "investigation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("occurredAt ASC, collectedAt ASC")
    private List<EvidenceItem> evidenceItems = new ArrayList<>();

    @OneToMany(mappedBy = "investigation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("occurredAt ASC")
    private List<InvestigationEvent> events = new ArrayList<>();

    @Version
    private long version;

    public Investigation() {
        // JPA
    }

    private Investigation(InvestigationType type, String service, DeploymentEnvironment environment,
                          String trackingId, String userProblem) {
        this.type = Objects.requireNonNull(type, "type");
        this.service = normalizeNullable(service);
        this.environment = Objects.requireNonNull(environment, "environment");
        this.trackingId = normalizeNullable(trackingId);
        this.userProblem = normalizeNullable(userProblem);
        validateRequiredInput();
    }

    public static Investigation forTrackingId(String trackingId, DeploymentEnvironment environment) {
        return new Investigation(InvestigationType.TRACKING_ID, null, environment, trackingId, null);
    }

    public static Investigation forServiceTriage(String service, DeploymentEnvironment environment, String userProblem) {
        return new Investigation(InvestigationType.SERVICE_TRIAGE, service, environment, null, userProblem);
    }

    public void transitionTo(InvestigationStatus nextStatus, String message) {
        Objects.requireNonNull(nextStatus, "nextStatus");
        if (status != null && status.isTerminal() && status != nextStatus) {
            throw new IllegalStateException("A terminal investigation cannot transition from " + status + " to " + nextStatus);
        }
        this.status = nextStatus;
        addEvent(InvestigationEvent.status(nextStatus, message));
    }

    public void complete(String diagnosis, ConfidenceLevel confidence, RootCauseCategory category,
                         Collection<String> actions) {
        this.finalDiagnosis = requireText(diagnosis, "diagnosis");
        this.confidence = confidence == null ? ConfidenceLevel.UNKNOWN : confidence;
        this.rootCauseCategory = category == null ? RootCauseCategory.UNKNOWN : category;
        replaceRecommendedActions(actions);
        this.completedAt = Instant.now();
        transitionTo(InvestigationStatus.COMPLETED, "Investigation completed");
    }

    /** Explicit user-approved escalation; this is the sole transition out of a terminal completed state. */
    public void beginCodeInvestigation() {
        if (status != InvestigationStatus.COMPLETED) {
            throw new IllegalStateException("Code investigation can only begin after an investigation has completed");
        }
        this.completedAt = null;
        this.status = InvestigationStatus.CODE_INVESTIGATION;
        addEvent(InvestigationEvent.status(InvestigationStatus.CODE_INVESTIGATION,
                "User requested read-only application code investigation"));
    }

    /** Keeps the supported operational result when an optional code escalation cannot proceed. */
    public void completeCodeInvestigationWithLimitation(String reason) {
        if (status != InvestigationStatus.CODE_INVESTIGATION
                && status != InvestigationStatus.ANALYZING
                && status != InvestigationStatus.NEEDS_MORE_EVIDENCE) {
            throw new IllegalStateException("No code investigation is active");
        }
        String limitation = requireText(reason, "reason");
        if (finalDiagnosis != null && !finalDiagnosis.contains("Code investigation limitation:")) {
            finalDiagnosis = finalDiagnosis + " Code investigation limitation: " + limitation;
        }
        completedAt = Instant.now();
        addEvent(InvestigationEvent.note(InvestigationEventType.ERROR, "Code investigation limitation: " + limitation));
        transitionTo(InvestigationStatus.COMPLETED, "Operational diagnosis retained; optional code investigation could not complete");
    }

    public void fail(String reason) {
        this.failureReason = requireText(reason, "reason");
        this.completedAt = Instant.now();
        transitionTo(InvestigationStatus.FAILED, reason);
    }

    public void recordClaudeExecution(String sessionId, Instant executionStartedAt, Instant executionCompletedAt,
                                      Integer turns, BigDecimal costUsd, String usageMetadata, String error) {
        String normalizedSessionId = normalizeNullable(sessionId);
        if (normalizedSessionId != null) {
            this.claudeSessionId = normalizedSessionId;
        }
        this.claudeStartedAt = executionStartedAt;
        this.claudeCompletedAt = executionCompletedAt;
        this.claudeDurationMillis = executionStartedAt != null && executionCompletedAt != null
                ? Math.max(0, executionCompletedAt.toEpochMilli() - executionStartedAt.toEpochMilli()) : null;
        this.claudeTurns = turns;
        this.claudeCostUsd = costUsd;
        this.claudeUsageMetadata = normalizeNullable(usageMetadata);
        this.claudeError = normalizeNullable(error);
    }

    public void recordFeedback(FeedbackRating feedback, String actualRootCause, String successfulRemediation) {
        this.userFeedback = Objects.requireNonNull(feedback, "feedback");
        this.actualRootCause = normalizeNullable(actualRootCause);
        this.successfulRemediation = normalizeNullable(successfulRemediation);
    }

    public void addEvidence(EvidenceItem evidenceItem) {
        Objects.requireNonNull(evidenceItem, "evidenceItem");
        evidenceItem.attachTo(this);
        evidenceItems.add(evidenceItem);
    }

    public void addEvent(InvestigationEvent event) {
        Objects.requireNonNull(event, "event");
        event.attachTo(this);
        events.add(event);
    }

    public boolean hasConfirmedOutcome() {
        return userFeedback == FeedbackRating.YES || actualRootCause != null || successfulRemediation != null;
    }

    @PrePersist
    void validateBeforePersist() {
        validateRequiredInput();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (startedAt == null) {
            startedAt = Instant.now();
        }
        if (status == null) {
            status = InvestigationStatus.CREATED;
        }
    }

    private void validateRequiredInput() {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(environment, "environment");
        if (type == InvestigationType.TRACKING_ID) {
            requireText(trackingId, "trackingId");
        } else if (type == InvestigationType.SERVICE_TRIAGE) {
            requireText(service, "service");
            requireText(userProblem, "userProblem");
        }
    }

    private void replaceRecommendedActions(Collection<String> actions) {
        recommendedActions.clear();
        if (actions != null) {
            actions.stream().map(Investigation::normalizeNullable).filter(Objects::nonNull).forEach(recommendedActions::add);
        }
    }

    private static String requireText(String value, String field) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public UUID getId() { return id; }
    public InvestigationType getType() { return type; }
    public void setType(InvestigationType type) { this.type = type; }
    public String getService() { return service; }
    public void setService(String service) { this.service = normalizeNullable(service); }
    public DeploymentEnvironment getEnvironment() { return environment; }
    public void setEnvironment(DeploymentEnvironment environment) { this.environment = environment; }
    public String getTrackingId() { return trackingId; }
    public void setTrackingId(String trackingId) { this.trackingId = normalizeNullable(trackingId); }
    public String getUserProblem() { return userProblem; }
    public void setUserProblem(String userProblem) { this.userProblem = normalizeNullable(userProblem); }
    public InvestigationStatus getStatus() { return status; }
    public void setStatus(InvestigationStatus status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getClaudeSessionId() { return claudeSessionId; }
    public void setClaudeSessionId(String claudeSessionId) { this.claudeSessionId = normalizeNullable(claudeSessionId); }
    public Instant getClaudeStartedAt() { return claudeStartedAt; }
    public Instant getClaudeCompletedAt() { return claudeCompletedAt; }
    public Long getClaudeDurationMillis() { return claudeDurationMillis; }
    public Integer getClaudeTurns() { return claudeTurns; }
    public BigDecimal getClaudeCostUsd() { return claudeCostUsd; }
    public String getClaudeUsageMetadata() { return claudeUsageMetadata; }
    public String getClaudeError() { return claudeError; }
    public int getSplunkSearchCount() { return splunkSearchCount; }
    public String getFinalDiagnosis() { return finalDiagnosis; }
    public void setFinalDiagnosis(String finalDiagnosis) { this.finalDiagnosis = normalizeNullable(finalDiagnosis); }
    public ConfidenceLevel getConfidence() { return confidence; }
    public void setConfidence(ConfidenceLevel confidence) { this.confidence = confidence; }
    public RootCauseCategory getRootCauseCategory() { return rootCauseCategory; }
    public void setRootCauseCategory(RootCauseCategory rootCauseCategory) { this.rootCauseCategory = rootCauseCategory; }
    public List<String> getRecommendedActions() { return Collections.unmodifiableList(recommendedActions); }
    public void setRecommendedActions(Collection<String> actions) { replaceRecommendedActions(actions); }
    public FeedbackRating getUserFeedback() { return userFeedback; }
    public String getActualRootCause() { return actualRootCause; }
    public String getSuccessfulRemediation() { return successfulRemediation; }
    public String getFailureReason() { return failureReason; }
    public List<EvidenceItem> getEvidenceItems() { return Collections.unmodifiableList(evidenceItems); }
    public List<InvestigationEvent> getEvents() { return Collections.unmodifiableList(events); }
    public long getVersion() { return version; }
}
