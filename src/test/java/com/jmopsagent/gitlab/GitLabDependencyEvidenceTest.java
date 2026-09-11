package com.jmopsagent.gitlab;

import com.jmopsagent.connector.*;
import com.jmopsagent.registry.YamlServiceRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.assertThat;

class GitLabDependencyEvidenceTest {
    @Test
    void historicalConfigReadsTheFileAtItsOldCommitAndNeverFallsBackToCurrentBranch() {
        var registry = new YamlServiceRegistry(new ByteArrayResource("""
                services:
                  - service: data-service
                    configServer.repository: https://git.example.invalid/demo/config
                """.getBytes(StandardCharsets.UTF_8)));
        registry.load();
        List<ClientRequest> requests = new ArrayList<>();
        var connector = new LiveGitLabConnector(WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            boolean commits = request.url().getPath().endsWith("commits");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", commits ? "application/json" : "text/plain")
                    .body(commits ? "[{\"id\":\"abc123\",\"committed_date\":\"2025-09-01T00:00:00Z\"}]"
                            : "database:\n  table: demo-historical-test\n  password: synthetic-secret\n").build());
        }), registry, "https://git.example.invalid", "synthetic-token");
        var config = connector.getRepositoryConfigurationAt("data-service", Environment.TEST,
                Instant.parse("2025-09-09T09:00:00Z"), 10_000).getFirst();
        assertThat(config.metadata()).containsEntry("ref", "abc123").containsEntry("runtimeVerified", "false");
        assertThat(config.content()).contains("abc123", "demo-historical-test").doesNotContain("synthetic-secret");
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst().url().getQuery()).contains("ref_name=master", "path=test/data-service-test.yml",
                "until=2025-09-09T09:00:00Z", "per_page=1");
        assertThat(requests.getLast().url().getQuery()).isEqualTo("ref=abc123");
        requests.clear();
        var missing = new LiveGitLabConnector(WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body("[]").build());
        }), registry, "https://git.example.invalid", "synthetic-token");
        assertThat(missing.getRepositoryConfigurationAt("data-service", Environment.TEST,
                Instant.parse("2025-09-09T09:00:00Z"), 10_000)).isEmpty();
        assertThat(requests).singleElement().satisfies(request -> assertThat(request.url().getPath()).endsWith("commits"));
    }

    @Test
    void readsDependencyBranchHistoryAndSeparateConfigurationRepoWithLiteralKeywordFiltering() {
        var registry = new YamlServiceRegistry(new ByteArrayResource("""
                services:
                  - service: data-service
                    gitlab:
                      repository: https://git.example.invalid/demo/data
                      defaultBranch: main
                    configServer:
                      repository: https://git.example.invalid/demo/config
                """.getBytes(StandardCharsets.UTF_8)));
        registry.load();
        List<ClientRequest> requests = new ArrayList<>();
        var builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            boolean commits = request.url().getPath().endsWith("commits");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", commits ? "application/json" : "text/plain")
                    .body(commits ? """
                            [{"id":"abc123","title":"DEMO-42 replace column projections","committed_date":"2026-09-01T12:00:00Z"},
                             {"id":"def456","title":"unrelated change","committed_date":"2026-09-08T12:00:00Z"}]
                            """ : "database:\n  table: demo-data-test\n  secret: |\n    synthetic-secret-line-one\n    synthetic-secret-line-two\nfeature:\n  enabled: true\n").build());
        });
        var connector = new LiveGitLabConnector(builder, registry, "https://git.example.invalid", "synthetic-token");
        var query = new EvidenceQuery(Instant.parse("2026-08-10T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z"), 10, 20_000);
        assertThat(connector.searchCommits("data-service", List.of("projection"), query))
                .extracting(CommitChange::commitSha).containsExactly("abc123");
        var config = connector.getRepositoryConfiguration("data-service", Environment.TEST, 10_000).getFirst();
        assertThat(config.content()).contains("database.table=demo-data-test", "feature.enabled=true", "REDACTED_AT_SOURCE")
                .doesNotContain("synthetic-secret");
        assertThat(config.metadata()).containsEntry("runtimeVerified", "false").containsEntry("ref", "master")
                .containsEntry("path", "test/data-service-test.yml");
        assertThat(requests.getFirst().url().getPath()).contains("demo/data/repository/commits");
        assertThat(requests.getFirst().url().getQuery()).contains("ref_name=main", "since=2026-08-10", "per_page=100");
        assertThat(requests.getLast().url().getPath()).contains("demo/config/repository/files/test/data-service-test.yml/raw");
        assertThat(requests).allSatisfy(request -> assertThat(request.method().name()).isEqualTo("GET"));
    }
}
