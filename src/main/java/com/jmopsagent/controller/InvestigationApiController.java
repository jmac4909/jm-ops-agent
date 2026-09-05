package com.jmopsagent.controller;

import com.jmopsagent.domain.Investigation;
import com.jmopsagent.orchestration.InvestigationApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/investigations")
public class InvestigationApiController {
    private final InvestigationApplicationService investigations;

    public InvestigationApiController(InvestigationApplicationService investigations) {
        this.investigations = investigations;
    }

    @GetMapping("/{id}")
    public StatusResponse status(@PathVariable UUID id) {
        Investigation value = investigations.get(id);
        return new StatusResponse(value.getId(), value.getStatus().name(), value.getService(),
                value.getFinalDiagnosis(), value.getConfidence().name(), value.getRootCauseCategory().name(),
                value.getStartedAt(), value.getCompletedAt());
    }

    public record StatusResponse(UUID id, String status, String service, String diagnosis, String confidence,
                                 String rootCauseCategory, Instant startedAt, Instant completedAt) {}
}
