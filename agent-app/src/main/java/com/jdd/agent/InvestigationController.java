package com.jdd.agent;

import com.jdd.agent.domain.Investigation;
import com.jdd.agent.domain.InvestigationInput;
import com.jdd.agent.domain.InvestigationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/investigations")
public class InvestigationController {
    private final InvestigationService service;

    public InvestigationController(InvestigationService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Investigation.Accepted> submit(@RequestBody InvestigationInput input) {
        return ResponseEntity.accepted().body(service.submit(input));
    }

    @GetMapping("/{investigationId}")
    public Investigation get(@PathVariable String investigationId) { return service.get(investigationId); }

    @GetMapping("/{investigationId}/evidence/{evidenceId}")
    public Investigation.EvidenceDetail evidence(@PathVariable String investigationId, @PathVariable String evidenceId) {
        return service.evidence(investigationId, evidenceId);
    }
}
