package com.jmopsagent.persistence;

import com.jmopsagent.domain.InvestigationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InvestigationEventRepository extends JpaRepository<InvestigationEvent, UUID> {
    List<InvestigationEvent> findByInvestigation_IdOrderByOccurredAtAsc(UUID investigationId);
}
