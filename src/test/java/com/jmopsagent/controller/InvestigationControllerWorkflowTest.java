package com.jmopsagent.controller;

import com.jmopsagent.conversation.FollowUpConversationService;
import com.jmopsagent.domain.ConfidenceLevel;
import com.jmopsagent.domain.DeploymentEnvironment;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.domain.RootCauseCategory;
import com.jmopsagent.orchestration.InvestigationApplicationService;
import com.jmopsagent.orchestration.InvestigationWorkQueue;
import com.jmopsagent.ui.InvestigationViewAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InvestigationControllerWorkflowTest {
    private InvestigationApplicationService investigations;
    private InvestigationWorkQueue workQueue;
    private FollowUpConversationService followUps;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        investigations = mock(InvestigationApplicationService.class);
        workQueue = mock(InvestigationWorkQueue.class);
        followUps = mock(FollowUpConversationService.class);
        InvestigationController controller = new InvestigationController(investigations, workQueue, followUps,
                mock(InvestigationViewAssembler.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void trackingPostPersistsQueuesAndRedirectsToTheInvestigation() throws Exception {
        Investigation investigation = Investigation.forTrackingId("DEMO-TRACE-001", DeploymentEnvironment.TEST);
        when(investigations.createTrackingInvestigation("DEMO-TRACE-001", "TEST")).thenReturn(investigation);

        mvc.perform(post("/investigations/tracking")
                        .param("trackingId", "DEMO-TRACE-001")
                        .param("environment", "TEST"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/investigations/" + investigation.getId()));

        verify(investigations).createTrackingInvestigation("DEMO-TRACE-001", "TEST");
        verify(workQueue).submit(investigation.getId());
    }

    @Test
    void rejectedEnvironmentReturnsToTrackingFormWithoutQueuingWork() throws Exception {
        when(investigations.createTrackingInvestigation("DEMO-TRACE-001", "PROD"))
                .thenThrow(new IllegalArgumentException("Only DEV and TEST environments are allowed"));

        mvc.perform(post("/investigations/tracking")
                        .param("trackingId", "DEMO-TRACE-001")
                        .param("environment", "PROD"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/#tracking"))
                .andExpect(flash().attribute("error", "Only DEV and TEST environments are allowed"));

        verifyNoInteractions(workQueue);
    }

    @Test
    void feedbackAndFollowUpPostsUsePostRedirectGet() throws Exception {
        Investigation investigation = Investigation.forTrackingId("DEMO-TRACE-001", DeploymentEnvironment.TEST);

        mvc.perform(post("/investigations/{id}/feedback", investigation.getId())
                        .param("userFeedback", "PARTIALLY")
                        .param("actualRootCause", "Confirmed configuration mismatch")
                        .param("successfulRemediation", "Reviewed deployment"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/investigations/" + investigation.getId()));
        mvc.perform(post("/investigations/{id}/follow-ups", investigation.getId())
                        .param("question", "Why configuration?"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/investigations/" + investigation.getId()));

        verify(investigations).recordFeedback(investigation.getId(), "PARTIALLY",
                "Confirmed configuration mismatch", "Reviewed deployment");
        verify(followUps).ask(investigation.getId(), "Why configuration?");
    }

    @Test
    void explicitCodeEscalationQueuesReadOnlyInspectionAndRedirects() throws Exception {
        Investigation investigation = Investigation.forTrackingId("DEMO-TRACE-001", DeploymentEnvironment.TEST);
        investigation.setService("catalog-service");
        investigation.complete("Application-level investigation recommended", ConfidenceLevel.MEDIUM,
                RootCauseCategory.CODE, List.of("Inspect the deployed revision"));
        when(investigations.get(investigation.getId())).thenReturn(investigation);

        mvc.perform(post("/investigations/{id}/code", investigation.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/investigations/" + investigation.getId()));

        verify(workQueue).submitCodeInvestigation(investigation.getId());
    }

    @Test
    void codeEscalationIsRejectedWhenAnOperationalCauseWasEstablished() throws Exception {
        Investigation investigation = Investigation.forServiceTriage(
                "catalog-service", DeploymentEnvironment.TEST, "HTTP 500");
        investigation.complete("Confirmed configuration issue", ConfidenceLevel.HIGH,
                RootCauseCategory.CONFIG, List.of("Correct through the approved workflow"));
        when(investigations.get(investigation.getId())).thenReturn(investigation);

        mvc.perform(post("/investigations/{id}/code", investigation.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/investigations/" + investigation.getId()))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("only when")));

        verifyNoInteractions(workQueue);
    }
}
