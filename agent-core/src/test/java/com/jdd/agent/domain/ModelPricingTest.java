package com.jdd.agent.domain;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelPricingTest {
    private final ModelPricing price = new ModelPricing("synthetic-1", "mock-priced-model",
            new BigDecimal("2"), new BigDecimal("0.2"), new BigDecimal("2.5"), new BigDecimal("12"), true);

    @Test void partitionsInputAndDoesNotAddReasoningTwice() {
        var usage = new ModelUsage(1000L, 200L, 600L, 100L, 150L);
        assertEquals(0, price.observedCost(usage).orElseThrow().compareTo(new BigDecimal("0.00337")));
    }

    @Test void reservesMostExpensiveInputPartitionWithoutAssumingCacheHits() {
        assertEquals(0, price.maximumCost(1000, 200).compareTo(new BigDecimal("0.0049")));
    }

    @Test void missingMetricsAreUnknownRatherThanZero() {
        assertTrue(price.observedCost(null).isEmpty());
        assertTrue(price.observedCost(new ModelUsage(1000L, 200L, 0L, null, null)).isEmpty());
        assertTrue(price.observedCost(new ModelUsage(1000L, 200L, null, 0L, null)).isEmpty());
        assertNull(new ModelUsage(null, null, null, null, null).inputTokens());
    }

    @Test void unsupportedWriteMetricStaysNullWithoutBlockingAnOlderTariff() {
        var older = new ModelPricing("synthetic-older", "mock-old", new BigDecimal("2"),
                new BigDecimal("0.2"), BigDecimal.ZERO, new BigDecimal("12"), false);
        var usage = new ModelUsage(1000L, 200L, 0L, null, null);
        assertTrue(older.observedCost(usage).isPresent());
        assertNull(usage.cacheWriteTokens());
        assertTrue(older.observedCost(new ModelUsage(1000L, 200L, 0L, 1L, null)).isEmpty());
    }

    @Test void rejectsNegativeAndOverlappingCounters() {
        assertThrows(IllegalArgumentException.class, () -> new ModelUsage(-1L, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ModelUsage(10L, 1L, 6L, 5L, null));
        assertThrows(IllegalArgumentException.class, () -> new ModelUsage(10L, 1L, 0L, 0L, 2L));
        assertThrows(IllegalArgumentException.class, () -> price.maximumCost(0, 100));
    }
}
