package com.jmopsagent.persistence;

import com.jmopsagent.domain.EvidenceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvidenceItemRepository extends JpaRepository<EvidenceItem, UUID> {
    List<EvidenceItem> findByInvestigation_IdOrderByOccurredAtAscCollectedAtAsc(UUID investigationId);
}
