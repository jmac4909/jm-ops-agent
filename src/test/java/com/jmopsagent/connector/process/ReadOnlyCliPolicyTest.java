package com.jmopsagent.connector.process;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReadOnlyCliPolicyTest {
    @Test
    void permitsOnlyKnownReadOperations() {
        assertThatNoException().isThrownBy(() -> ReadOnlyCliPolicy.validateKubectl(
                List.of("--context", "test", "--namespace", "team-test", "get", "pods", "-o", "json")));
        assertThatNoException().isThrownBy(() -> ReadOnlyCliPolicy.validateKubectl(
                List.of("--context", "test", "--namespace", "team-test", "rollout", "status", "deployment/api")));
        assertThatNoException().isThrownBy(() -> ReadOnlyCliPolicy.validateCf(
                List.of("logs", "api", "--recent")));
        assertThatNoException().isThrownBy(() -> ReadOnlyCliPolicy.validateCf(List.of("target")));
    }

    @Test
    void rejectsMutatingOrUnknownOperations() {
        assertThatIllegalArgumentException().isThrownBy(() -> ReadOnlyCliPolicy.validateKubectl(
                List.of("--context", "test", "--namespace", "team-test", "delete", "pod", "api")));
        assertThatIllegalArgumentException().isThrownBy(() -> ReadOnlyCliPolicy.validateKubectl(
                List.of("--context", "test", "--namespace", "team-test", "rollout", "restart", "deployment/api")));
        assertThatIllegalArgumentException().isThrownBy(() -> ReadOnlyCliPolicy.validateCf(
                List.of("restart", "api")));
        assertThatIllegalArgumentException().isThrownBy(() -> ReadOnlyCliPolicy.validateCf(
                List.of("target", "-o", "sample-org", "-s", "sample-space")));
        assertThatIllegalArgumentException().isThrownBy(() -> ReadOnlyCliPolicy.validateCf(
                List.of("-a", "api.test.example.invalid", "app", "api")));
    }
}
