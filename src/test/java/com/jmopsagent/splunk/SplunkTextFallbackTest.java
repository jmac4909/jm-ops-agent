package com.jmopsagent.splunk;

import com.jmopsagent.sanitization.ConfigurableEvidenceSanitizer;
import com.jmopsagent.sanitization.SanitizationProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class SplunkTextFallbackTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final SplunkFieldNormalizer normalizer = new SplunkFieldNormalizer(List.of());

    @Test
    void rejectsDuplicateProfileSelectionsAndToleratesAbsentEvents() {
        SplunkFieldProfile profile = new SplunkFieldProfile();
        profile.setName("plain");
        profile.setTrackingIdExtraction(SplunkTrackingIdExtraction.PREFIXED_TEXT);
        var fields = new SplunkFieldNormalizer(List.of(profile.validate(0)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fields.businessCallRawPredicate(List.of("plain", "plain")))
                .hasMessageContaining("duplicate");
        assertThat(fields.value(null, SplunkCanonicalField.MESSAGE)).isEmpty();
        assertThat(fields.value(null, SplunkCanonicalField.HTTP_STATUS)).isEmpty();
    }

    @Test
    void preservesTextForMissingBlankAndWrappedMessagesAndExtractsBusinessFields() {
        String message = "[ERROR] findDetails ErrorMessage: null X-TrackingId=DEMO-TRACE-123 statusCode=500 "
                + "[CatalogClient#getDetails] ---> GET http://catalog.example.invalid/detail HTTP/1.1";
        for (String field : List.of("msg", "_raw")) {
            var row = json.createObjectNode().put("message", "   ").put(field, message);
            assertThat(normalizer.value(row, SplunkCanonicalField.MESSAGE)).isEqualTo(message);
            assertThat(normalizer.value(row, SplunkCanonicalField.SEVERITY)).isEqualTo("ERROR");
            assertThat(normalizer.value(row, SplunkCanonicalField.HTTP_STATUS)).isEqualTo("500");
            assertThat(normalizer.value(row, SplunkCanonicalField.TRACKING_ID)).isEqualTo("DEMO-TRACE-123");
            assertThat(normalizer.value(row, SplunkCanonicalField.TARGET_URL)).isEqualTo("http://catalog.example.invalid/detail");
        }
        var wrapped = json.createObjectNode().put("_raw", json.writeValueAsString(java.util.Map.of("msg", message)));
        assertThat(normalizer.value(wrapped, SplunkCanonicalField.MESSAGE)).isEqualTo(message);
    }

    @Test
    void profileFieldsTakePrecedenceAndPlainTextFallbackIsProjectedBeforeRawIsDropped() {
        SplunkFieldProfile profile = new SplunkFieldProfile();
        profile.setName("plain");
        profile.setFields(java.util.Map.of("MESSAGE", List.of("msg.detail"), "TRACKING_ID", List.of("trace")));
        var fields = new SplunkFieldNormalizer(List.of(profile.validate(0)));
        var row = json.createObjectNode().put("msg.detail", "structured error").put("_raw", "raw error");
        assertThat(fields.value(row, SplunkCanonicalField.MESSAGE)).isEqualTo("structured error");
        String query = new SplunkQueryBuilder(fields).serviceErrors(List.of("test"), List.of("catalog-service"), List.of("plain"), 5);
        assertThat(query).contains("rex field=jmops_text", "message=\"*ERROR*\"");
        String prefilter = query.substring(0, query.indexOf('|'));
        for (String statusField : List.of("statusCode", "httpStatus", "http_status")) {
            assertThat(prefilter).contains("OR \"" + statusField + "\"");
            var rawStatusOnly = json.createObjectNode().put("_raw", json.writeValueAsString(java.util.Map.of(
                    "msg", statusField + "=500 No catalog details found")));
            assertThat(fields.value(rawStatusOnly, SplunkCanonicalField.HTTP_STATUS)).isEqualTo("500");
        }
        assertThat(query.substring(query.indexOf("| eval httpStatus="))).contains("httpStatus>=500");
        assertThat(query.indexOf("| eval message=")).isLessThan(query.indexOf("| fields - _raw"));
        assertThat(fields.businessCallRawPredicate(List.of("plain")))
                .isEqualTo("(\"X-TrackingId\" OR \"trace\") (\"statusCode\")");
    }

    @Test
    void preservesThreadLabelsAndOmitsMultilineFeignBodyContinuation() {
        var sanitizer = new ConfigurableEvidenceSanitizer(new SanitizationProperties());
        String error = "2026-09-10 [nio-8080-exec-1] [task-1] [123-worker] [123] [ERROR] findDetails ErrorMessage: null";
        String text = error + "\n[CatalogClient#getDetails] {\n"
                + "[CatalogClient#getDetails]   \"businessData\": {\n"
                + "[CatalogClient#getDetails]     \"field\": \"private-multiline-value\"\n"
                + "[CatalogClient#getDetails]   }\n[CatalogClient#getDetails] }\n"
                + "prefix {\nprefix \"businessData\":\"private-generic-value\"\nprefix }\n" + error;
        assertThat(sanitizer.sanitizeLogContent(text).sanitizedContent()).contains(error)
                .doesNotContain("private-multiline-value", "private-generic-value", "businessData");
    }

    @Test
    void prefixedAndTruncatedFeignBodiesAreOmittedWhileRequestMetadataAndErrorsSurvive() {
        var sanitizer = new ConfigurableEvidenceSanitizer(new SanitizationProperties());
        String text = "[CatalogClient#getDetails] ---> GET http://catalog.example.invalid/detail\n"
                + "[CatalogClient#getDetails] Authorization: Bearer synthetic-sensitive-value\n"
                + "[CatalogClient#getDetails] {\"businessData\":\"sensitive-body\"}\n"
                + "[CatalogClient#getDetails] {\"businessData\":\"truncated-sensitive\n"
                + "[CatalogClient#getDetails] [\"private-array-item\"]\n"
                + "[CatalogClient#getDetails] {\"operationOutcome\":\"No catalog details found\"}\n"
                + "[ERROR] findDetails ErrorMessage: null";
        assertThat(sanitizer.sanitizeLogContent(text).sanitizedContent()).contains("GET http://catalog.example.invalid/detail",
                        "findDetails ErrorMessage: null", "[REDACTED:BODY]", "No catalog details found")
                .doesNotContain("sensitive-body", "truncated-sensitive", "synthetic-sensitive-value", "private-array-item");
    }
}
