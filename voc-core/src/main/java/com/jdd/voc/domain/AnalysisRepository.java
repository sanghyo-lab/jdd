package com.jdd.voc.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AnalysisRepository {
    /** Lock the ticket, deduplicate, check its version and capture the input in one transaction. */
    AnalysisRequest createOrReuse(String ticketId, String requestKey, long ticketVersion,
                                  String previousInvestigationId, Instant now);
    Optional<AnalysisRequest> find(String ticketId, String analysisRequestId);
    List<AnalysisRequest> list(String ticketId);
}
