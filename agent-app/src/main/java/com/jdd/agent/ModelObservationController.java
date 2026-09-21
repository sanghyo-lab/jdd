package com.jdd.agent;

import com.jdd.agent.domain.InvestigationException;
import com.jdd.agent.domain.ModelObservationRepository;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Internal runner diagnostics. Not allowlisted by the public web relay. */
@RestController
public class ModelObservationController {
    public record Observations(String schemaVersion, String investigationId, String runtime, String provider,
                               List<ModelObservationRepository.Call> calls) {}
    private final ModelObservationRepository observations;
    private final LlmRuntimeObservation runtime;

    public ModelObservationController(ModelObservationRepository observations, LlmRuntimeObservation runtime) {
        this.observations = observations;
        this.runtime = runtime;
    }

    @GetMapping("/internal/investigations/{investigationId}/model-observations")
    public ResponseEntity<Observations> get(@PathVariable String investigationId) {
        var calls = observations.find(investigationId).orElseThrow(InvestigationException::notFound);
        var config = runtime.configuration();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Observations("1.0",
                investigationId, config.get("runtime"), config.get("provider"), calls));
    }
}
