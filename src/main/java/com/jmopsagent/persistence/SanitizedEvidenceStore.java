package com.jmopsagent.persistence;

import com.jmopsagent.domain.EvidenceItem;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.sanitization.EvidenceDraft;
import com.jmopsagent.sanitization.SanitizedEvidenceItemFactory;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** Atomically sanitizes raw connector output, attaches it, and persists only the sanitized entity. */
@Service
public class SanitizedEvidenceStore {

    private final InvestigationRepository investigations;
    private final SanitizedEvidenceItemFactory evidenceFactory;

    public SanitizedEvidenceStore(InvestigationRepository investigations,
                                  SanitizedEvidenceItemFactory evidenceFactory) {
        this.investigations = Objects.requireNonNull(investigations, "investigations");
        this.evidenceFactory = Objects.requireNonNull(evidenceFactory, "evidenceFactory");
    }

    @Transactional
    public EvidenceItem append(UUID investigationId, EvidenceDraft rawEvidence) {
        Investigation investigation = investigations.findById(investigationId)
                .orElseThrow(() -> new EntityNotFoundException("Investigation not found: " + investigationId));
        EvidenceItem sanitized = evidenceFactory.create(rawEvidence);
        investigation.addEvidence(sanitized);
        investigations.save(investigation);
        return sanitized;
    }
}
