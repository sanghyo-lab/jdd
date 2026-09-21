package com.jdd.agent.infra;

import com.jdd.agent.domain.ModelUsage;
import tools.jackson.databind.JsonNode;

/** Reads one native HTTP response; never adds a ChatClient/advisor cumulative usage total. */
public final class OpenAiUsageParser {
    private OpenAiUsageParser() {}
    public static ModelUsage parse(JsonNode response) {
        JsonNode usage = response.path("usage");
        if (!usage.isObject()) return null;
        var input = usage.path("prompt_tokens_details");
        var output = usage.path("completion_tokens_details");
        try {
            Long promptTokens = number(usage, "prompt_tokens"), completionTokens = number(usage, "completion_tokens");
            Long total = number(usage, "total_tokens");
            if (total != null && promptTokens != null && completionTokens != null
                    && (promptTokens > total || completionTokens != total - promptTokens))
                throw new IllegalArgumentException("Inconsistent native usage total");
            return new ModelUsage(promptTokens, completionTokens,
                    number(input, "cached_tokens"), number(input, "cache_write_tokens"), number(output, "reasoning_tokens"));
        } catch (IllegalArgumentException invalidUsage) { return null; }
    }
    private static Long number(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new IllegalArgumentException("Invalid native usage");
        return value.longValue();
    }
}
