package com.jdd.voc;

import com.jdd.voc.domain.AnalysisRequest;
import com.jdd.voc.domain.AnalysisService;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tickets/{ticketId}/analyses")
public class AnalysisController {
    private final AnalysisService analyses;
    public AnalysisController(AnalysisService analyses) { this.analyses = analyses; }

    @PostMapping
    public ResponseEntity<AnalysisRequest> request(@PathVariable String ticketId, @RequestBody Map<String, Object> body) {
        AnalysisRequest saved = analyses.request(ticketId, body);
        return ResponseEntity.accepted().location(URI.create("/api/tickets/" + ticketId + "/analyses/" + saved.analysisRequestId()))
                .body(saved);
    }

    @GetMapping("/{analysisRequestId}")
    public AnalysisRequest detail(@PathVariable String ticketId, @PathVariable String analysisRequestId) {
        return analyses.get(ticketId, analysisRequestId);
    }
}
