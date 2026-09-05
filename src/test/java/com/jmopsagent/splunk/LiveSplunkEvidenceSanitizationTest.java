package com.jmopsagent.splunk;

import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.domain.EvidenceItem;
import com.jmopsagent.domain.EvidenceReliability;
import com.jmopsagent.domain.EvidenceSource;
import com.jmopsagent.domain.EvidenceType;
import com.jmopsagent.sanitization.ConfigurableEvidenceSanitizer;
import com.jmopsagent.sanitization.EvidenceDraft;
import com.jmopsagent.sanitization.SanitizationProperties;
import com.jmopsagent.sanitization.SanitizedEvidenceItemFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LiveSplunkEvidenceSanitizationTest {

    @Test
    void arbitraryStructuredBusinessDataCannotSurviveInLogContentSummaryOrSignatureMetadata() {
        String rawBody = "{\"arbitraryBusinessField\":\"confidential-diagnosis\","
                + "\"unrecognizedCustomerField\":\"customer-value\",\"padding\":\""
                + "x".repeat(5_000) + "\"}";
        String fingerprint = LiveSplunkConnector.signature(rawBody);
        SanitizedEvidenceItemFactory factory = new SanitizedEvidenceItemFactory(
                new ConfigurableEvidenceSanitizer(new SanitizationProperties()));
        EvidenceDraft draft = new EvidenceDraft(EvidenceSource.SPLUNK, EvidenceType.ERROR_LOG, null,
                "catalog-service", DeploymentEnvironment.TEST,
                rawBody.substring(0, 240), rawBody.substring(0, 4_000), null,
                "{\"frequency\":\"1\",\"signature\":\"" + fingerprint + "\"}",
                EvidenceReliability.HIGH);

        EvidenceItem item = factory.create(draft);

        assertThat(fingerprint).startsWith("sha256:").hasSize(71)
                .doesNotContain("confidential-diagnosis", "customer-value", "arbitraryBusinessField");
        assertThat(item.getSummary()).isEqualTo("[REDACTED:BODY]");
        assertThat(item.getSanitizedContent()).isEqualTo("[REDACTED:BODY]");
        assertThat(item.getMetadataJson()).contains("sha256:")
                .doesNotContain("confidential-diagnosis", "customer-value", "arbitraryBusinessField");
        assertThat(item.isRedactionApplied()).isTrue();
        assertThat(item.getRedactionCategories()).contains("BODY");
    }

    @Test
    void equivalentDynamicMessagesRetainTheSameNonContentGroupingFingerprint() {
        String first = "Request 123 failed for 0f47ac10-b58c-4372-a567-0e02b2c3d479";
        String second = "Request 456 failed for 1f47ac10-b58c-4372-a567-0e02b2c3d480";

        assertThat(LiveSplunkConnector.signature(first)).isEqualTo(LiveSplunkConnector.signature(second));
    }
}
