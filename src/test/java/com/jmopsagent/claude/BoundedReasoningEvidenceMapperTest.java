package com.jmopsagent.claude;

import com.jmopsagent.domain.*;
import com.jmopsagent.sanitization.SanitizationResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BoundedReasoningEvidenceMapperTest {
    @Test
    void freshlyCollectedOldIncidentEvidenceStillReachesReasoningAfterPriorEvidenceFillsThePrompt() {
        List<EvidenceItem> items = new ArrayList<>();
        Instant collected = Instant.parse("2026-01-01T00:00:00Z");
        for (int index = 0; index < 30; index++) items.add(item("Old collection " + index, "x".repeat(25_000), collected.plusSeconds(index)));
        var fresh = item("New historical source finding", "Missing projected field in old revision", collected.plusSeconds(30));
        items.add(fresh);
        var superseded = item("Previous scope", "Superseded source", collected.plusSeconds(31));
        var investigation = Investigation.forServiceTriage("demo-api", DeploymentEnvironment.TEST, "Earlier incident");
        investigation.addEvidence(superseded);
        investigation.resolveIncidentWindow(new IncidentWindow(Instant.parse("2024-05-01T00:00:00Z"),
                Instant.parse("2024-05-02T00:00:00Z")), "Corrected timing");
        items.add(superseded);
        var mapped = new BoundedReasoningEvidenceMapper().map(items);
        assertThat(mapped.getFirst().id()).isEqualTo(fresh.getId().toString());
        assertThat(mapped.toString()).contains("Missing projected field").doesNotContain("Superseded source");
        assertThat(mapped.stream().mapToInt(value -> value.summary().length() + value.content().length()).sum()).isLessThanOrEqualTo(500_000);
    }

    private EvidenceItem item(String summary, String content, Instant collectedAt) {
        var item = EvidenceItem.create(EvidenceSource.GITLAB, EvidenceType.SOURCE_CODE, Instant.parse("2024-05-01T00:00:00Z"),
                "demo-api", DeploymentEnvironment.TEST, summary, new SanitizationResult(content, false, 0, List.of(), false),
                null, null, EvidenceReliability.MEDIUM);
        org.springframework.test.util.ReflectionTestUtils.setField(item, "collectedAt", collectedAt);
        return item;
    }
}
