package com.jmopsagent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** Minimal HTTP client builder for the servlet-based application and read-only REST connectors. */
@Configuration(proxyBeanMethods = false)
public class ConnectorHttpConfiguration {
    @Bean
    @ConditionalOnMissingBean(WebClient.Builder.class)
    WebClient.Builder connectorWebClientBuilder() {
        return WebClient.builder();
    }
}
