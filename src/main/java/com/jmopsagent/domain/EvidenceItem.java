package com.jmopsagent.domain;

import com.jmopsagent.sanitization.SanitizationResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Persisted evidence. This entity intentionally has no raw-content field. Prefer creating it through
 * {@code SanitizedEvidenceItemFactory}, which sanitizes every untrusted textual field.
 */
@Entity
@Table(name = "evidence_items")
public class EvidenceItem {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "investigation_id", nullable = false, updatable = false)
    private Investigation investigation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private EvidenceSource sourceSystem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private EvidenceType evidenceType;

    private Instant occurredAt;

    @Column(length = 160)
    private String service;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private DeploymentEnvironment environment;

    @Column(nullable = false, length = 2000)
    private String summary;

    @Lob
    @Column(nullable = false)
    private String sanitizedContent;

    @Column(length = 2000)
    private String sourceUrl;

    @Lob
    private String metadataJson;

    @Column(nullable = false)
    private Instant collectedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EvidenceReliability reliability = EvidenceReliability.UNKNOWN;

    @Column(nullable = false)
    private boolean redactionApplied;

    @Column(nullable = false)
    private int redactionCount;

    @Column(length = 1000)
    private String redactionCategories;

    @Column(nullable = false)
    private boolean contentTruncated;

    public EvidenceItem() {
        // JPA
    }

    /**
     * Convenience factory for a system-generated summary/URL/metadata and sanitized untrusted content.
     * Use SanitizedEvidenceItemFactory when any of the other textual fields originates externally.
     */
    public static EvidenceItem create(EvidenceSource sourceSystem, EvidenceType evidenceType, Instant occurredAt,
                                      String service, DeploymentEnvironment environment, String trustedSummary,
                                      SanitizationResult sanitizedContent, String trustedSourceUrl,
                                      String trustedMetadataJson, EvidenceReliability reliability) {
        SanitizationResult summary = alreadySafe(trustedSummary);
        SanitizationResult sourceUrl = alreadySafe(trustedSourceUrl);
        SanitizationResult metadata = alreadySafe(trustedMetadataJson);
        return createSanitized(sourceSystem, evidenceType, occurredAt, service, environment, summary,
                sanitizedContent, sourceUrl, metadata, reliability);
    }

    public static EvidenceItem createSanitized(EvidenceSource sourceSystem, EvidenceType evidenceType,
                                               Instant occurredAt, String service,
                                               DeploymentEnvironment environment, SanitizationResult summary,
                                               SanitizationResult content, SanitizationResult sourceUrl,
                                               SanitizationResult metadata, EvidenceReliability reliability) {
        EvidenceItem item = new EvidenceItem();
        item.sourceSystem = Objects.requireNonNull(sourceSystem, "sourceSystem");
        item.evidenceType = Objects.requireNonNull(evidenceType, "evidenceType");
        item.occurredAt = occurredAt;
        item.service = normalizeNullable(service);
        item.environment = environment;
        item.summary = limit(requireText(result(summary).sanitizedContent(), "summary"), 2000);
        item.sanitizedContent = result(content).sanitizedContent();
        item.sourceUrl = limit(normalizeNullable(result(sourceUrl).sanitizedContent()), 2000);
        item.metadataJson = normalizeNullable(result(metadata).sanitizedContent());
        item.reliability = reliability == null ? EvidenceReliability.UNKNOWN : reliability;

        Set<String> categories = new LinkedHashSet<>();
        int redactions = 0;
        boolean truncated = false;
        boolean redacted = false;
        for (SanitizationResult sanitization : new SanitizationResult[]{result(summary), result(content),
                result(sourceUrl), result(metadata)}) {
            redactions += sanitization.redactionCount();
            redacted = redacted || sanitization.redactionApplied();
            categories.addAll(sanitization.redactionCategories());
            truncated = truncated || sanitization.truncated();
        }
        item.redactionCount = redactions;
        item.redactionApplied = redacted || redactions > 0;
        item.redactionCategories = categories.isEmpty() ? null : String.join(",", categories);
        item.contentTruncated = truncated;
        return item;
    }

    void attachTo(Investigation investigation) {
        if (this.investigation != null && this.investigation != investigation) {
            throw new IllegalStateException("Evidence is already attached to another investigation");
        }
        this.investigation = Objects.requireNonNull(investigation, "investigation");
    }

    private static SanitizationResult result(SanitizationResult result) {
        return result == null ? alreadySafe(null) : result;
    }

    private static SanitizationResult alreadySafe(String value) {
        return new SanitizationResult(value == null ? "" : value, false, 0, List.of(), false);
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

    private static String limit(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public UUID getId() { return id; }
    public UUID getInvestigationId() { return investigation == null ? null : investigation.getId(); }
    public EvidenceSource getSourceSystem() { return sourceSystem; }
    public EvidenceType getEvidenceType() { return evidenceType; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getService() { return service; }
    public DeploymentEnvironment getEnvironment() { return environment; }
    public String getSummary() { return summary; }
    public String getSanitizedContent() { return sanitizedContent; }
    public String getSourceUrl() { return sourceUrl; }
    public String getMetadataJson() { return metadataJson; }
    public Instant getCollectedAt() { return collectedAt; }
    public EvidenceReliability getReliability() { return reliability; }
    public boolean isRedactionApplied() { return redactionApplied; }
    public int getRedactionCount() { return redactionCount; }
    public String getRedactionCategories() { return redactionCategories; }
    public boolean isContentTruncated() { return contentTruncated; }
}
