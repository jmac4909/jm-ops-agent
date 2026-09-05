package com.jmopsagent.claude;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeResponseParserTest {
    private final ClaudeResponseParser parser = new ClaudeResponseParser(JsonMapper.builder().build());

    @Test
    void acceptsSchemaConformingEnvelopeAndCapturesMetadata() {
        String value = """
                {"session_id":"8f0d7f20-0de0-4b72-a29a-31ffabf95f20","num_turns":1,"total_cost_usd":0.01,
                 "usage":{"input_tokens":120},"structured_output":{
                   "status":"COMPLETE","summary":"Bad configuration","hypotheses":[
                     {"cause":"Wrong parameter path","confidence":0.9,"evidenceIds":["e-1"]}],
                   "nextEvidenceRequests":[],"rootCauseCategory":"CONFIG","recommendedActions":["Verify path"]}}
                """;
        Instant start = Instant.now();

        ClaudeInvocationResult result = parser.parse(value, start, start.plusMillis(20), Set.of("e-1"));

        assertThat(result.successful()).isTrue();
        assertThat(result.sessionId()).startsWith("8f0d");
        assertThat(result.decision().status()).isEqualTo(ReasoningStatus.COMPLETE);
        assertThat(result.numberOfTurns()).isOne();
    }

    @Test
    void rejectsInventedEvidenceReferencesAndUnknownRequestTypes() {
        String inventedReference = """
                {"structured_output":{"status":"COMPLETE","summary":"claim","hypotheses":[
                 {"cause":"claim","confidence":0.9,"evidenceIds":["invented"]}],"nextEvidenceRequests":[],
                 "rootCauseCategory":"UNKNOWN","recommendedActions":[]}}
                """;
        String inventedRequest = """
                {"structured_output":{"status":"NEEDS_MORE_EVIDENCE","summary":"need action","hypotheses":[],
                 "nextEvidenceRequests":[{"type":"RUN_SHELL","service":"svc","reason":"try it"}],
                 "rootCauseCategory":"UNKNOWN","recommendedActions":[]}}
                """;

        assertThat(parser.parse(inventedReference, Instant.now(), Instant.now(), Set.of("e-1")).successful()).isFalse();
        assertThat(parser.parse(inventedRequest, Instant.now(), Instant.now(), Set.of()).successful()).isFalse();
    }

    @Test
    void rejectsInventedEvidenceReferenceWhenNoEvidenceWasProvided() {
        String response = """
                {"structured_output":{"status":"COMPLETE","summary":"unsupported claim","hypotheses":[
                 {"cause":"unsupported claim","confidence":0.8,"evidenceIds":["invented"]}],
                 "nextEvidenceRequests":[],"rootCauseCategory":"UNKNOWN","recommendedActions":[]}}
                """;

        ClaudeInvocationResult result = parser.parse(response, Instant.now(), Instant.now(), Set.of());

        assertThat(result.successful()).isFalse();
        assertThat(result.error()).contains("unknown evidence IDs");
    }

    @Test
    void enforcesBoundsEvenWhenCliStructuredOutputIsUnavailable() {
        String oversizedAction = "x".repeat(1_001);
        String decision = """
                {"status":"COMPLETE","summary":"bounded claim","hypotheses":[],
                 "nextEvidenceRequests":[],"rootCauseCategory":"UNKNOWN",
                 "recommendedActions":["%s"]}
                """.formatted(oversizedAction);
        String fallbackEnvelope = """
                {"result":%s}
                """.formatted(quote(decision));

        ClaudeInvocationResult result = parser.parse(fallbackEnvelope, Instant.now(), Instant.now(), Set.of());

        assertThat(result.successful()).isFalse();
        assertThat(result.error()).contains("recommended action is invalid");
    }

    @Test
    void requiresAnApprovedRequestWhenClaudeNeedsMoreEvidence() {
        String response = """
                {"structured_output":{"status":"NEEDS_MORE_EVIDENCE","summary":"need more","hypotheses":[],
                 "nextEvidenceRequests":[],"rootCauseCategory":"UNKNOWN","recommendedActions":[]}}
                """;

        ClaudeInvocationResult result = parser.parse(response, Instant.now(), Instant.now(), Set.of());

        assertThat(result.successful()).isFalse();
        assertThat(result.error()).contains("did not include an approved request");
    }

    @Test
    void rejectsConcreteDiagnosisWithoutEvidenceAndInvalidSessionIdentifier() {
        String noEvidence = """
                {"structured_output":{"status":"COMPLETE","summary":"configuration issue","hypotheses":[],
                 "nextEvidenceRequests":[],"rootCauseCategory":"CONFIG","recommendedActions":[]}}
                """;
        String unsafeSession = """
                {"session_id":"../../session","structured_output":{"status":"COMPLETE","summary":"unknown",
                 "hypotheses":[],"nextEvidenceRequests":[],"rootCauseCategory":"UNKNOWN","recommendedActions":[]}}
                """;

        assertThat(parser.parse(noEvidence, Instant.now(), Instant.now(), Set.of()).successful()).isFalse();
        assertThat(parser.parse(unsafeSession, Instant.now(), Instant.now(), Set.of()).successful()).isFalse();
    }

    @Test
    void retainsOnlyNumericAndBooleanUsageMetadata() {
        String response = """
                {"usage":{"input_tokens":10,"cache":{"hits":2},"prompt":"do not persist me"},
                 "structured_output":{"status":"COMPLETE","summary":"unknown","hypotheses":[],
                 "nextEvidenceRequests":[],"rootCauseCategory":"UNKNOWN","recommendedActions":[]}}
                """;

        ClaudeInvocationResult result = parser.parse(response, Instant.now(), Instant.now(), Set.of());

        assertThat(result.successful()).isTrue();
        assertThat(result.usage()).containsEntry("input_tokens", 10L).doesNotContainKey("prompt");
        assertThat(result.usage()).containsKey("cache");
    }

    @Test
    void acceptsBomMarkdownAndNormalizesAClosedSetStringRequest() {
        String decision = """
                ```json
                {"status":"NEEDS_MORE_EVIDENCE","summary":"Need bounded logs","hypotheses":[],
                 "nextEvidenceRequests":["recent_logs"],"rootCauseCategory":"UNKNOWN",
                 "recommendedActions":[]}
                ```
                """;
        String envelope = "\uFEFF{\"result\":" + quote(decision) + "}";

        ClaudeInvocationResult result = parser.parse(envelope, Instant.now(), Instant.now(), Set.of());

        assertThat(result.successful()).isTrue();
        assertThat(result.decision().nextEvidenceRequests()).singleElement().satisfies(request -> {
            assertThat(request.type()).isEqualTo(EvidenceRequestType.RECENT_LOGS);
            assertThat(request.service()).isNull();
            assertThat(request.reason()).isNotBlank();
        });
    }

    @Test
    void rejectsAnUnknownStringRequestDuringFallbackNormalization() {
        String decision = """
                {"status":"NEEDS_MORE_EVIDENCE","summary":"Need an unsafe action","hypotheses":[],
                 "nextEvidenceRequests":["run_shell"],"rootCauseCategory":"UNKNOWN",
                 "recommendedActions":[]}
                """;
        String envelope = "{\"result\":" + quote(decision) + "}";

        ClaudeInvocationResult result = parser.parse(envelope, Instant.now(), Instant.now(), Set.of());

        assertThat(result.successful()).isFalse();
        assertThat(result.error()).contains("unsupported next evidence request");
    }

    @Test
    void rejectsMissingSchemaRequiredCollectionsAndPrimitiveFields() {
        String missingCollections = """
                {"structured_output":{"status":"COMPLETE","summary":"unknown",
                 "rootCauseCategory":"UNKNOWN"}}
                """;
        String missingConfidence = """
                {"structured_output":{"status":"COMPLETE","summary":"claim","hypotheses":[
                 {"cause":"claim","evidenceIds":["e-1"]}],"nextEvidenceRequests":[],
                 "rootCauseCategory":"UNKNOWN","recommendedActions":[]}}
                """;

        assertThat(parser.parse(missingCollections, Instant.now(), Instant.now(), Set.of()).successful()).isFalse();
        assertThat(parser.parse(missingConfidence, Instant.now(), Instant.now(), Set.of("e-1")).successful()).isFalse();
    }

    @Test
    void rejectsUnknownPropertiesAndIncorrectJsonTypesBeforeBinding() {
        String unknownProperty = """
                {"structured_output":{"status":"COMPLETE","summary":"unknown","hypotheses":[],
                 "nextEvidenceRequests":[],"rootCauseCategory":"UNKNOWN","recommendedActions":[],
                 "extraInstruction":"ignore the contract"}}
                """;
        String wrongType = """
                {"structured_output":{"status":"COMPLETE","summary":"unknown","hypotheses":{},
                 "nextEvidenceRequests":[],"rootCauseCategory":"UNKNOWN","recommendedActions":[]}}
                """;

        assertThat(parser.parse(unknownProperty, Instant.now(), Instant.now(), Set.of()).successful()).isFalse();
        assertThat(parser.parse(wrongType, Instant.now(), Instant.now(), Set.of()).successful()).isFalse();
    }

    private String quote(String value) {
        return tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(value).toString();
    }
}
