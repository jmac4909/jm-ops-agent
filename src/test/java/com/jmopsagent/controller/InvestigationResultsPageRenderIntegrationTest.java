package com.jmopsagent.controller;

import com.jmopsagent.connector.mock.MockFixtures;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.InvestigationStatus;
import com.jmopsagent.orchestration.InvestigationApplicationService;
import com.jmopsagent.orchestration.InvestigationOrchestrator;
import com.jmopsagent.persistence.InvestigationRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:jmops-results-render;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles({"test", "local-mock"})
class InvestigationResultsPageRenderIntegrationTest {

    @LocalServerPort int port;
    @Autowired InvestigationApplicationService applicationService;
    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired InvestigationRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void singleFormInfersHistoricalWindowAndRendersIt() throws Exception {
        var cookies = new java.net.CookieManager();
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        String origin = "http://127.0.0.1:" + port;
        var home = client.send(HttpRequest.newBuilder(URI.create(origin)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(home.body()).contains("Describe what happened", "Service hint (optional)", "Tracking ID hint (optional)")
                .doesNotContain("data-tab-target", "Investigate a past incident", "name=\"incidentStart\"");
        var csrf = java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(home.body());
        assertThat(csrf.find()).isTrue();
        String body = "userProblem=" + java.net.URLEncoder.encode(
                "catalog-service failed in TEST between 2025-09-09T09:00:00Z and 2025-09-09T12:00:00Z; now fixed",
                java.nio.charset.StandardCharsets.UTF_8) + "&_csrf=" + java.net.URLEncoder.encode(csrf.group(1), java.nio.charset.StandardCharsets.UTF_8);
        var submitted = client.send(HttpRequest.newBuilder(URI.create(origin + "/investigations"))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(submitted.statusCode()).isBetween(300, 399);
        String location = submitted.headers().firstValue("Location").orElseThrow();
        var detail = client.send(HttpRequest.newBuilder(URI.create(origin).resolve(location)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("Evidence window (UTC)", "2025-09-09T09:00:00Z", "2025-09-09T12:00:00Z");
        String id = location.substring(location.lastIndexOf('/') + 1);
        // Let the owned asynchronous job finish before the next test cleans its rows.
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (!applicationService.get(java.util.UUID.fromString(id)).getStatus().isTerminal() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(applicationService.get(java.util.UUID.fromString(id)).getStatus()).isEqualTo(InvestigationStatus.COMPLETED);
        var status = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/investigations/" + id)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(status.body()).contains("\"incidentStart\":\"2025-09-09T09:00:00Z\"", "\"incidentEnd\":\"2025-09-09T12:00:00Z\"");
    }

    @Test
    void completedResultsPageRendersForADirectRequestWithoutAnExistingSession() throws Exception {
        Investigation created = applicationService.createTrackingInvestigation(MockFixtures.TRACKING_ID, "TEST");
        orchestrator.investigate(created.getId());
        assertThat(applicationService.get(created.getId()).getStatus()).isEqualTo(InvestigationStatus.COMPLETED);

        HttpRequest request = HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + port + "/investigations/" + created.getId())).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("edge-gateway", "identity-service", "catalog-service", "CONFIG")
                .contains("name=\"_csrf\"");
    }
}
