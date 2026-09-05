package com.jmopsagent.connector;

import com.jmopsagent.gitlab.GitLabConnector;
import com.jmopsagent.jenkins.JenkinsConnector;
import com.jmopsagent.kubernetes.KubernetesConnector;
import com.jmopsagent.splunk.SplunkConnector;
import com.jmopsagent.tas.TasConnector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jmops-live-connectors;DB_CLOSE_DELAY=-1",
        "jmops.claude.enabled=false"
})
@ActiveProfiles("local-live")
class LiveConnectorContextTest {
    @Autowired KubernetesConnector kubernetes;
    @Autowired TasConnector tas;
    @Autowired SplunkConnector splunk;
    @Autowired JenkinsConnector jenkins;
    @Autowired GitLabConnector gitLab;

    @Test
    void liveProfileBuildsOnlyNarrowConnectorInterfaces() {
        assertThat(kubernetes).isNotNull();
        assertThat(tas).isNotNull();
        assertThat(splunk).isNotNull();
        assertThat(jenkins).isNotNull();
        assertThat(gitLab).isNotNull();
    }
}
