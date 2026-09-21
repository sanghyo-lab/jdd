package com.jdd.agent.domain;

/** Per HTTP attempt only. Null means not observed; reasoning is a subset of output tokens. */
public record ModelUsage(Long inputTokens, Long outputTokens, Long cachedInputTokens,
                         Long cacheWriteTokens, Long reasoningTokens) {
    public ModelUsage {
        nonnegative(inputTokens);
        nonnegative(outputTokens);
        nonnegative(cachedInputTokens);
        nonnegative(cacheWriteTokens);
        nonnegative(reasoningTokens);
        if (inputTokens != null && cachedInputTokens != null && cachedInputTokens > inputTokens)
            throw new IllegalArgumentException("Cached tokens exceed input");
        if (inputTokens != null && cacheWriteTokens != null && cacheWriteTokens > inputTokens)
            throw new IllegalArgumentException("Cache writes exceed input");
        if (inputTokens != null && cachedInputTokens != null && cacheWriteTokens != null
                && cacheWriteTokens > inputTokens - cachedInputTokens)
            throw new IllegalArgumentException("Input token partitions overlap");
        if (outputTokens != null && reasoningTokens != null && reasoningTokens > outputTokens)
            throw new IllegalArgumentException("Reasoning tokens exceed output");
    }
    private static void nonnegative(Long value) {
        if (value != null && value < 0) throw new IllegalArgumentException("Negative token usage");
    }
}
