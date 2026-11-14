package com.okexalpha.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 合约乘数换算口径测试（P0 单位修复的回归护栏）。
 *
 * <p>影子账本的 sz 是「合约张数」，而止损/盈亏公式吃的是「币数量」，两者之间必须显式乘
 * {@code ctVal}。BTC/ETH/DOGE 的 ctVal ≠ 1，历史上就是把张数当币数喂进公式，才导致
 * 止损距离被压缩 100/10/1000 倍。</p>
 */
class ShadowSizingTest {

    @Test
    @DisplayName("张数 → 币数量：BTC 0.66 张（ctVal=0.01）= 0.0066 BTC")
    void contractsToCoinQty() {
        assertThat(ShadowSizing.coinQty(0.66d, 0.01d)).isCloseTo(0.0066d, within(1e-12));
        assertThat(ShadowSizing.coinQty(1d, 0.1d)).isCloseTo(0.1d, within(1e-12));
        assertThat(ShadowSizing.coinQty(7d, 1000d)).isCloseTo(7000d, within(1e-9));
    }

    @Test
    @DisplayName("币数量 → 张数：0.0066 BTC / ctVal 0.01 = 0.66 张")
    void coinQtyToContracts() {
        assertThat(ShadowSizing.contracts(0.0066d, 0.01d)).isCloseTo(0.66d, within(1e-12));
        assertThat(ShadowSizing.contracts(7000d, 1000d)).isCloseTo(7d, within(1e-12));
    }

    @Test
    @DisplayName("BTC 止损距离按币数量计：25% 保证金 ≈ 5% 价格，而不是 0.05%")
    void btcStopDistanceUsesCoinQty() {
        // 500 名义 / 5x = 100 保证金，止损 25% 即亏 25 USD
        double entry = 73_621d;
        double coinQty = ShadowSizing.coinQty(ShadowSizing.contractsForNotional(500d, 0.01d, entry, 0.01d), 0.01d);
        double margin = 100d;
        double stop = ShadowMath.stopPrice(entry, true, 25d, margin, coinQty);
        assertThat((entry - stop) / entry).isCloseTo(0.05d, within(0.002d));
        // 止损价亏损额 ≈ 保证金的 25%
        assertThat((entry - stop) * coinQty).isCloseTo(25d, within(0.5d));
    }

    @Test
    @DisplayName("DOGE 加仓用张数落账：7000 币 / ctVal 1000 = 7 张，而不是 7000 张")
    void layerContractsAreRoundedToLot() {
        double lot = 1d;
        double contracts = ShadowSizing.contractsForNotional(500d, 1000d, 0.0717d, lot);
        assertThat(contracts).isEqualTo(7d);
        assertThat(ShadowSizing.coinQty(contracts, 1000d)).isCloseTo(7000d, within(1e-9));
    }

    @Test
    @DisplayName("张数按 lotSz 向上取整，至少一张")
    void contractsForNotionalRespectsLot() {
        // SOL ctVal=1：500/89.68 → 5.57 张，lot=0.1 → 5.6 张
        assertThat(ShadowSizing.contractsForNotional(500d, 1d, 89.68d, 0.1d)).isCloseTo(5.6d, within(1e-9));
        // 名义小于一张也至少 1 lot
        assertThat(ShadowSizing.contractsForNotional(10d, 758.25d, 73_621d, 0.01d)).isCloseTo(0.01d, within(1e-12));
    }

    @Test
    @DisplayName("ctVal 缺失时用快照 refPx 反推（758.25 / 75825 = 0.01）")
    void ctValueFallsBackToSnapshotRefPx() {
        assertThat(ShadowSizing.ctValue(null, 758.25d, 75_825d)).isCloseTo(0.01d, within(1e-12));
        assertThat(ShadowSizing.ctValue(0.01d, 758.25d, 75_825d)).isCloseTo(0.01d, within(1e-12));
        assertThat(ShadowSizing.ctValue(null, null, 100d)).isZero();
    }

    @Test
    @DisplayName("不变量：notional 必须约等于 张数 × ctVal × 价格；历史污染行应被识别")
    void notionalConsistencyDetectsLegacyPollution() {
        // 干净的 BTC 仓：0.66 张 × 0.01 × 73621 ≈ 485.9
        assertThat(ShadowSizing.notionalConsistent(485.9d, 0.66d, 0.01d, 73_621d)).isTrue();
        // 历史 DOGE 污染行：sz=29014 张 × 1000 × 0.0717 = 2,080,000，名义却记 2638
        assertThat(ShadowSizing.notionalConsistent(2638d, 29_014d, 1000d, 0.0717d)).isFalse();
        // 无乘数信息时不做判断
        assertThat(ShadowSizing.notionalConsistent(2638d, 29_014d, 0d, 0.0717d)).isTrue();
    }
}
