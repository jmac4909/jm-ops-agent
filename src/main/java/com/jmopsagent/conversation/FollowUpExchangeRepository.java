package com.jmopsagent.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FollowUpExchangeRepository extends JpaRepository<FollowUpExchange, UUID> {
    List<FollowUpExchange> findByInvestigationIdOrderByAskedAtAsc(UUID investigationId);
    long countByInvestigationId(UUID investigationId);
    long countByInvestigationIdAndTargetedEvidenceRequestedTrue(UUID investigationId);
}
