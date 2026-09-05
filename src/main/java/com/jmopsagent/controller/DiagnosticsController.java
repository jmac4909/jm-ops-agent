package com.jmopsagent.controller;

import com.jmopsagent.config.diagnostics.DiagnosticItem;
import com.jmopsagent.config.diagnostics.DiagnosticsSnapshot;
import com.jmopsagent.config.diagnostics.StartupDiagnosticsService;
import com.jmopsagent.orchestration.InvestigationApplicationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
public class DiagnosticsController {
    private final StartupDiagnosticsService diagnosticsService;
    private final InvestigationApplicationService investigations;

    public DiagnosticsController(StartupDiagnosticsService diagnosticsService,
                                 InvestigationApplicationService investigations) {
        this.diagnosticsService = diagnosticsService;
        this.investigations = investigations;
    }

    @GetMapping("/diagnostics")
    public String diagnostics(Model model) {
        DiagnosticsSnapshot snapshot = diagnosticsService.refresh();
        Map<String, String> values = new LinkedHashMap<>();
        for (DiagnosticItem item : snapshot.items()) {
            values.put(item.component(), item.status().name() + " — " + item.detail());
        }
        model.addAttribute("diagnostics", values);
        model.addAttribute("checkedAt", snapshot.checkedAt());
        model.addAttribute("recentInvestigations", investigations.recent(20));
        return "diagnostics";
    }

    @GetMapping("/api/diagnostics")
    @ResponseBody
    public DiagnosticsSnapshot diagnosticsApi() {
        return diagnosticsService.getDiagnostics();
    }
}
