package com.jmopsagent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "investigation_events")
public class InvestigationEvent {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "investigation_id", nullable = false, updatable = false)
    private Investigation investigation;

    @Column(nullable = false)
    private Instant occurredAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private InvestigationEventType type;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private InvestigationStatus status;

    @Column(nullable = false, length = 2000)
    private String message;

    public InvestigationEvent() {
        // JPA
    }

    public InvestigationEvent(InvestigationEventType type, InvestigationStatus status, String message, Instant occurredAt) {
        this.type = Objects.requireNonNull(type, "type");
        this.status = status;
        this.message = requireText(message);
        this.occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public static InvestigationEvent status(InvestigationStatus status, String message) {
        return new InvestigationEvent(InvestigationEventType.STATUS_CHANGED, Objects.requireNonNull(status, "status"),
                message == null || message.isBlank() ? status.name() : message, Instant.now());
    }

    public static InvestigationEvent note(InvestigationEventType type, String message) {
        return new InvestigationEvent(type, null, message, Instant.now());
    }

    void attachTo(Investigation investigation) {
        if (this.investigation != null && this.investigation != investigation) {
            throw new IllegalStateException("Event is already attached to another investigation");
        }
        this.investigation = Objects.requireNonNull(investigation, "investigation");
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        return value.trim();
    }

    public UUID getId() { return id; }
    public UUID getInvestigationId() { return investigation == null ? null : investigation.getId(); }
    public Instant getOccurredAt() { return occurredAt; }
    public InvestigationEventType getType() { return type; }
    public InvestigationStatus getStatus() { return status; }
    public String getMessage() { return message; }
}
