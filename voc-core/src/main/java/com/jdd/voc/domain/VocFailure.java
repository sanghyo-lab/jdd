package com.jdd.voc.domain;

public final class VocFailure extends RuntimeException {
    private final String code;
    public VocFailure(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
    public static VocFailure invalid(String message) { return new VocFailure("INVALID_REQUEST", message); }
    public static VocFailure notFound() { return new VocFailure("NOT_FOUND", "티켓을 찾을 수 없습니다."); }
    public static VocFailure versionConflict() {
        return new VocFailure("TICKET_VERSION_CONFLICT", "티켓이 변경되었습니다. 최신 내용을 확인해 주세요.");
    }
}
