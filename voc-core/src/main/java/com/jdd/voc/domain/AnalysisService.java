package com.jdd.voc.domain;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AnalysisService {
    private static final Set<String> FIELDS = Set.of("requestKey", "ticketVersion", "previousInvestigationId");
    private final AnalysisRepository repository;
    private final Clock clock;

    public AnalysisService(AnalysisRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public AnalysisRequest request(String ticketId, Map<String, Object> body) {
        if (body == null || !FIELDS.containsAll(body.keySet()))
            throw VocFailure.invalid("지원하지 않는 분석 요청 필드입니다.");
        String key = identifier(body.get("requestKey"), "requestKey");
        long version = TicketService.positiveInteger(body.get("ticketVersion"), "ticketVersion");
        Object previous = body.get("previousInvestigationId");
        return repository.createOrReuse(ticketId, key, version,
                previous == null ? null : identifier(previous, "previousInvestigationId"),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    public AnalysisRequest get(String ticketId, String analysisRequestId) {
        return repository.find(ticketId, analysisRequestId).orElseThrow(AnalysisService::notFound);
    }

    public List<AnalysisRequest.Summary> list(String ticketId) {
        return repository.list(ticketId).stream().map(AnalysisRequest::summary).toList();
    }

    public static VocFailure notFound() {
        return new VocFailure("NOT_FOUND", "이 티켓에 속한 분석 요청을 찾을 수 없습니다.");
    }

    private static String identifier(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank())
            throw VocFailure.invalid(field + "는 비어 있지 않은 문자열이어야 합니다.");
        return text;
    }
}
