package com.jmopsagent.tas;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CF CLI settings. Target identifiers are non-secret logical names; target details and CF homes
 * belong in ignored external configuration.
 */
@ConfigurationProperties("jmops.tas")
public class TasProperties {
    private String executable = "cf";
    private String appPattern = "";
    private boolean environmentMetadataEnabled;

    // Backward-compatible one-target-per-environment settings.
    private String devApi = "";
    private String devOrg = "";
    private String devSpace = "";
    private String devHome = "";
    private String testApi = "";
    private String testOrg = "";
    private String testSpace = "";
    private String testHome = "";

    private Map<String, Target> targets = new LinkedHashMap<>();

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = nullToEmpty(executable);
    }

    public String getAppPattern() {
        return appPattern;
    }

    public void setAppPattern(String appPattern) {
        this.appPattern = nullToEmpty(appPattern);
    }

    public boolean isEnvironmentMetadataEnabled() {
        return environmentMetadataEnabled;
    }

    public void setEnvironmentMetadataEnabled(boolean environmentMetadataEnabled) {
        this.environmentMetadataEnabled = environmentMetadataEnabled;
    }

    public String getDevApi() {
        return devApi;
    }

    public void setDevApi(String devApi) {
        this.devApi = nullToEmpty(devApi);
    }

    public String getDevOrg() {
        return devOrg;
    }

    public void setDevOrg(String devOrg) {
        this.devOrg = nullToEmpty(devOrg);
    }

    public String getDevSpace() {
        return devSpace;
    }

    public void setDevSpace(String devSpace) {
        this.devSpace = nullToEmpty(devSpace);
    }

    public String getDevHome() {
        return devHome;
    }

    public void setDevHome(String devHome) {
        this.devHome = nullToEmpty(devHome);
    }

    public String getTestApi() {
        return testApi;
    }

    public void setTestApi(String testApi) {
        this.testApi = nullToEmpty(testApi);
    }

    public String getTestOrg() {
        return testOrg;
    }

    public void setTestOrg(String testOrg) {
        this.testOrg = nullToEmpty(testOrg);
    }

    public String getTestSpace() {
        return testSpace;
    }

    public void setTestSpace(String testSpace) {
        this.testSpace = nullToEmpty(testSpace);
    }

    public String getTestHome() {
        return testHome;
    }

    public void setTestHome(String testHome) {
        this.testHome = nullToEmpty(testHome);
    }

    public Map<String, Target> getTargets() {
        return targets;
    }

    public void setTargets(Map<String, Target> targets) {
        this.targets = targets == null ? new LinkedHashMap<>() : new LinkedHashMap<>(targets);
    }

    boolean hasLegacyValues() {
        return present(devApi) || present(devOrg) || present(devSpace) || present(devHome)
                || present(testApi) || present(testOrg) || present(testSpace) || present(testHome);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public static class Target {
        private String environment = "";
        private String api = "";
        private String org = "";
        private String space = "";
        private String home = "";

        public String getEnvironment() {
            return environment;
        }

        public void setEnvironment(String environment) {
            this.environment = nullToEmpty(environment);
        }

        public String getApi() {
            return api;
        }

        public void setApi(String api) {
            this.api = nullToEmpty(api);
        }

        public String getOrg() {
            return org;
        }

        public void setOrg(String org) {
            this.org = nullToEmpty(org);
        }

        public String getSpace() {
            return space;
        }

        public void setSpace(String space) {
            this.space = nullToEmpty(space);
        }

        public String getHome() {
            return home;
        }

        public void setHome(String home) {
            this.home = nullToEmpty(home);
        }
    }
}
