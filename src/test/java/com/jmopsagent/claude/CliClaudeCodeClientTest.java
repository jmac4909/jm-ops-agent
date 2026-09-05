package com.jmopsagent.claude;

import com.jmopsagent.connector.process.ProcessRequest;
import com.jmopsagent.connector.process.ProcessEnvironmentPolicy;
import com.jmopsagent.connector.process.ProcessResult;
import com.jmopsagent.connector.process.ProcessRunner;
import com.jmopsagent.sanitization.ConfigurableEvidenceSanitizer;
import com.jmopsagent.sanitization.SanitizationProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CliClaudeCodeClientTest {
    private static final Set<String> SAFE_FLAGS = Set.of(
            "--print", "--output-format", "--tools", "--disallowedTools", "--bare", "--safe-mode",
            "--system-prompt", "--disable-slash-commands", "--permission-mode", "--max-turns",
            "--permission-prompts", "--restricted", "--no-chrome", "--json-schema", "--resume", "--model");

    @Test
    void invokesClaudeWithNoToolsAndPassesEvidenceThroughStdin() {
        ProcessRunner runner = mock(ProcessRunner.class);
        ClaudeCliCapabilityInspector inspector = mock(ClaudeCliCapabilityInspector.class);
        when(inspector.inspect()).thenReturn(new ClaudeCliCapabilities(true, "2.1.260", SAFE_FLAGS, null));
        Instant started = Instant.now();
        when(runner.execute(any(ProcessRequest.class))).thenReturn(new ProcessResult(true, 0, """
                {"session_id":"session-123","structured_output":{"status":"COMPLETE","summary":"Supported",
                 "hypotheses":[{"cause":"Supported","confidence":0.8,"evidenceIds":["e-1"]}],
                 "nextEvidenceRequests":[],"rootCauseCategory":"RUNTIME","recommendedActions":["Observe"]}}
                """, "", false, false, null, started, started.plusMillis(12)));
        CliClaudeCodeClient client = client(runner, inspector);

        ClaudeInvocationResult result = client.analyze(request(null));

        assertThat(result.successful()).isTrue();
        ArgumentCaptor<ProcessRequest> capture = ArgumentCaptor.forClass(ProcessRequest.class);
        verify(runner).execute(capture.capture());
        ProcessRequest process = capture.getValue();
        assertThat(process.executable()).isEqualTo("claude");
        assertThat(process.environmentPolicy()).isEqualTo(ProcessEnvironmentPolicy.CLAUDE_VERTEX_ALLOWLIST);
        assertThat(valueAfter(process.arguments(), "--tools")).isEmpty();
        assertThat(valueAfter(process.arguments(), "--disallowedTools")).isEqualTo("mcp__*");
        assertThat(valueAfter(process.arguments(), "--permission-mode")).isEqualTo("dontAsk");
        assertThat(valueAfter(process.arguments(), "--permission-prompts")).isEqualTo("none");
        assertThat(valueAfter(process.arguments(), "--max-turns")).isEqualTo("1");
        assertThat(process.arguments()).contains("--bare", "-p", "--safe-mode", "--restricted", "--disable-slash-commands",
                "--no-chrome", "--json-schema", "--system-prompt")
                .doesNotContain("--dangerously-skip-permissions", "--allowedTools", "Bash", "Read", "Edit");
        assertThat(process.stdinContent()).contains("sanitizedEvidence", "e-1", "bounded evidence")
                .doesNotContain("--dangerously-skip-permissions");
        assertThat(process.arguments()).noneMatch(argument -> argument.contains("bounded evidence"));
    }

    @Test
    void failsClosedWhenARequiredSafetyFlagIsUnavailable() {
        ProcessRunner runner = mock(ProcessRunner.class);
        ClaudeCliCapabilityInspector inspector = mock(ClaudeCliCapabilityInspector.class);
        when(inspector.inspect()).thenReturn(new ClaudeCliCapabilities(true, "older",
                Set.of("--print", "--output-format", "--system-prompt"), null));

        ClaudeInvocationResult result = client(runner, inspector).analyze(request(null));

        assertThat(result.successful()).isFalse();
        assertThat(result.error()).contains("required safety flags");
        verify(runner, never()).execute(any());
    }

    @Test
    void rejectsAnUnsafeResumeIdentifierBeforeStartingAProcess() {
        ProcessRunner runner = mock(ProcessRunner.class);
        ClaudeCliCapabilityInspector inspector = mock(ClaudeCliCapabilityInspector.class);
        when(inspector.inspect()).thenReturn(new ClaudeCliCapabilities(true, "2.1.260", SAFE_FLAGS, null));

        ClaudeInvocationResult result = client(runner, inspector).analyze(request("../../another-session"));

        assertThat(result.successful()).isFalse();
        assertThat(result.error()).contains("cannot be resumed safely");
        verify(runner, never()).execute(any());
    }

    @Test
    void retriesPromptOnlyWhenTheLocalLauncherRejectsSchemaTransport() {
        ProcessRunner runner = mock(ProcessRunner.class);
        ClaudeCliCapabilityInspector inspector = mock(ClaudeCliCapabilityInspector.class);
        when(inspector.inspect()).thenReturn(new ClaudeCliCapabilities(true, "windows", SAFE_FLAGS, null));
        Instant started = Instant.now();
        ProcessResult rejected = new ProcessResult(true, 1, "",
                "--json-schema is not valid JSON: Expected closing brace", false, false, null,
                started, started.plusMillis(2));
        ProcessResult accepted = new ProcessResult(true, 0, """
                {"structured_output":{"status":"COMPLETE","summary":"Supported",
                 "hypotheses":[{"cause":"Supported","confidence":0.8,"evidenceIds":["e-1"]}],
                 "nextEvidenceRequests":[],"rootCauseCategory":"RUNTIME","recommendedActions":[]}}
                """, "", false, false, null, started.plusMillis(3), started.plusMillis(8));
        when(runner.execute(any(ProcessRequest.class))).thenReturn(rejected, accepted);

        ClaudeInvocationResult result = client(runner, inspector).analyze(request(null));

        assertThat(result.successful()).isTrue();
        ArgumentCaptor<ProcessRequest> capture = ArgumentCaptor.forClass(ProcessRequest.class);
        verify(runner, org.mockito.Mockito.times(2)).execute(capture.capture());
        assertThat(capture.getAllValues().get(0).arguments()).contains("--json-schema");
        assertThat(capture.getAllValues().get(1).arguments()).doesNotContain("--json-schema");
        assertThat(valueAfter(capture.getAllValues().get(1).arguments(), "--system-prompt"))
                .contains("nextEvidenceRequests");
    }

    @Test
    void passesAnExplicitValidatedModelOnlyWhenSupported() {
        ProcessRunner runner = mock(ProcessRunner.class);
        ClaudeCliCapabilityInspector inspector = mock(ClaudeCliCapabilityInspector.class);
        when(inspector.inspect()).thenReturn(new ClaudeCliCapabilities(true, "current", SAFE_FLAGS, null));
        Instant started = Instant.now();
        when(runner.execute(any(ProcessRequest.class))).thenReturn(new ProcessResult(true, 0, """
                {"structured_output":{"status":"COMPLETE","summary":"Unknown","hypotheses":[],
                 "nextEvidenceRequests":[],"rootCauseCategory":"UNKNOWN","recommendedActions":[]}}
                """, "", false, false, null, started, started.plusMillis(5)));
        ClaudeProperties properties = new ClaudeProperties();
        properties.setModel("example-model@20260101");

        ClaudeInvocationResult result = client(runner, inspector, properties).analyze(request(null));

        assertThat(result.successful()).isTrue();
        ArgumentCaptor<ProcessRequest> capture = ArgumentCaptor.forClass(ProcessRequest.class);
        verify(runner).execute(capture.capture());
        assertThat(valueAfter(capture.getValue().arguments(), "--model")).isEqualTo("example-model@20260101");
    }

    private CliClaudeCodeClient client(ProcessRunner runner, ClaudeCliCapabilityInspector inspector) {
        ClaudeProperties properties = new ClaudeProperties();
        properties.setTimeout(Duration.ofSeconds(30));
        return client(runner, inspector, properties);
    }

    private CliClaudeCodeClient client(ProcessRunner runner, ClaudeCliCapabilityInspector inspector,
                                       ClaudeProperties properties) {
        var mapper = JsonMapper.builder().build();
        return new CliClaudeCodeClient(runner, properties, inspector, new ClaudePromptFactory(mapper),
                new ClaudeResponseParser(mapper),
                new ConfigurableEvidenceSanitizer(new SanitizationProperties()));
    }

    private ClaudeReasoningRequest request(String sessionId) {
        return new ClaudeReasoningRequest(UUID.randomUUID(), "SERVICE_TRIAGE", "sample-service", "TEST",
                null, "A bounded problem", 1,
                List.of(new ReasoningEvidence("e-1", "SPLUNK", "ERROR_LOG", Instant.now().toString(),
                        "Failure", "bounded evidence", "HIGH")), List.of(), sessionId, false);
    }

    private String valueAfter(List<String> arguments, String flag) {
        int index = arguments.indexOf(flag);
        assertThat(index).isGreaterThanOrEqualTo(0).isLessThan(arguments.size() - 1);
        return arguments.get(index + 1);
    }
}
