package com.jdd.agent.domain;

import java.util.Optional;

public interface InvestigationRepository {
    record Stored(InvestigationInput input, Investigation investigation) {}

    Optional<Stored> find(String investigationId);
    Optional<Stored> findByRequest(String ticketId, String requestKey);

    /** Atomically inserts or returns the winner of a concurrent submission with the same key. */
    Stored insertOrFind(Stored candidate);

    Optional<Investigation.EvidenceDetail> findEvidence(String investigationId, String evidenceId);
}
