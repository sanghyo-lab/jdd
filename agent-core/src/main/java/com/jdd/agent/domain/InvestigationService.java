package com.jdd.agent.domain;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

public final class InvestigationService {
    private final InvestigationRepository repository;
    private final Clock clock;

    public InvestigationService(InvestigationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Investigation.Accepted submit(InvestigationInput input) {
        var existing = repository.findByRequest(input.ticketId(), input.requestKey());
        if (existing.isPresent()) return acceptSameInput(existing.get(), input);

        if (input.previousInvestigationId() != null) {
            var previous = repository.find(input.previousInvestigationId())
                    .orElseThrow(InvestigationException::notFound);
            if (!previous.input().ticketId().equals(input.ticketId())) throw InvestigationException.notFound();
        }
        var now = clock.instant();
        var investigation = new Investigation("1.0", UUID.randomUUID().toString(), input.ticketId(),
                input.ticketVersion(), Investigation.Status.QUEUED, now, now, List.of(), List.of(), null, null);
        var stored = repository.insertOrFind(new InvestigationRepository.Stored(input, investigation));
        return acceptSameInput(stored, input);
    }

    private Investigation.Accepted acceptSameInput(InvestigationRepository.Stored stored, InvestigationInput input) {
        if (!stored.input().equals(input)) {
            throw new InvestigationException("REQUEST_KEY_CONFLICT", "This request key already has a different input");
        }
        var view = stored.investigation();
        return new Investigation.Accepted(view.investigationId(), view.ticketId(), view.status());
    }

    public Investigation get(String id) {
        return repository.find(id).orElseThrow(InvestigationException::notFound).investigation();
    }

    public Investigation.EvidenceDetail evidence(String investigationId, String evidenceId) {
        return repository.findEvidence(investigationId, evidenceId).orElseThrow(InvestigationException::notFound);
    }
}
