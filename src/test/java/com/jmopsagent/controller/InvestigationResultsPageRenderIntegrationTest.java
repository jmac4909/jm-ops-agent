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
