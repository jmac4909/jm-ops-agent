package com.jmopsagent.controller;

import com.jmopsagent.conversation.FollowUpConversationService;
import com.jmopsagent.domain.Investigation;
import com.jmopsagent.orchestration.InvestigationApplicationService;
import com.jmopsagent.orchestration.InvestigationWorkQueue;
import com.jmopsagent.ui.InvestigationViewAssembler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.security.web.csrf.CsrfToken;

import java.util.UUID;

@Controller
public class InvestigationController {
    private final InvestigationApplicationService investigations;
    private final InvestigationWorkQueue workQueue;
    private final FollowUpConversationService followUps;
    private final InvestigationViewAssembler viewAssembler;

    public InvestigationController(InvestigationApplicationService investigations,
                                   InvestigationWorkQueue workQueue,
                                   FollowUpConversationService followUps,
                                   InvestigationViewAssembler viewAssembler) {
        this.investigations = investigations;
        this.workQueue = workQueue;
        this.followUps = followUps;
        this.viewAssembler = viewAssembler;
    }

    @GetMapping("/")
    public String home(Model model, HttpServletRequest request) {
        loadCsrfTokenBeforeRendering(request);
        model.addAttribute("recentInvestigations", investigations.recent(20));
        return "index";
    }

    @PostMapping("/investigations/tracking")
    public String startTracking(@RequestParam String trackingId,
                                @RequestParam String environment,
                                RedirectAttributes redirect) {
        try {
            Investigation investigation = investigations.createTrackingInvestigation(trackingId, environment);
            workQueue.submit(investigation.getId());
            return "redirect:/investigations/" + investigation.getId();
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            redirect.addFlashAttribute("trackingId", trackingId);
            redirect.addFlashAttribute("environment", environment);
            return "redirect:/#tracking";
        }
    }

    @PostMapping("/investigations/service")
    public String startService(@RequestParam String service,
                               @RequestParam String environment,
                               @RequestParam String userProblem,
                               RedirectAttributes redirect) {
        try {
            Investigation investigation = investigations.createServiceInvestigation(service, environment, userProblem);
            workQueue.submit(investigation.getId());
            return "redirect:/investigations/" + investigation.getId();
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            redirect.addFlashAttribute("service", service);
            redirect.addFlashAttribute("environment", environment);
            redirect.addFlashAttribute("userProblem", userProblem);
            return "redirect:/#service";
        }
    }

    @GetMapping("/investigations/{id}")
    public String detail(@PathVariable UUID id, Model model, HttpServletRequest request) {
        // The first form is late in this large page. Resolve the deferred token before the
        // response buffer can be committed, including when a user opens a saved URL directly.
        loadCsrfTokenBeforeRendering(request);
        Investigation investigation = investigations.get(id);
        var evidence = investigations.evidence(id);
        var events = investigations.timeline(id);
        model.addAttribute("investigation", investigation);
        model.addAttribute("evidenceItems", evidence);
        model.addAttribute("timeline", viewAssembler.timeline(events, evidence));
        model.addAttribute("serviceChain", viewAssembler.serviceChain(evidence));
        model.addAttribute("followUps", followUps.list(id));
        model.addAttribute("recentInvestigations", investigations.recent(20));
        return "investigation";
    }

    private static void loadCsrfTokenBeforeRendering(HttpServletRequest request) {
        Object attribute = request.getAttribute(CsrfToken.class.getName());
        if (attribute instanceof CsrfToken token) token.getToken();
    }

    @PostMapping("/investigations/{id}/feedback")
    public String feedback(@PathVariable UUID id,
                           @RequestParam String userFeedback,
                           @RequestParam(required = false) String actualRootCause,
                           @RequestParam(required = false) String successfulRemediation,
                           RedirectAttributes redirect) {
        try {
            investigations.recordFeedback(id, userFeedback, actualRootCause, successfulRemediation);
            redirect.addFlashAttribute("notice", "Feedback saved for future historical matching.");
        } catch (IllegalArgumentException | IllegalStateException | EntityNotFoundException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/investigations/" + id;
    }

    @PostMapping("/investigations/{id}/follow-ups")
    public String followUp(@PathVariable UUID id, @RequestParam String question, RedirectAttributes redirect) {
        try {
            followUps.ask(id, question);
        } catch (IllegalArgumentException | IllegalStateException | EntityNotFoundException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/investigations/" + id;
    }

    @PostMapping("/investigations/{id}/code")
    public String code(@PathVariable UUID id, RedirectAttributes redirect) {
        try {
            Investigation investigation = investigations.get(id);
            if (investigation.getStatus() != com.jmopsagent.domain.InvestigationStatus.COMPLETED) {
                throw new IllegalStateException("The operational investigation must complete before code inspection");
            }
            if (investigation.getService() == null || investigation.getService().isBlank()) {
                throw new IllegalStateException("Code inspection requires a localized service");
            }
            if (investigation.getRootCauseCategory() != com.jmopsagent.domain.RootCauseCategory.CODE
                    && investigation.getRootCauseCategory() != com.jmopsagent.domain.RootCauseCategory.UNKNOWN) {
                throw new IllegalStateException(
                        "Code inspection is available only when the operational evidence points to code or remains inconclusive");
            }
            workQueue.submitCodeInvestigation(id);
        } catch (IllegalStateException | EntityNotFoundException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/investigations/" + id;
    }
}
