package com.jmopsagent.jenkins;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jenkins connection settings. Controller identifiers are logical, non-secret names; endpoint and
 * credential values belong in external configuration.
 */
@ConfigurationProperties("jmops.integrations.jenkins")
public class JenkinsProperties {
    private String baseUrl = "";
    private String username = "";
    private String token = "";
    private String jobPattern = "{service}-{environment}-deploy";
    private Map<String, Controller> controllers = new LinkedHashMap<>();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = nullToEmpty(baseUrl);
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = nullToEmpty(username);
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = nullToEmpty(token);
    }

    public String getJobPattern() {
        return jobPattern;
    }

    public void setJobPattern(String jobPattern) {
        this.jobPattern = nullToEmpty(jobPattern);
    }

    public Map<String, Controller> getControllers() {
        return controllers;
    }

    public void setControllers(Map<String, Controller> controllers) {
        this.controllers = controllers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(controllers);
    }

    boolean hasLegacyValues() {
        return !baseUrl.isBlank() || !username.isBlank() || !token.isBlank();
    }

    String effectiveJobPattern() {
        return jobPattern.isBlank() ? "{service}-{environment}-deploy" : jobPattern;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public static class Controller {
        private String baseUrl = "";
        private String username = "";
        private String token = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = nullToEmpty(baseUrl);
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = nullToEmpty(username);
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = nullToEmpty(token);
        }
    }
}
