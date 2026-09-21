package com.jdd.agent.infra;

import com.jdd.agent.domain.Investigation.AnalysisReport;
import com.jdd.agent.domain.InvestigationRunner;
import com.jdd.agent.domain.ReportReview;
import tools.jackson.databind.json.JsonMapper;

public final class InvestigationReportDecoder implements InvestigationRunner.ReportDecoder {
    private final JsonMapper json;
    public InvestigationReportDecoder(JsonMapper json) { this.json = json; }
    @Override public AnalysisReport decode(String candidate) { return json.readValue(candidate, AnalysisReport.class); }
    @Override public ReportReview decodeReview(String candidate) { return json.readValue(candidate, ReportReview.class); }
}
