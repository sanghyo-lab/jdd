package com.jdd.agent.infra;

import com.jdd.agent.domain.ModelUsage;

/** OAuth usage is observed separately and never priced as API-key credit consumption. */
public interface OAuthCallJournal {
    void start(String callId, String investigationId, int iteration, String model);
    void finish(String callId, String actualModel, ModelUsage usage, String outcome, long elapsedMillis);
    OAuthCallJournal NONE = new OAuthCallJournal() {
        public void start(String id, String investigation, int iteration, String model) {}
        public void finish(String id, String model, ModelUsage usage, String outcome, long elapsed) {}
    };
}
