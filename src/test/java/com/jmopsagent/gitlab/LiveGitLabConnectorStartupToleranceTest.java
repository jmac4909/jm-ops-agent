package com.jmopsagent.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.jmopsagent.registry.ServiceRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class LiveGitLabConnectorStartupToleranceTest {

    @Test
    void invalidEndpointBuildsAnInertConnectorWithoutSendingCredentials() {
        AtomicInteger calls = new AtomicInteger();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        });

        LiveGitLabConnector connector = new LiveGitLabConnector(builder, mock(ServiceRegistry.class),
                "https" + "://invalid host.example.invalid/private-marker", "gitlab-sensitive-value");

        assertThat(connector.resolveRepository("catalog-service")).isEmpty();
        assertThat(calls).hasValue(0);
    }
}
