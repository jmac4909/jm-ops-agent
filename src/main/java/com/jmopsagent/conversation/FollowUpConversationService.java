package com.jmopsagent.conversation;

import com.jmopsagent.claude.ClaudeCodeClient;
import com.jmopsagent.claude.BoundedReasoningEvidenceMapper;
import com.jmopsagent.claude.ClaudeFollowUpRequest;
import com.jmopsagent.claude.ClaudeInvocationResult;
import com.jmopsagent.claude.ReasoningEvidence;
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
    private final com.jmopsagent.orchestration.InvestigationOrchestrator orchestrator;

    public FollowUpConversationService(FollowUpExchangeRepository repository,
                                       InvestigationApplicationService investigations,
                                       InvestigationStateService state,
                                       ClaudeCodeClient claude,
                                       EvidenceSanitizer sanitizer,
                                       BoundedReasoningEvidenceMapper reasoningEvidenceMapper,
                                       InvestigationLimitsProperties limits,
                                       TargetedFollowUpEvidenceService targetedEvidence,
                                       com.jmopsagent.orchestration.InvestigationOrchestrator orchestrator) {
        this.repository = repository;
        this.investigations = investigations;
        this.state = state;
        this.claude = claude;
        this.sanitizer = sanitizer;
        this.reasoningEvidenceMapper = reasoningEvidenceMapper;
        this.limits = limits;
        this.targetedEvidence = targetedEvidence;
        this.orchestrator = orchestrator;
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
        java.time.Instant deadline = java.time.Instant.now().plus(limits.getMaxWallClock());
        var budget = com.jmopsagent.orchestration.EvidenceCollectionBudget.followUp(state.snapshotWithEvidence(investigationId), limits);
        var clueEvidence = orchestrator.collectFollowUpEvidence(investigationId, safeQuestion.sanitizedContent(), List.of(), deadline, budget, false);
        investigation = investigations.get(investigationId);
        int collectedItems = clueEvidence.collectedItems();
        if (clueEvidence.directAnswer() != null) {
            if (collectedItems > 0) exchange = recordTargetedEvidence(exchange.getId(), collectedItems);
            state.note(investigationId, InvestigationEventType.ANALYSIS, "Answered the follow-up from a focused lookup or requested clarification");
            return saveAnswer(exchange.getId(), sanitizer.sanitize(clueEvidence.directAnswer()));
        }
        boolean recentRequestsQuestion = requestsRecentBusinessCalls(safeQuestion.sanitizedContent())
                || investigation.isRetrospective() && requestsIncidentBusinessCalls(safeQuestion.sanitizedContent());
        TargetedFollowUpEvidenceService.CollectionResult refresh = null;
        if (recentRequestsQuestion && java.time.Instant.now().isBefore(deadline)) {
            refresh = targetedEvidence.collectRecentBusinessCalls(investigation, budget);
            collectedItems += refresh.collectedItems();
        }
        if (collectedItems > 0 || recentRequestsQuestion) exchange = recordTargetedEvidence(exchange.getId(), collectedItems);
        ClaudeInvocationResult result = null;
        String collectionContext = clueEvidence.limitation();
        if (refresh != null) collectionContext = (collectionContext == null ? "" : collectionContext + "; ") + refresh.description();
        String stopReason = null;
        for (int iteration = 1; iteration <= limits.getMaxClaudeIterations(); iteration++) {
            if (java.time.Instant.now().isAfter(deadline)) { stopReason = "Follow-up time budget reached"; break; }
            List<ReasoningEvidence> evidence = reasoningEvidenceMapper.map(investigations.evidence(investigationId));
            result = claude.followUp(new ClaudeFollowUpRequest(investigationId,
                    safeQuestion.sanitizedContent(), investigations.get(investigationId).getClaudeSessionId(),
                    investigation.getFinalDiagnosis(), evidence, collectedItems > 0, collectionContext));
            state.recordClaude(investigationId, result);
            if (!result.successful() || result.decision().status() == com.jmopsagent.claude.ReasoningStatus.COMPLETE) break;
            if (iteration == limits.getMaxClaudeIterations()) { stopReason = "Follow-up reasoning budget reached"; break; }
            List<com.jmopsagent.claude.NextEvidenceRequest> requests = result.decision().nextEvidenceRequests();
            if (result.decision().status() == com.jmopsagent.claude.ReasoningStatus.CODE_INVESTIGATION_RECOMMENDED && requests.isEmpty()) {
                requests = List.of(new com.jmopsagent.claude.NextEvidenceRequest(com.jmopsagent.claude.EvidenceRequestType.RELEVANT_CODE_FILES,
                        investigation.getService(), "Inspect source to answer the follow-up"));
            }
            var batch = orchestrator.collectFollowUpEvidence(investigationId, null, requests, deadline, budget, clueEvidence.explicitCurrent());
            collectedItems += batch.collectedItems();
            exchange = recordTargetedEvidence(exchange.getId(), collectedItems);
            collectionContext = "Collected additional approved evidence for the follow-up. "
                    + (batch.limitation() == null ? "" : batch.limitation());
            if (batch.collectedItems() == 0) { stopReason = "No additional evidence was available within the investigation's scope and budgets"; break; }
        }
        String answer = result != null && result.successful() ? result.decision().summary()
                : "The follow-up could not be answered by Claude Code. "
                + (collectionContext == null ? "No evidence was recollected." : collectionContext);
        if (stopReason != null) answer += " Evidence gap: " + stopReason;
        SanitizationResult safeAnswer = sanitizer.sanitize(answer);
        FollowUpExchange completed = saveAnswer(exchange.getId(), safeAnswer);
        String auditMessage = collectedItems == 0 && refresh == null
                ? "Answered a follow-up using stored sanitized evidence; no additional items were collected"
                : collectedItems > 0 || refresh != null && refresh.attempted()
                ? "Answered a follow-up after bounded read-only evidence collection"
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
                || normalized.contains("current") || normalized.contains("right now");
        boolean asksForTraffic = normalized.contains("request") || normalized.contains("call")
                || normalized.contains("traffic");
        return asksForTimeBoundedData && asksForTraffic;
    }

    private static boolean requestsIncidentBusinessCalls(String question) {
        String normalized = question.toLowerCase(java.util.Locale.ROOT);
        return (normalized.contains("incident") || normalized.contains("during") || normalized.contains("historical"))
                && (normalized.contains("request") || normalized.contains("call") || normalized.contains("traffic"));
    }

}
