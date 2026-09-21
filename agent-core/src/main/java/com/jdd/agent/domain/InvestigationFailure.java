package com.jdd.agent.domain;

import com.jdd.agent.domain.Investigation.ApiError;

public final class InvestigationFailure extends RuntimeException {
    private final ApiError error;
    public InvestigationFailure(ApiError error) { super(error.code()); this.error = error; }
    public ApiError error() { return error; }
    public static InvestigationFailure modelConfiguration() {
        return new InvestigationFailure(new ApiError("LLM_CONFIGURATION_ERROR", "모델 실행 설정과 허용 범위를 확인해 주세요.", false));
    }
    public static InvestigationFailure unavailable() {
        return new InvestigationFailure(new ApiError("LLM_UNAVAILABLE", "모델 응답을 받지 못했습니다. 허용 범위 확인 후 새 요청으로 재조사할 수 있습니다.", true));
    }
    public static ApiError from(PaidModelGate.Rejected rejected) {
        return switch (rejected.reason()) {
            case CONFIGURATION -> modelConfiguration().error();
            case TRANSPORT -> unavailable().error();
            case BUDGET_OR_LIMIT -> new ApiError("INVESTIGATION_BUDGET_EXCEEDED", "조사 비용 또는 호출 한도로 새 모델 호출을 중단했습니다.", false);
        };
    }
}
