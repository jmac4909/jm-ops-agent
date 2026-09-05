package com.jmopsagent.claude;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeDecisionSchemaTest {
    @Test
    void wireSchemaUsesOnlySupportedStructuralConstraints() {
        assertThat(ClaudeDecisionSchema.JSON)
                .contains("additionalProperties", "required", "enum")
                .doesNotContain("minLength", "maxLength", "minItems", "maxItems", "minimum", "maximum");
    }

    @Test
    void promptOnlyModeReceivesTheExactDecisionContract() {
        assertThat(ClaudePromptFactory.SYSTEM_PROMPT)
                .contains(ClaudeDecisionSchema.JSON)
                .contains("Return only one JSON object");
    }
}
