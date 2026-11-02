package com.okexalpha.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MartingaleBacktesterTest {

    private static final long MIN = 60_000L;

    private static StrategyParams params(String side, double margin, int leverage, int maxLayers,
            double stepPct, double scale, double tpPct, double slPct, int maxHoldHours) {
        return new StrategyParams(side, margin, leverage, maxLayers, stepPct, scale, tpPct, slPct,
                maxHoldHours, 0d, 0d, 0d);
    }

    private static PriceBar bar(long minute, double open, double high, double low, double close) {
        return new PriceBar(minute * MIN, open, high, low, close);
    }

    @Test
    @DisplayName("价格直接上行：触及止盈价即平仓，收益按杠杆放大")
    void takeProfit() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1.5, 1, 15, 24);
        BacktestOutcome out = MartingaleBacktester.run(List.of(bar(0, 100, 110, 99, 108)), p);

        assertThat(out.trades()).hasSize(1);
        BacktestTrade t = out.trades().get(0);
        assertThat(t.exitReason()).isEqualTo("TAKE_PROFIT");
        assertThat(t.exitPx()).isEqualTo(101);
        assertThat(t.pnl()).isEqualTo(10);
        assertThat(t.layers()).isEqualTo(1);
        assertThat(out.metrics().trades()).isEqualTo(1);
        assertThat(out.metrics().winRate()).isEqualTo(100);
        assertThat(out.metrics().returnPct()).isEqualTo(10);
    }

    @Test
    @DisplayName("价格连续下跌：逆势加仓摊低成本，层数达到上限后不再加")
    void layersMultiplyOnAdverseMove() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1.0, 50, 95, 5);
        List<PriceBar> bars = new ArrayList<>();
        bars.add(bar(0, 100, 100.5, 100, 100));
        bars.add(bar(1, 99, 99.1, 97.5, 98));
        bars.add(bar(2, 97.9, 98, 95.8, 96));
        bars.add(bar(3, 95.9, 96, 93.1, 94));
        bars.add(bar(4, 93.9, 94, 92, 93));
        for (int i = 5; i <= 400; i++) {
            bars.add(bar(i, 93, 93, 93, 93));
        }
        BacktestOutcome out = MartingaleBacktester.run(bars, p);

        assertThat(out.trades()).isNotEmpty();
        BacktestTrade t = out.trades().get(0);
        assertThat(t.layers()).isEqualTo(3);
        assertThat(t.avgEntry()).isLessThan(98);
        assertThat(t.exitReason()).isEqualTo("TIME_STOP");
    }

    @Test
    @DisplayName("暴跌穿强平线：按逐仓口径最多亏掉保证金，并计入 liquidation 计数")
    void liquidation() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1.5, 50, 90, 24);
        List<PriceBar> bars = List.of(bar(0, 100, 101, 99, 100), bar(1, 95, 96, 80, 82));
        BacktestOutcome out = MartingaleBacktester.run(bars, p);

        assertThat(out.trades()).hasSize(1);
        BacktestTrade t = out.trades().get(0);
        assertThat(t.exitReason()).isEqualTo("LIQUIDATED");
        assertThat(t.exitPx()).isEqualTo(90.5);
        assertThat(t.pnl()).isEqualTo(-100);
        assertThat(out.metrics().liquidations()).isEqualTo(1);
    }

    @Test
    @DisplayName("止损线按已用保证金百分比换算，触及即按该价平仓")
    void stopLoss() {
        StrategyParams p = params(StrategyParams.LONG, 100, 5, 3, 5, 1.5, 50, 10, 24);
        List<PriceBar> bars = List.of(bar(0, 100, 100.2, 100, 100), bar(1, 99, 99.5, 97, 97.5));
        BacktestOutcome out = MartingaleBacktester.run(bars, p);

        BacktestTrade t = out.trades().get(0);
        assertThat(t.exitReason()).isEqualTo("STOP_LOSS");
        assertThat(t.exitPx()).isEqualTo(98);
        assertThat(t.pnl()).isEqualTo(-10);
        assertThat(t.pnlPct()).isEqualTo(-10);
    }

    @Test
    @DisplayName("横盘超过最大持仓小时数：按时间止损平掉")
    void timeStop() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1.5, 50, 90, 1);
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i <= 120; i++) {
            bars.add(bar(i, 100, 100.01, 99.99, 100));
        }
        BacktestOutcome out = MartingaleBacktester.run(bars, p);

        // 平仓后下一根立即重开新仓，故第一笔之后还会有后续交易
        assertThat(out.trades()).isNotEmpty();
        BacktestTrade t = out.trades().get(0);
        assertThat(t.exitReason()).isEqualTo("TIME_STOP");
        assertThat(t.exitTsMs() - t.entryTsMs()).isEqualTo(60 * MIN);
    }

    @Test
    @DisplayName("多头每 8 小时累计一次资金费，正费率对多头成本")
    void fundingAccruesEveryEightHours() {
        StrategyParams p = new StrategyParams(StrategyParams.LONG, 100, 10, 3, 2, 1.5, 50, 90, 48,
                0d, 0.01d, 0d);
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i <= 24 * 60; i++) {
            bars.add(bar(i, 100, 100, 100, 100));
        }
        BacktestOutcome out = MartingaleBacktester.run(bars, p);

        BacktestTrade t = out.trades().get(0);
        assertThat(t.exitReason()).isEqualTo("END_OF_DATA");
        assertThat(t.funding()).isEqualTo(0.3);
        assertThat(t.pnl()).isEqualTo(-0.3);
    }

    @Test
    @DisplayName("空头方向：价格上涨触发止盈、下跌触发加仓")
    void shortSide() {
        StrategyParams p = params(StrategyParams.SHORT, 100, 10, 3, 2, 1.5, 5, 90, 24);
        BacktestOutcome out = MartingaleBacktester.run(List.of(bar(0, 100, 100.2, 94.5, 95)), p);

        BacktestTrade t = out.trades().get(0);
        assertThat(t.side()).isEqualTo(StrategyParams.SHORT);
        assertThat(t.exitReason()).isEqualTo("TAKE_PROFIT");
        assertThat(t.exitPx()).isEqualTo(95);
        assertThat(t.pnl()).isGreaterThan(0);
    }

    @Test
    @DisplayName("手续费开平各收一次，按名义价值计")
    void feesOnBothSides() {
        StrategyParams p = new StrategyParams(StrategyParams.LONG, 100, 10, 3, 2, 1.5, 1, 15, 24,
                0.001d, 0d, 0d);
        BacktestOutcome out = MartingaleBacktester.run(List.of(bar(0, 100, 110, 99, 108)), p);

        BacktestTrade t = out.trades().get(0);
        assertThat(t.fee()).isEqualTo(2.01);
        assertThat(t.pnl()).isEqualTo(7.99);
    }

    @Test
    @DisplayName("滑点让开仓价更高、平仓价更低")
    void slippage() {
        StrategyParams p = new StrategyParams(StrategyParams.LONG, 100, 10, 3, 2, 1.5, 1, 15, 1,
                0d, 0d, 10d);
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 0; i <= 120; i++) {
            bars.add(bar(i, 100, 100, 100, 100));
        }
        BacktestOutcome out = MartingaleBacktester.run(bars, p);

        BacktestTrade t = out.trades().get(0);
        assertThat(t.avgEntry()).isEqualTo(100.1);
        assertThat(t.exitReason()).isEqualTo("TIME_STOP");
        assertThat(t.pnl()).isLessThan(0);
    }

    @Test
    @DisplayName("空序列返回空结果，不抛异常")
    void emptySeries() {
        BacktestOutcome out = MartingaleBacktester.run(List.of(), params(StrategyParams.LONG, 100, 10, 3, 2, 1.5, 1, 15, 24));
        assertThat(out.metrics().trades()).isZero();
        assertThat(out.trades()).isEmpty();
        assertThat(out.equity()).isEmpty();
    }

    @Test
    @DisplayName("分层名义价值按 layerScale 等比放大")
    void notionalScalesPerLayer() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 5, 2, 1.5, 1, 15, 24);
        assertThat(MartingaleBacktester.notionalFor(p, 1)).isEqualTo(1000);
        assertThat(MartingaleBacktester.notionalFor(p, 2)).isEqualTo(1500);
        assertThat(MartingaleBacktester.notionalFor(p, 3)).isEqualTo(2250);
    }

    @Test
    @DisplayName("权益曲线按日打点，回撤峰值只在人工合成的最差情况出现")
    void equityCurveIsDailySeries() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1.5, 50, 90, 24 * 200);
        List<PriceBar> bars = new ArrayList<>();
        for (int day = 0; day < 5; day++) {
            for (int min = 0; min < 24 * 60; min++) {
                bars.add(bar(day * 24 * 60L + min, 100, 100, 100, 100));
            }
        }
        BacktestOutcome out = MartingaleBacktester.run(bars, p);
        // 5 个自然日各一点 + 序列结束平仓补一个收尾点
        assertThat(out.equity()).hasSize(6);
        assertThat(out.metrics().maxDrawdownPct()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("本金耗尽后停止开新仓：收益不会跌破 -100%（物理上不可破产两次）")
    void stopsTradingAfterCapitalExhausted() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 1, 2, 1.0, 50, 100, 1);
        List<PriceBar> bars = new ArrayList<>();
        // 反复"开仓 → 单向暴跌"的锯齿行情：每笔都亏掉整份保证金
        for (int round = 0; round < 20; round++) {
            long base = round * 10L;
            bars.add(bar(base, 100, 100, 100, 100));
            bars.add(bar(base + 1, 99, 99, 80, 80));
            for (int i = 2; i < 10; i++) {
                bars.add(bar(base + i, 80, 80, 80, 80));
            }
        }
        BacktestOutcome out = MartingaleBacktester.run(bars, p);

        assertThat(out.metrics().returnPct()).isGreaterThanOrEqualTo(-100);
        // 亏掉 100U 本金后不再有第 3 笔（每笔强平 -100）
        assertThat(out.trades()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("longSide() 不参与 JSON 序列化，params 里不会出现冗余的 long 字段")
    void isLongIsNotSerialized() {
        StrategyParams p = params(StrategyParams.LONG, 100, 10, 3, 2, 1.5, 1, 15, 24);
        String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(p);
        assertThat(json).doesNotContain("\"long\":").contains("\"side\":\"long\"");
    }
}
