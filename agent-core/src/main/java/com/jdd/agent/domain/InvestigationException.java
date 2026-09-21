package com.jdd.agent.domain;

public final class InvestigationException extends RuntimeException {
    private final String code;

    public InvestigationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }

    public static InvestigationException invalid(String message) {
        return new InvestigationException("INVALID_REQUEST", message);
    }

    public static InvestigationException notFound() {
        return new InvestigationException("NOT_FOUND", "Investigation or evidence was not found");
    }

    public static InvestigationException queueFull() {
        return new InvestigationException("INVESTIGATION_QUEUE_FULL", "Investigation queue is full; retry the same request key later");
    }
}
