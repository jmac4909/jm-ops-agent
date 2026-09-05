package com.jmopsagent.splunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import com.jmopsagent.registry.RegistryDiscoveryUpdate;
import com.jmopsagent.registry.RegistryProvenance;
import com.jmopsagent.registry.ServiceDefinition;
import com.jmopsagent.registry.ServiceRegistry;
import com.jmopsagent.registry.YamlServiceRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class LiveSplunkConnectorContractTest {
    private static final EvidenceQuery QUERY = new EvidenceQuery(
            Instant.parse("2026-01-15T10:30:00Z"), Instant.parse("2026-01-15T10:50:00Z"), 50, 50_000);

    @Test
    void queryUsesValidatedSpathCoalesceAliasesAndCanonicalProjectionWithoutRawPayload() {
        SplunkFieldNormalizer normalizer = normalizer();
        SplunkQueryBuilder builder = new SplunkQueryBuilder(normalizer);

        String spl = builder.serviceErrors(List.of("test_index"),
                List.of("catalog-service", "catalog-green-test"), List.of("platform-json"), 25);

        assertThat(spl).contains("spath path=\"msg.traceId\"")
                .contains("spath path=\"msg.statusCode\"")
                .contains("if(sourcetype=\"cf:logmessage\"")
                .contains("coalesce(")
                .startsWith("search (index=\"test_index\") sourcetype=\"cf:logmessage\"")
                .contains("(cf_app_name=\"catalog-service\" OR cf_app_name=\"catalog-green-test\")")
                .contains("(ERROR OR Exception OR status>=500)")
                .contains("service=\"catalog-service\" OR service=\"catalog-green-test\"")
                .contains("| fields - _raw")
                .endsWith("| table _time trackingId service downstreamService httpStatus outcome severity message operation"
                        + " targetUrl executionTime")
                .doesNotContain("table _raw");

        assertThat(spl.indexOf("cf_app_name=\"catalog-service\""))
                .isLessThan(spl.indexOf("spath path=\"msg.traceId\""));

        String activity = builder.recentActivity(List.of("test_index"), List.of("catalog-service"),
                List.of("platform-json"), 25);
        assertThat(activity).contains("isnotnull(operation) AND isnotnull(httpStatus)")
                .contains("(\"traceId\") (\"statusCode\")")
                .contains("tonumber(httpStatus)>=200 AND tonumber(httpStatus)<400")
                .contains("upper(severity)!=\"ERROR\"")
                .contains("upper(severity)!=\"FATAL\"")
                .contains("upper(severity)!=\"SEVERE\"")
                .contains("stats count AS trafficEventCount BY _time service operation httpStatus")
                .contains("head 26")
                .contains("table _time service httpStatus operation trafficEventCount message");
        assertThat(activity.indexOf("(\"traceId\") (\"statusCode\")"))
                .isLessThan(activity.indexOf("spath path=\"msg.traceId\""));

        String events = builder.serviceEvents(List.of("test_index"), List.of("catalog-green-test"),
                List.of("platform-json"), 25);
        assertThat(events).startsWith("search (index=\"test_index\") sourcetype=\"cf:logmessage\"")
                .contains("cf_app_name=\"catalog-green-test\"");
        assertThat(events.indexOf("cf_app_name=\"catalog-green-test\""))
                .isLessThan(events.indexOf("spath path=\"msg.traceId\""));

        assertThatThrownBy(() -> builder.serviceEvents(List.of("test_index"),
                List.of("catalog-*"), 25)).hasMessageContaining("Invalid Splunk service identity");
    }

    @Test
    void businessCallsBoundBeforeExtractionAndHttpFallbackKeepsRouterCorrelationSeparate() {
        SplunkQueryBuilder builder = new SplunkQueryBuilder(normalizer());

        String structured = builder.recentBusinessCalls(List.of("test_index"),
                List.of("catalog-green-test"), List.of("platform-json"), 20);
        assertThat(structured)
                .startsWith("search (index=\"test_index\") sourcetype=\"cf:logmessage\"")
                .contains("cf_app_name=\"catalog-green-test\"")
                .contains("(\"traceId\") (\"statusCode\")")
                .contains("| head 21")
                .contains("| dedup trackingId")
                .contains("jmopsSourceFormat=\"application-log\"");
        assertThat(structured.indexOf("| head 21")).isLessThan(structured.indexOf("| spath"));

        String fallback = builder.recentHttpCalls(List.of("test_index"),
                List.of("catalog-green-test"), 20);
        assertThat(fallback).contains("sourcetype=\"cf:httpstartstop\"")
                .contains("cf_app_name=\"catalog-green-test\"")
                .contains("NOT \"actuator\" NOT \"eureka\"")
                .contains("trackingId=null(),jmopsSourceFormat=\"http-access\"")
                .contains("httpMethod requestUri")
                .doesNotContain("trackingId='request_id'");
    }

    @Test
    void selectedPlainTextProfileUsesFixedRexOnlyWhenSelected() {
        SplunkFieldProfile plain = new SplunkFieldProfile();
        plain.setName("prefixed-text");
        plain.setSourcetype("cf:logmessage");
        plain.setTrackingIdExtraction(SplunkTrackingIdExtraction.PREFIXED_TEXT);
        plain.setFields(Map.of("message", List.of("message")));
        SplunkFieldNormalizer normalizer = new SplunkFieldNormalizer(List.of(plain.validate(0)));

        assertThat(normalizer.pipeline()).doesNotContain("rex field=_raw");
        assertThat(normalizer.pipeline(List.of("prefixed-text")))
                .contains("rex field=_raw \"(?i)X-TrackingId[_=]")
                .contains("jmops_rex_0_tracking_id");
        assertThat(normalizer.businessCallRawPredicate(List.of("prefixed-text")))
                .isEqualTo("(\"X-TrackingId\")");
        assertThatThrownBy(() -> normalizer.pipeline(List.of("unknown-profile")))
                .hasMessageContaining("Unknown or duplicate");
    }

    @Test
    void gatewayTrackingUsesOnlyTopLevelAliasesAndApplicationTrackingUsesExactAppNames() {
        SplunkQueryBuilder builder = new SplunkQueryBuilder(normalizer());

        String gateway = builder.gatewayTracking(List.of("gateway_index"), "DEMO-TRACE-001", 25);
        assertThat(gateway).contains("index=\"gateway_index\"", "'X-TrackingId'", "'http.status_code'",
                        "'apigee_proxy.name'", "'targetURL'", "'totalLatency(ms)'",
                        "targetUrl executionTime")
                .doesNotContain("spath", "cf_app_name");

        String application = builder.trackingApplicationLogs(List.of("application_index"),
                "DEMO-TRACE-001", List.of("catalog-green-test"), List.of("platform-json"), 25);
        assertThat(application)
                .startsWith("search (index=\"application_index\") sourcetype=\"cf:logmessage\"")
                .contains("cf_app_name=\"catalog-green-test\"")
                .doesNotContain("*catalog");
        assertThat(application.indexOf("cf_app_name=\"catalog-green-test\""))
                .isLessThan(application.indexOf("spath path=\"msg.traceId\""));

        SplunkFieldProfile plain = new SplunkFieldProfile();
        plain.setName("prefixed-text");
        plain.setSourcetype("cf:logmessage");
        plain.setTrackingIdExtraction(SplunkTrackingIdExtraction.PREFIXED_TEXT);
        plain.setFields(Map.of("message", List.of("message")));
        String textTracking = new SplunkQueryBuilder(new SplunkFieldNormalizer(List.of(plain.validate(0))))
                .trackingApplicationLogs(List.of("application_index"), "DEMO-TRACE-001",
                        List.of("legacy-app-test"), List.of("prefixed-text"), 25);
        assertThat(textTracking).contains("cf_app_name=\"legacy-app-test\"")
                .contains("rex field=_raw \"(?i)X-TrackingId[_=]");
        assertThat(textTracking.indexOf("cf_app_name=\"legacy-app-test\""))
                .isLessThan(textTracking.indexOf("rex field=_raw"));
    }

    @Test
    void parsesNestedConfiguredFieldsIntoCanonicalTraceWithoutPersistingRawPayload() {
        LiveSplunkConnector connector = connector(status(HttpStatus.OK, ""), properties(), registry("""
                services:
                  - service: catalog-service
                    splunk:
                      appNames:
                        TEST:
                          - catalog-green-test
                """));
        String json = "{\"result\":{\"_time\":\"2026-01-15T10:40:00Z\","
                + "\"sourcetype\":\"cf:logmessage\",\"msg\":{\"traceId\":\"DEMO-TRACE-001\","
                + "\"appName\":\"catalog-green-test\",\"statusCode\":\"500\","
                + "\"detail\":\"Synthetic downstream failure\",\"operation\":\"GET /catalog/items\"}}}";

        SplunkConnectorResult result = connector.parse(json, Environment.TEST, QUERY,
                LiveSplunkConnector.SearchKind.TRACKING);

        assertThat(result.outcome()).isEqualTo(SplunkSearchOutcome.SUCCESS);
        assertThat(result.result().traceEvents()).singleElement().satisfies(event -> {
            assertThat(event.trackingId()).isEqualTo("DEMO-TRACE-001");
            assertThat(event.service()).isEqualTo("catalog-service");
            assertThat(event.httpStatus()).isEqualTo(500);
            assertThat(event.outcome()).isEqualTo("FAILURE");
            assertThat(event.summary()).isEqualTo("Synthetic downstream failure");
        });
        assertThat(result.result().evidence()).singleElement().satisfies(item -> {
            assertThat(item.content()).isEqualTo("Synthetic downstream failure");
            assertThat(item.content()).doesNotContain("traceId", "DEMO-TRACE-001");
        });
    }

    @Test
    void distinguishesNoDataParseFailureAndPartialParse() {
        LiveSplunkConnector connector = connector(status(HttpStatus.OK, ""), properties(), emptyRegistry());

        assertThat(connector.parse("", Environment.TEST, QUERY, LiveSplunkConnector.SearchKind.EVENTS).outcome())
                .isEqualTo(SplunkSearchOutcome.NO_DATA);
        assertThat(connector.parse("not-json", Environment.TEST, QUERY, LiveSplunkConnector.SearchKind.EVENTS).outcome())
                .isEqualTo(SplunkSearchOutcome.PARSE_FAILURE);
        assertThat(connector.parse("not-json\n{\"result\":{\"message\":\"Synthetic event\"}}",
                Environment.TEST, QUERY, LiveSplunkConnector.SearchKind.EVENTS).outcome())
                .isEqualTo(SplunkSearchOutcome.PARTIAL_PARSE);
    }

    @Test
    void distinguishesAuthenticationRedirectAndRemoteFailuresWithoutFollowingRedirect() {
        assertOutcome(HttpStatus.UNAUTHORIZED, SplunkSearchOutcome.UNAUTHORIZED);
        assertOutcome(HttpStatus.FORBIDDEN, SplunkSearchOutcome.FORBIDDEN);
        assertOutcome(HttpStatus.SEE_OTHER, SplunkSearchOutcome.REDIRECT_REJECTED);
        assertOutcome(HttpStatus.INTERNAL_SERVER_ERROR, SplunkSearchOutcome.REMOTE_FAILURE);
    }

    @Test
    void distinguishesUnconfiguredConnectorFromSuccessfulSearchWithNoData() {
        SplunkProperties unconfigured = new SplunkProperties();
        unconfigured.setTestIndexes("test_index");
        LiveSplunkConnector unavailable = connector(status(HttpStatus.OK, ""), unconfigured, emptyRegistry());
        LiveSplunkConnector noData = connector(status(HttpStatus.OK, ""), properties(), emptyRegistry());

        assertThat(unavailable.searchServiceEventsDetailed("catalog-service", Environment.TEST, QUERY).outcome())
                .isEqualTo(SplunkSearchOutcome.UNCONFIGURED);
        assertThat(noData.searchServiceEventsDetailed("catalog-service", Environment.TEST, QUERY).outcome())
                .isEqualTo(SplunkSearchOutcome.NO_DATA);
    }

    @Test
    void partialSessionConfigurationBuildsAnInertConnectorWithoutSendingCredentials() {
        SplunkProperties partial = new SplunkProperties();
        partial.setBaseUrl("https://logs.example.invalid");
        partial.setAuthMode(SplunkAuthMode.SESSION_CSRF);
        partial.setSessionCookie("splunkd_1=cookie-secret; session_id_1=session-secret");
        partial.setFormKey("form-key-secret");
        partial.setTestIndexes("test_index");
        AtomicInteger calls = new AtomicInteger();
        LiveSplunkConnector connector = connector(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }, partial, emptyRegistry());

        SplunkConnectorResult result = connector.searchServiceEventsDetailed(
                "catalog-service", Environment.TEST, QUERY);

        assertThat(result.outcome()).isEqualTo(SplunkSearchOutcome.UNCONFIGURED);
        assertThat(result.toString())
                .doesNotContain("logs.example.invalid", "cookie-secret", "session-secret", "form-key-secret");
        assertThat(calls).hasValue(0);
    }

    @Test
    void invalidRawRequestTimeoutBuildsAnInertConnectorWithoutSendingCredentials() {
        SplunkProperties invalid = properties();
        invalid.setRequestTimeout("not-a-duration-with-sensitive-detail");
        AtomicInteger calls = new AtomicInteger();
        LiveSplunkConnector connector = connector(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }, invalid, emptyRegistry());

        SplunkConnectorResult result = connector.searchServiceEventsDetailed(
                "catalog-service", Environment.TEST, QUERY);

        assertThat(result.outcome()).isEqualTo(SplunkSearchOutcome.UNCONFIGURED);
        assertThat(result.toString()).doesNotContain("not-a-duration-with-sensitive-detail");
        assertThat(calls).hasValue(0);
    }

    @Test
    void classifiesTimeoutAndTlsTransportFailures() {
        LiveSplunkConnector timedOut = connector(request -> Mono.error(
                new RuntimeException(new java.util.concurrent.TimeoutException("synthetic timeout"))),
                properties(), emptyRegistry());
        LiveSplunkConnector tlsFailed = connector(request -> Mono.error(
                new RuntimeException(new javax.net.ssl.SSLException("synthetic TLS failure"))),
                properties(), emptyRegistry());

        assertThat(timedOut.searchServiceEventsDetailed("catalog-service", Environment.TEST, QUERY).outcome())
                .isEqualTo(SplunkSearchOutcome.TIMEOUT);
        assertThat(tlsFailed.searchServiceEventsDetailed("catalog-service", Environment.TEST, QUERY).outcome())
                .isEqualTo(SplunkSearchOutcome.TLS_FAILURE);
    }

    @Test
    void rejectsRuntimeAliasCollisionsInsteadOfMixingServices() {
        ServiceRegistry registry = registry("""
                services:
                  - service: catalog-service
                    splunk:
                      appNames:
                        TEST: [shared-test-app]
                  - service: billing-service
                    splunk:
                      appNames:
                        TEST: [shared-test-app]
                """);
        LiveSplunkConnector connector = connector(status(HttpStatus.OK, ""), properties(), registry);

        assertThatThrownBy(() -> connector.parse(
                "{\"result\":{\"message\":\"Synthetic event\"}}", Environment.TEST, QUERY,
                LiveSplunkConnector.SearchKind.EVENTS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assigned to multiple services");
    }

    @Test
    void sendsCredentialsOnlyAsHeadersAndMakesOneRequestForRedirect() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient.Builder webClient = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.SEE_OTHER)
                    .header("Location", "https://redirect.example.invalid/elsewhere").build());
        });
        LiveSplunkConnector connector = new LiveSplunkConnector(webClient, new ObjectMapper(), emptyRegistry(), properties());

        SplunkConnectorResult result = connector.searchRecentActivityDetailed("catalog-service", Environment.TEST, QUERY);

        assertThat(result.outcome()).isEqualTo(SplunkSearchOutcome.REDIRECT_REJECTED);
        assertThat(calls).hasValue(1);
        assertThat(captured.get().url().toString()).doesNotContain("synthetic-token");
        assertThat(captured.get().headers().getFirst("Authorization")).isEqualTo("Bearer synthetic-token");
    }

    @Test
    void recentActivityCreatesNonErrorTrafficEvidence() {
        LiveSplunkConnector connector = connector(status(HttpStatus.OK,
                "{\"result\":{\"_time\":\"2026-01-15T10:40:00Z\",\"service\":\"catalog-service\","
                        + "\"httpStatus\":\"200\",\"operation\":\"GET /catalog/items\","
                        + "\"trafficEventCount\":\"12\",\"message\":\"Successful HTTP activity summary\"}}"),
                properties(), emptyRegistry());

        SplunkConnectorResult result = connector.searchRecentActivityDetailed("catalog-service", Environment.TEST, QUERY);

        assertThat(result.outcome()).isEqualTo(SplunkSearchOutcome.SUCCESS);
        assertThat(result.result().evidence()).singleElement().satisfies(item -> {
            assertThat(item.type().name()).isEqualTo("RECENT_ACTIVITY");
            assertThat(item.summary()).contains("12 matching log events");
            assertThat(item.metadata()).containsEntry("httpStatusClass", "2xx")
                    .containsEntry("trafficEventCount", "12")
                    .containsEntry("bucketDuration", "5m")
                    .containsEntry("countSemantics", "matching-log-events");
        });
    }

    @Test
    void businessCallEvidenceIsMetadataOnlyAndTruthfullyMarkedScanCapped() {
        LiveSplunkConnector connector = connector(status(HttpStatus.OK, ""), properties(), emptyRegistry());

        SplunkConnectorResult result = connector.parse(
                "{\"result\":{\"_time\":\"2026-01-15T10:40:00Z\","
                        + "\"trackingId\":\"DEMO-CALL-009\",\"service\":\"catalog-service\","
                        + "\"httpStatus\":\"200\",\"operation\":\"GET /catalog/items\","
                        + "\"executionTime\":\"42ms\",\"jmopsSourceFormat\":\"application-log\"}}",
                Environment.TEST, QUERY, LiveSplunkConnector.SearchKind.BUSINESS_CALLS);

        assertThat(result.outcome()).isEqualTo(SplunkSearchOutcome.SUCCESS);
        assertThat(result.result().truncated()).isTrue();
        assertThat(result.result().evidence()).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo(com.jmopsagent.connector.EvidenceType.RECENT_BUSINESS_CALLS);
            assertThat(item.content()).contains("trackingId=DEMO-CALL-009", "status=200", "bodyIncluded=false");
            assertThat(item.metadata()).containsEntry("scanCapped", "true")
                    .containsEntry("bodyIncluded", "false")
                    .containsEntry("executionTime", "42ms");
        });
    }

    @Test
    void httpAccessFallbackDoesNotMislabelRouterRequestIdAsTrackingId() {
        LiveSplunkConnector connector = connector(status(HttpStatus.OK, ""), properties(), emptyRegistry());

        SplunkConnectorResult result = connector.parse(
                "{\"result\":{\"_time\":\"2026-01-15T10:40:00Z\",\"service\":\"catalog-service\","
                        + "\"httpStatus\":\"204\",\"httpMethod\":\"POST\",\"requestUri\":\"/catalog/items\","
                        + "\"routerRequestId\":\"ROUTER-DEMO-001\",\"jmopsSourceFormat\":\"http-access\"}}",
                Environment.TEST, QUERY, LiveSplunkConnector.SearchKind.BUSINESS_CALLS);

        assertThat(result.result().evidence()).singleElement().satisfies(item -> {
            assertThat(item.summary()).contains("POST /catalog/items", "204");
            assertThat(item.metadata()).containsEntry("routerRequestId", "ROUTER-DEMO-001")
                    .containsEntry("httpMethod", "POST")
                    .containsEntry("requestUri", "/catalog/items")
                    .doesNotContainKey("trackingId");
            assertThat(item.content()).contains("trackingId=unavailable");
        });
    }

    @Test
    void sessionCsrfModeAutomaticallyUsesTheReadOnlyWebProxyPath() {
        SplunkProperties session = new SplunkProperties();
        session.setBaseUrl("https://logs.example.invalid");
        session.setAuthMode(SplunkAuthMode.SESSION_CSRF);
        session.setSessionCookie(SplunkPropertiesTest.completeCookie());
        session.setFormKey("synthetic-form-key");
        session.setTestIndexes("test_index");
        session.setFieldProfiles(properties().getFieldProfiles());
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        LiveSplunkConnector connector = connector(request -> {
            captured.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).body("").build());
        }, session, emptyRegistry());

        connector.searchServiceEventsDetailed("catalog-service", Environment.TEST, QUERY);

        assertThat(captured.get().url().getPath())
                .isEqualTo("/en-US/splunkd/__raw/services/search/jobs/export");
        assertThat(LiveSplunkConnector.normalizeBaseUrl(
                "https://logs.example.invalid/en-US/splunkd/__raw", SplunkAuthMode.SESSION_CSRF))
                .isEqualTo("https://logs.example.invalid/en-US/splunkd/__raw");
        assertThat(LiveSplunkConnector.normalizeBaseUrl(
                "https://logs.example.invalid/read-only-proxy", SplunkAuthMode.SESSION_CSRF))
                .isEqualTo("https://logs.example.invalid/read-only-proxy/en-US/splunkd/__raw");
    }

    @Test
    void kubernetesServiceNeverSendsAnEnterpriseLogSearch() {
        AtomicInteger calls = new AtomicInteger();
        LiveSplunkConnector connector = connector(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }, properties(), registry("""
                services:
                  - service: cluster-service
                    runtime:
                      platform:
                        TEST: EKS
                    splunk:
                      appNames:
                        TEST: [cluster-service-test]
                """));

        assertThat(connector.searchErrorsForServiceDetailed("cluster-service", Environment.TEST, QUERY).outcome())
                .isEqualTo(SplunkSearchOutcome.UNCONFIGURED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void gatewayIndexIsQueriedFirstAndGatewayNameIsCanonicalized() {
        SplunkProperties properties = properties();
        properties.setTestGatewayIndexes("gateway_index");
        AtomicInteger calls = new AtomicInteger();
        LiveSplunkConnector connector = connector(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).body(
                    "{\"result\":{\"_time\":\"2026-01-15T10:40:00Z\","
                            + "\"X-TrackingId\":\"DEMO-TRACE-001\","
                            + "\"apigee_proxy.name\":\"catalog-proxy-test\","
                            + "\"http.status_code\":\"500\","
                            + "\"targetURL\":\"https://backend.example.invalid/catalog/items\","
                            + "\"totalLatency(ms)\":\"87\","
                            + "\"message\":\"Synthetic gateway failure\"}}").build());
        }, properties, emptyRegistry());

        SplunkConnectorResult result = connector.searchByTrackingIdDetailed(
                "DEMO-TRACE-001", Environment.TEST, QUERY);

        assertThat(calls).hasValue(1);
        assertThat(result.result().traceEvents()).singleElement()
                .satisfies(event -> {
                    assertThat(event.service()).isEqualTo("catalog-service");
                    assertThat(event.operation()).isEqualTo("catalog-proxy-test");
                    assertThat(event.httpStatus()).isEqualTo(500);
                    assertThat(event.metadata())
                            .containsEntry("targetUrl", "https://backend.example.invalid/catalog/items")
                            .containsEntry("executionTime", "87");
                });
    }

    @Test
    void businessCallsUseHttpMetadataFallbackOnlyAfterSuccessfulNoData() {
        AtomicInteger calls = new AtomicInteger();
        LiveSplunkConnector connector = connector(request -> {
            if (calls.incrementAndGet() == 1) {
                return Mono.just(ClientResponse.create(HttpStatus.OK).body("").build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK).body(
                    "{\"result\":{\"_time\":\"2026-01-15T10:40:00Z\","
                            + "\"service\":\"catalog-green-test\",\"httpStatus\":\"200\","
                            + "\"httpMethod\":\"GET\",\"requestUri\":\"/catalog/items\","
                            + "\"routerRequestId\":\"ROUTER-DEMO-002\","
                            + "\"jmopsSourceFormat\":\"http-access\"}}").build());
        }, properties(), emptyRegistry());

        SplunkConnectorResult result = connector.searchRecentBusinessCallsDetailed(
                "catalog-service", Environment.TEST, QUERY);

        assertThat(calls).hasValue(2);
        assertThat(result.outcome()).isEqualTo(SplunkSearchOutcome.SUCCESS);
        assertThat(result.result().evidence()).singleElement().satisfies(item ->
                assertThat(item.metadata()).containsEntry("sourceFormat", "http-access")
                        .doesNotContainKey("trackingId"));
    }

    @Test
    void eachRemoteFallbackRequiresItsOwnInvestigationPermit() {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger permitAttempts = new AtomicInteger();
        LiveSplunkConnector connector = connector(request -> {
            requests.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).body("").build());
        }, properties(), emptyRegistry());

        SplunkConnectorResult result = connector.searchRecentBusinessCallsDetailed(
                "catalog-service", Environment.TEST, QUERY,
                () -> permitAttempts.incrementAndGet() <= 1);

        assertThat(result.outcome()).isEqualTo(SplunkSearchOutcome.LIMIT_REACHED);
        assertThat(requests).hasValue(1);
        assertThat(permitAttempts).hasValue(2);
    }

    @Test
    void missingEnvironmentSpecificAppNameFailsClosedWithoutAQuery() {
        AtomicInteger calls = new AtomicInteger();
        LiveSplunkConnector connector = connector(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }, properties(), registry("""
                services:
                  - service: catalog-service
                    runtime:
                      platform:
                        TEST: TAS
                """));

        assertThat(connector.searchServiceEventsDetailed("catalog-service", Environment.TEST, QUERY).outcome())
                .isEqualTo(SplunkSearchOutcome.UNCONFIGURED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void unconfirmedDiscoveredApplicationIdentityCannotRouteAQuery() {
        AtomicInteger calls = new AtomicInteger();
        YamlServiceRegistry registry = (YamlServiceRegistry) registry("""
                services:
                  - service: catalog-service
                    runtime:
                      platform:
                        TEST: TAS
                """);
        registry.applyDiscovery(new RegistryDiscoveryUpdate("catalog-service",
                RegistryProvenance.DISCOVERED_GITLAB,
                Map.of("splunk.appNames.TEST", List.of("catalog-green-test")), Set.of()));
        LiveSplunkConnector connector = connector(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        }, properties(), registry);

        assertThat(connector.searchServiceEventsDetailed("catalog-service", Environment.TEST, QUERY).outcome())
                .isEqualTo(SplunkSearchOutcome.UNCONFIGURED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void marksPerMessageContentTruncationInResultAndEvidenceMetadata() {
        String message = "x".repeat(4_001);
        LiveSplunkConnector connector = connector(status(HttpStatus.OK, ""), properties(), emptyRegistry());

        SplunkConnectorResult result = connector.parse(
                "{\"result\":{\"message\":\"" + message + "\"}}", Environment.TEST, QUERY,
                LiveSplunkConnector.SearchKind.EVENTS);

        assertThat(result.result().truncated()).isTrue();
        assertThat(result.result().evidence()).singleElement().satisfies(item -> {
            assertThat(item.content()).hasSize(4_000);
            assertThat(item.metadata()).containsEntry("contentTruncated", "true");
        });
    }

    @Test
    void sentinelResultMakesTruncationObservableAndIsNotReturned() {
        String body = IntStream.rangeClosed(1, QUERY.maxResults() + 1)
                .mapToObj(index -> "{\"result\":{\"message\":\"Synthetic event "
                        + "x".repeat(index) + "\"}}")
                .collect(java.util.stream.Collectors.joining("\n"));
        LiveSplunkConnector connector = connector(status(HttpStatus.OK, ""), properties(), emptyRegistry());

        SplunkConnectorResult result = connector.parse(body, Environment.TEST, QUERY,
                LiveSplunkConnector.SearchKind.EVENTS);

        assertThat(result.result().rawResultCount()).isEqualTo(QUERY.maxResults() + 1);
        assertThat(result.result().truncated()).isTrue();
        assertThat(result.result().evidence()).hasSize(QUERY.maxResults());
    }

    private static void assertOutcome(HttpStatus status, SplunkSearchOutcome expected) {
        AtomicInteger calls = new AtomicInteger();
        LiveSplunkConnector connector = connector(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(status).build());
        }, properties(), emptyRegistry());

        SplunkConnectorResult result = connector.searchErrorsForServiceDetailed(
                "catalog-service", Environment.TEST, QUERY);

        assertThat(result.outcome()).isEqualTo(expected);
        assertThat(result.result().evidence()).isEmpty();
        assertThat(calls).hasValue(1);
    }

    private static LiveSplunkConnector connector(org.springframework.web.reactive.function.client.ExchangeFunction exchange,
            SplunkProperties properties, ServiceRegistry registry) {
        return new LiveSplunkConnector(WebClient.builder().exchangeFunction(exchange),
                new ObjectMapper(), registry, properties);
    }

    private static org.springframework.web.reactive.function.client.ExchangeFunction status(HttpStatus status, String body) {
        return request -> Mono.just(ClientResponse.create(status).body(body).build());
    }

    private static SplunkProperties properties() {
        SplunkProperties properties = new SplunkProperties();
        properties.setBaseUrl("https://logs.example.invalid");
        properties.setToken("synthetic-token");
        properties.setTestIndexes("test_index");
        SplunkFieldProfile profile = new SplunkFieldProfile();
        profile.setName("platform-json");
        profile.setSourcetype("cf:logmessage");
        profile.setFields(Map.of(
                "tracking-id", List.of("msg.traceId"),
                "service", List.of("msg.appName"),
                "http-status", List.of("msg.statusCode"),
                "message", List.of("msg.detail"),
                "operation", List.of("msg.operation")));
        properties.setFieldProfiles(List.of(profile));
        return properties;
    }

    private static SplunkFieldNormalizer normalizer() {
        return new SplunkFieldNormalizer(properties().validatedFieldProfiles());
    }

    private static ServiceRegistry emptyRegistry() {
        return registry("""
                services:
                  - service: catalog-service
                    runtime:
                      platform:
                        DEV: TAS
                        TEST: TAS
                    splunk:
                      appNames:
                        DEV: [catalog-green-dev]
                        TEST: [catalog-green-test]
                      gatewayNames:
                        TEST: [catalog-proxy-test]
                      fieldProfiles:
                        TEST: [platform-json]
                """);
    }

    private static ServiceRegistry registry(String yaml) {
        YamlServiceRegistry registry = new YamlServiceRegistry(
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        registry.load();
        return registry;
    }
}
