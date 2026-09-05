package com.jmopsagent.conversation;

import com.jmopsagent.claude.ClaudeCodeClient;
import com.jmopsagent.claude.BoundedReasoningEvidenceMapper;
import com.jmopsagent.claude.ClaudeFollowUpRequest;
import com.jmopsagent.claude.ClaudeInvocationResult;
import com.jmopsagent.claude.ReasoningEvidence;
import com.jmopsagent.domain.EvidenceItem;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationEventType;
import com.jmopsagent.orchestration.InvestigationApplicationService;
import com.jmopsagent.orchestration.InvestigationStateService;
import com.jmopsagent.orchestration.InvestigationLimitsProperties;
import com.jmopsagent.sanitization.EvidenceSanitizer;
import com.jmopsagent.sanitization.SanitizationResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class FollowUpConversationService {
    private final FollowUpExchangeRepository repository;
    private final InvestigationApplicationService investigations;
    private final InvestigationStateService state;
    private final ClaudeCodeClient claude;
    private final EvidenceSanitizer sanitizer;
    private final BoundedReasoningEvidenceMapper reasoningEvidenceMapper;
    private final InvestigationLimitsProperties limits;
    private final TargetedFollowUpEvidenceService targetedEvidence;

    public FollowUpConversationService(FollowUpExchangeRepository repository,
                                       InvestigationApplicationService investigations,
                                       InvestigationStateService state,
                                       ClaudeCodeClient claude,
                                       EvidenceSanitizer sanitizer,
                                       BoundedReasoningEvidenceMapper reasoningEvidenceMapper,
                                       InvestigationLimitsProperties limits,
                                       TargetedFollowUpEvidenceService targetedEvidence) {
        this.repository = repository;
        this.investigations = investigations;
        this.state = state;
        this.claude = claude;
        this.sanitizer = sanitizer;
        this.reasoningEvidenceMapper = reasoningEvidenceMapper;
        this.limits = limits;
        this.targetedEvidence = targetedEvidence;
    }

    public FollowUpExchange ask(UUID investigationId, String rawQuestion) {
        if (rawQuestion == null || rawQuestion.isBlank()) throw new IllegalArgumentException("Follow-up question is required");
        if (rawQuestion.length() > 2_000) throw new IllegalArgumentException("Follow-up question exceeds 2000 characters");
        Investigation investigation = investigations.get(investigationId);
        if (investigation.getStatus() != com.jmopsagent.domain.InvestigationStatus.COMPLETED) {
            throw new IllegalStateException("Follow-up questions are available after an investigation completes");
        }
        if (repository.countByInvestigationId(investigationId) >= limits.getMaxFollowUps()) {
            throw new IllegalStateException("The configured follow-up limit has been reached for this investigation");
        }
        SanitizationResult safeQuestion = sanitizer.sanitize(rawQuestion);
        FollowUpExchange exchange = saveQuestion(investigationId, safeQuestion);
        boolean recentRequestsQuestion = requestsRecentBusinessCalls(safeQuestion.sanitizedContent());
        TargetedFollowUpEvidenceService.CollectionResult refresh = null;
        if (recentRequestsQuestion) {
            long priorRefreshes = repository.countByInvestigationIdAndTargetedEvidenceRequestedTrue(investigationId);
            if (priorRefreshes < limits.getMaxFollowUpEvidenceCollections()) {
                refresh = targetedEvidence.collectRecentBusinessCalls(investigation);
            } else {
                refresh = new TargetedFollowUpEvidenceService.CollectionResult(false, 0,
                        "The targeted follow-up evidence refresh limit was reached");
                state.note(investigationId, InvestigationEventType.LIMIT_REACHED,
                        "Skipped a requested recent-request refresh because its follow-up limit was reached");
            }
            exchange = recordTargetedEvidence(exchange.getId(), refresh.collectedItems());
        }
        List<EvidenceItem> evidenceItems = investigations.evidence(investigationId);
        List<ReasoningEvidence> evidence = reasoningEvidenceMapper.map(evidenceItems);
        ClaudeInvocationResult result = claude.followUp(new ClaudeFollowUpRequest(investigationId,
                safeQuestion.sanitizedContent(), investigation.getClaudeSessionId(), investigation.getFinalDiagnosis(), evidence,
                refresh != null && refresh.collectedItems() > 0, refresh == null ? null : refresh.description()));
        state.recordClaude(investigationId, result);
        String answer = result.successful() ? result.decision().summary()
                : "The follow-up could not be answered by Claude Code. "
                + (refresh == null ? "No live evidence was recollected." : refresh.description());
        SanitizationResult safeAnswer = sanitizer.sanitize(answer);
        FollowUpExchange completed = saveAnswer(exchange.getId(), safeAnswer);
        String auditMessage = refresh == null
                ? "Answered a follow-up using stored sanitized evidence; no connectors were queried"
                : refresh.attempted()
                ? "Answered a follow-up after the explicitly requested bounded read-only refresh"
                : "Answered a follow-up using stored evidence; the requested refresh was skipped by a configured limit";
        state.note(investigationId, InvestigationEventType.ANALYSIS, auditMessage);
        return completed;
    }

    @Transactional(readOnly = true)
    public List<FollowUpExchange> list(UUID investigationId) {
        return repository.findByInvestigationIdOrderByAskedAtAsc(investigationId);
    }

    @Transactional
    protected FollowUpExchange saveQuestion(UUID investigationId, SanitizationResult result) {
        return repository.save(new FollowUpExchange(investigationId, result.sanitizedContent(), result.redactionApplied()));
    }

    @Transactional
    protected FollowUpExchange saveAnswer(UUID id, SanitizationResult result) {
        FollowUpExchange exchange = repository.findById(id).orElseThrow();
        exchange.answer(result.sanitizedContent(), result.redactionApplied());
        return repository.save(exchange);
    }

    @Transactional
    protected FollowUpExchange recordTargetedEvidence(UUID id, int collectedItems) {
        FollowUpExchange exchange = repository.findById(id).orElseThrow();
        exchange.recordTargetedEvidenceRequest(collectedItems);
        return repository.save(exchange);
    }

    static boolean requestsRecentBusinessCalls(String question) {
        if (question == null || question.isBlank()) return false;
        String normalized = question.toLowerCase(java.util.Locale.ROOT);
        boolean asksForTimeBoundedData = normalized.contains("recent") || normalized.contains("latest")
                || normalized.contains("currently") || normalized.contains("right now");
        boolean asksForTraffic = normalized.contains("request") || normalized.contains("call")
                || normalized.contains("traffic");
        return asksForTimeBoundedData && asksForTraffic;
    }

}
