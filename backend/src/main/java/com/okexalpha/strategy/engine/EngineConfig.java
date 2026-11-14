package com.okexalpha.strategy.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次回放的执行假设（与 {@link StrategyParams} 的经济参数分开：这些是"怎么成交"，
 * 不是"策略长什么样"，因此不参与参数网格与签名）。
 *
 * @param fillMode               成交时点：NEXT_OPEN（默认）/ SIGNAL_CLOSE
 * @param entryRule              首仓入场规则
 * @param entryLookback          BREAKOUT 的回看根数
 * @param maintenanceMarginRate  维持保证金率（逐仓强平价用；分档数据到位后按仓位取真实值）
 * @param fundings               真实资金费结算序列（按时间升序）；空 = 用 params 的常量费率合成 8h 结算点
 */
public record EngineConfig(
        FillMode fillMode,
        EntryRule entryRule,
        int entryLookback,
        double maintenanceMarginRate,
        List<FundingPoint> fundings) {

    public static final double DEFAULT_MMR = 0.005d;
    public static final int DEFAULT_LOOKBACK = 20;

    public EngineConfig {
        fillMode = fillMode == null ? FillMode.NEXT_OPEN : fillMode;
        entryRule = entryRule == null ? EntryRule.ALWAYS_IN : entryRule;
        entryLookback = entryLookback < 1 ? DEFAULT_LOOKBACK : entryLookback;
        maintenanceMarginRate = maintenanceMarginRate <= 0d || maintenanceMarginRate >= 1d
                ? DEFAULT_MMR
                : maintenanceMarginRate;
        fundings = fundings == null ? List.of() : List.copyOf(fundings);
    }

    /** NEXT_OPEN + ALWAYS_IN + 0.5% MMR + 合成资金费：与旧实现的对照基线。 */
    public static EngineConfig standard() {
        return new EngineConfig(FillMode.NEXT_OPEN, EntryRule.ALWAYS_IN, DEFAULT_LOOKBACK, DEFAULT_MMR, List.of());
    }

    public EngineConfig withFillMode(FillMode mode) {
        return new EngineConfig(mode, entryRule, entryLookback, maintenanceMarginRate, fundings);
    }

    public EngineConfig withEntryRule(EntryRule rule) {
        return new EngineConfig(fillMode, rule, entryLookback, maintenanceMarginRate, fundings);
    }

    public EngineConfig withEntryLookback(int lookback) {
        return new EngineConfig(fillMode, entryRule, lookback, maintenanceMarginRate, fundings);
    }

    public EngineConfig withMaintenanceMarginRate(double mmr) {
        return new EngineConfig(fillMode, entryRule, entryLookback, mmr, fundings);
    }

    public EngineConfig withFundings(List<FundingPoint> points) {
        return new EngineConfig(fillMode, entryRule, entryLookback, maintenanceMarginRate, points);
    }

    /**
     * 合成 8h 结算点：从 {@code fromExclusive} 之后第一个 UTC 00/08/16 点开始，
     * 到 {@code toInclusive} 为止。只在没有真实费率数据时使用（结果页会标 funding_fallback）。
     */
    static List<FundingPoint> syntheticFundings(double ratePer8h, long fromExclusive, long toInclusive) {
        if (ratePer8h == 0d || toInclusive <= fromExclusive) {
            return List.of();
        }
        long interval = 8L * 3_600_000L;
        long first = (fromExclusive / interval + 1L) * interval;
        List<FundingPoint> points = new ArrayList<>();
        for (long ts = first; ts <= toInclusive; ts += interval) {
            points.add(new FundingPoint(ts, ratePer8h));
        }
        return points;
    }
}
