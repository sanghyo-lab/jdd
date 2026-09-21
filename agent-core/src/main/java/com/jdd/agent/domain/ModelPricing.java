package com.jdd.agent.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** Text-only tariff snapshot, including the configured context length and service tier. USD per million tokens. */
public record ModelPricing(String version, String model, BigDecimal input, BigDecimal cachedInput,
                           BigDecimal cacheWrite, BigDecimal output, boolean chargesCacheWrites,
                           Long longContextThreshold, BigDecimal longInputMultiplier, BigDecimal longOutputMultiplier) {
    /** Compatibility for tariffs with no context-length price transition. */
    public ModelPricing(String version, String model, BigDecimal input, BigDecimal cachedInput,
                        BigDecimal cacheWrite, BigDecimal output, boolean chargesCacheWrites) {
        this(version, model, input, cachedInput, cacheWrite, output, chargesCacheWrites, null, null, null);
    }
    public ModelPricing {
        if (version == null || version.isBlank() || model == null || model.isBlank())
            throw new IllegalArgumentException("Pricing version and model are required");
        for (BigDecimal rate : new BigDecimal[]{input, cachedInput, cacheWrite, output}) {
            if (rate == null || rate.signum() < 0) throw new IllegalArgumentException("Invalid token rate");
        }
        if (longContextThreshold == null) {
            if (longInputMultiplier != null || longOutputMultiplier != null) throw new IllegalArgumentException("Context threshold is required");
        } else if (longContextThreshold < 1 || longInputMultiplier == null || longOutputMultiplier == null
                || longInputMultiplier.compareTo(BigDecimal.ONE) < 0 || longOutputMultiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("Invalid context price transition");
        }
    }

    public BigDecimal maximumCost(long inputTokenLimit, long outputTokenLimit) {
        if (inputTokenLimit < 1 || outputTokenLimit < 1) throw new IllegalArgumentException("Token limits must be positive");
        BigDecimal maximumInput = input.max(cachedInput);
        if (chargesCacheWrites) maximumInput = maximumInput.max(cacheWrite);
        return money(maximumInput.multiply(inputMultiplier(inputTokenLimit)).multiply(BigDecimal.valueOf(inputTokenLimit))
                .add(output.multiply(outputMultiplier(inputTokenLimit)).multiply(BigDecimal.valueOf(outputTokenLimit))).movePointLeft(6));
    }

    public Optional<BigDecimal> observedCost(ModelUsage usage) {
        if (usage == null || usage.inputTokens() == null || usage.outputTokens() == null
                || usage.cachedInputTokens() == null || (chargesCacheWrites && usage.cacheWriteTokens() == null))
            return Optional.empty();
        // Missing, unsupported cache-write metrics stay null in the recorded usage.
        if (!chargesCacheWrites && usage.cacheWriteTokens() != null && usage.cacheWriteTokens() > 0) return Optional.empty();
        long writes = chargesCacheWrites ? usage.cacheWriteTokens() : 0;
        long ordinary = usage.inputTokens() - usage.cachedInputTokens() - writes;
        BigDecimal amount = input.multiply(BigDecimal.valueOf(ordinary))
                .add(cachedInput.multiply(BigDecimal.valueOf(usage.cachedInputTokens())))
                .add(cacheWrite.multiply(BigDecimal.valueOf(writes))).multiply(inputMultiplier(usage.inputTokens()))
                .add(output.multiply(outputMultiplier(usage.inputTokens())).multiply(BigDecimal.valueOf(usage.outputTokens())));
        // Reasoning is already included in output; never charge it a second time.
        return Optional.of(money(amount.movePointLeft(6)));
    }

    private BigDecimal inputMultiplier(long tokens) {
        return longContextThreshold != null && tokens > longContextThreshold ? longInputMultiplier : BigDecimal.ONE;
    }
    private BigDecimal outputMultiplier(long tokens) {
        return longContextThreshold != null && tokens > longContextThreshold ? longOutputMultiplier : BigDecimal.ONE;
    }

    public static BigDecimal money(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) throw new IllegalArgumentException("Invalid USD amount");
        return amount.setScale(12, RoundingMode.CEILING);
    }
}
