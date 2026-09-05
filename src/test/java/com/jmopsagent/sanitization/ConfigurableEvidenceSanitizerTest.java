package com.jmopsagent.sanitization;

import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.domain.EvidenceItem;
import com.jmopsagent.domain.EvidenceReliability;
import com.jmopsagent.domain.EvidenceSource;
import com.jmopsagent.domain.EvidenceType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurableEvidenceSanitizerTest {

    @Test
    void redactsCommonCredentialFormsAndRecordsTheAuditMarker() {
        SanitizationProperties properties = new SanitizationProperties();
        ConfigurableEvidenceSanitizer sanitizer = new ConfigurableEvidenceSanitizer(properties);

        SanitizationResult result = sanitizer.sanitize("Authorization: Bearer abcdefghijklmnop\n"
                + "password=hunter2 api_key=abcd1234 Cookie: session=secret-cookie");

        assertThat(result.sanitizedContent())
                .doesNotContain("abcdefghijklmnop", "hunter2", "abcd1234", "secret-cookie")
                .contains("[REDACTED:AUTHORIZATION]", "[REDACTED:CREDENTIAL]", "[REDACTED:COOKIE]");
        assertThat(result.redactionApplied()).isTrue();
        assertThat(result.redactionCount()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void redactsTheEntireAuthorizationValueForNonBearerSchemes() {
        ConfigurableEvidenceSanitizer sanitizer = new ConfigurableEvidenceSanitizer(new SanitizationProperties());

        SanitizationResult result = sanitizer.sanitize("Authorization: Splunk splunk-secret-value\n"
                + "Proxy-Authorization: Negotiate proxy-secret-value");

        assertThat(result.sanitizedContent())
                .doesNotContain("splunk-secret-value", "proxy-secret-value", "Negotiate")
                .contains("Authorization: [REDACTED:AUTHORIZATION]",
                        "Proxy-Authorization: [REDACTED:AUTHORIZATION]");
        assertThat(result.redactionCategories()).contains("AUTHORIZATION");
    }

    @Test
    void redactsCsrfHeadersAndCookieStyleCsrfFields() {
        ConfigurableEvidenceSanitizer sanitizer = new ConfigurableEvidenceSanitizer(new SanitizationProperties());

        SanitizationResult result = sanitizer.sanitize("X-Splunk-Form-Key: form-key-sentinel^value\n"
                + "csrf_token=csrf-sentinel^value");

        assertThat(result.sanitizedContent())
                .doesNotContain("form-key-sentinel", "csrf-sentinel")
                .contains("[REDACTED:CSRF]");
        assertThat(result.redactionCategories()).contains("CSRF");
    }

    @Test
    void factorySanitizesEveryUntrustedPersistedTextField() {
        SanitizationProperties properties = new SanitizationProperties();
        SanitizedEvidenceItemFactory factory = new SanitizedEvidenceItemFactory(
                new ConfigurableEvidenceSanitizer(properties));
        EvidenceDraft draft = new EvidenceDraft(EvidenceSource.SPLUNK, EvidenceType.ERROR_LOG, null,
                "catalog-service", DeploymentEnvironment.TEST,
                "password=summary-secret", "Bearer content-secret-token",
                "https://splunk.invalid/result?access_token=url-secret", "{\"apiKey\":\"meta-secret\"}",
                EvidenceReliability.HIGH);

        EvidenceItem item = factory.create(draft);

        assertThat(item.getSummary()).doesNotContain("summary-secret");
        assertThat(item.getSanitizedContent()).doesNotContain("content-secret-token");
        assertThat(item.getSourceUrl()).doesNotContain("url-secret");
        assertThat(item.getMetadataJson()).doesNotContain("meta-secret");
        assertThat(item.isRedactionApplied()).isTrue();
    }

    @Test
    void supportsBoundedContentAndCustomPatterns() {
        SanitizationProperties properties = new SanitizationProperties();
        properties.setMaxContentCharacters(1_000);
        properties.setAdditionalPatterns(Map.of("record-id", "REC-[0-9]{6}"));
        ConfigurableEvidenceSanitizer sanitizer = new ConfigurableEvidenceSanitizer(properties);

        SanitizationResult result = sanitizer.sanitize("REC-123456 " + "x".repeat(2_000));

        assertThat(result.sanitizedContent()).doesNotContain("REC-123456").hasSize(1_000);
        assertThat(result.truncated()).isTrue();
        assertThat(result.redactionCategories()).contains("CUSTOM_RECORD_ID");
    }

    @Test
    void stripsRequestAndResponseBodiesByDefault() {
        ConfigurableEvidenceSanitizer sanitizer = new ConfigurableEvidenceSanitizer(new SanitizationProperties());

        SanitizationResult result = sanitizer.sanitize("status=500 requestBody: patient-data-here\n"
                + "{\"responseBody\":\"customer-data-here\",\"error\":\"failed\"}");

        assertThat(result.sanitizedContent()).doesNotContain("patient-data-here", "customer-data-here")
                .contains("[REDACTED:BODY]");
        assertThat(result.redactionCategories()).contains("BODY");
    }

    @Test
    void redactsSensitiveJsonBodyAndHeaderFields() {
        ConfigurableEvidenceSanitizer sanitizer = new ConfigurableEvidenceSanitizer(new SanitizationProperties());

        SanitizationResult result = sanitizer.sanitize("""
                {"customerId":"CUS-123456","dateOfBirth":"1970-01-01",\
                "Authorization":"Basic dXNlcjpwYXNz","Cookie":"SESSION=secret-cookie"}
                """);

        assertThat(result.sanitizedContent())
                .doesNotContain("CUS-123456", "1970-01-01", "dXNlcjpwYXNz", "secret-cookie")
                .contains("[REDACTED:SENSITIVE_BODY_FIELD]", "[REDACTED:AUTHORIZATION]", "[REDACTED:COOKIE]");
        assertThat(result.redactionApplied()).isTrue();
    }

    @Test
    void logEvidenceDropsUnknownStructuredBodiesButKeepsErrorMetadata() {
        ConfigurableEvidenceSanitizer sanitizer = new ConfigurableEvidenceSanitizer(new SanitizationProperties());

        SanitizationResult body = sanitizer.sanitizeLogContent("{\"arbitraryBusinessField\":\"value\"}");
        SanitizationResult truncatedBody = sanitizer.sanitizeLogContent(
                "{\"arbitraryBusinessField\":\"sensitive-value\",\"unfinished\":\"");
        SanitizationResult structuredError = sanitizer.sanitizeLogContent(
                "{\"level\":\"ERROR\",\"message\":\"lookup failed\",\"customerId\":\"CUS-123456\"}");

        assertThat(body.sanitizedContent()).isEqualTo("[REDACTED:BODY]");
        assertThat(body.redactionCategories()).contains("BODY");
        assertThat(truncatedBody.sanitizedContent()).isEqualTo("[REDACTED:BODY]")
                .doesNotContain("sensitive-value");
        assertThat(structuredError.sanitizedContent()).contains("level=ERROR", "message=lookup failed", "[REDACTED:BODY]")
                .doesNotContain("CUS-123456");
        assertThat(structuredError.redactionApplied()).isTrue();
    }

    @Test
    void logEvidenceDropsJsonBodiesNestedInsideSafeMessageAndErrorFields() {
        ConfigurableEvidenceSanitizer sanitizer = new ConfigurableEvidenceSanitizer(new SanitizationProperties());

        SanitizationResult result = sanitizer.sanitizeLogContent("""
                {"level":"ERROR","logger":"CatalogClient",\
                "message":"{\\"arbitraryBusinessField\\":\\"sensitive-business-value\\"}",\
                "error":"[{\\"unknownCustomerField\\":\\"private-customer-value\\""}
                """);

        assertThat(result.sanitizedContent())
                .contains("level=ERROR", "logger=CatalogClient", "message=[REDACTED:BODY]",
                        "error=[REDACTED:BODY]")
                .doesNotContain("sensitive-business-value", "private-customer-value",
                        "arbitraryBusinessField", "unknownCustomerField");
        assertThat(result.redactionCategories()).contains("BODY");
        assertThat(result.redactionCount()).isGreaterThanOrEqualTo(2);
    }
}
