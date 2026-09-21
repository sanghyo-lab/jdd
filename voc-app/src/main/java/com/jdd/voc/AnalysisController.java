package com.jdd.voc;

import com.jdd.voc.domain.AnalysisRequest;
import com.jdd.voc.domain.AnalysisService;
import com.jdd.voc.domain.AnalysisAccessService;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tickets/{ticketId}/analyses")
public class AnalysisController {
    private final AnalysisService analyses;
    private final AnalysisAccessService access;
    public AnalysisController(AnalysisService analyses, AnalysisAccessService access) { this.analyses = analyses; this.access = access; }

    @PostMapping
    public ResponseEntity<AnalysisRequest> request(@PathVariable String ticketId, @RequestBody Map<String, Object> body) {
        AnalysisRequest saved = analyses.request(ticketId, body);
        return ResponseEntity.accepted().location(URI.create("/api/tickets/" + ticketId + "/analyses/" + saved.analysisRequestId()))
                .body(saved);
    }

    @GetMapping("/{analysisRequestId}")
    public AnalysisRequest detail(@PathVariable String ticketId, @PathVariable String analysisRequestId,
                                  @RequestParam(defaultValue="false") boolean refresh) {
        return access.view(ticketId, analysisRequestId, refresh);
    }

    @GetMapping("/{analysisRequestId}/evidence/{evidenceId}")
    public Map<String, Object> evidence(@PathVariable String ticketId, @PathVariable String analysisRequestId, @PathVariable String evidenceId) {
        return access.evidence(ticketId, analysisRequestId, evidenceId);
    }
}
