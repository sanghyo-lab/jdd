package com.jdd.commerce.common;

public class CommerceException extends RuntimeException {
    private final int status;
    private final String code;
    private final boolean retryable;

    public CommerceException(int status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public int status() { return status; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }

    public static CommerceException invalid(String message) {
        return new CommerceException(400, "INVALID_INPUT", message, false);
    }

    public static CommerceException notFound(String entity) {
        return new CommerceException(404, "NOT_FOUND", entity + " was not found", false);
    }
}
