package com.jmopsagent.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "investigation_follow_ups")
public class FollowUpExchange {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, updatable = false)
    private UUID investigationId;

    @Lob
    @Column(nullable = false)
    private String question;

    @Lob
    private String answer;

    @Column(nullable = false)
    private Instant askedAt = Instant.now();

    private Instant answeredAt;

    @Column(nullable = false)
    private boolean redactionApplied;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean targetedEvidenceRequested;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int targetedEvidenceItems;

    protected FollowUpExchange() {}

    public FollowUpExchange(UUID investigationId, String question, boolean redactionApplied) {
        this.investigationId = investigationId;
        this.question = question;
        this.redactionApplied = redactionApplied;
    }

    public void answer(String value, boolean responseRedacted) {
        this.answer = value;
        this.answeredAt = Instant.now();
        this.redactionApplied = this.redactionApplied || responseRedacted;
    }

    public void recordTargetedEvidenceRequest(int collectedItems) {
        this.targetedEvidenceRequested = true;
        this.targetedEvidenceItems = Math.max(0, collectedItems);
    }

    public UUID getId() { return id; }
    public UUID getInvestigationId() { return investigationId; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public Instant getAskedAt() { return askedAt; }
    public Instant getAnsweredAt() { return answeredAt; }
    public boolean isRedactionApplied() { return redactionApplied; }
    public boolean isTargetedEvidenceRequested() { return targetedEvidenceRequested; }
    public int getTargetedEvidenceItems() { return targetedEvidenceItems; }
}
