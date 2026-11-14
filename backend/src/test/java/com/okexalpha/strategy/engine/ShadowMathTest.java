package com.okexalpha.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 影子引擎纯计算口径测试：必须与 MartingaleBacktester 的强平/止损/止盈/加仓逻辑一致。 */
class ShadowMathTest {

    @Test
    @DisplayName("多单强平价 = 均价 × (1 − 1/杠杆 + 0.005)")
    void longLiquidationPrice() {
        assertThat(ShadowMath.liqPrice(100d, true, 5d)).isCloseTo(100d * (1d - 0.2d + 0.005d), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("空单强平价 = 均价 × (1 + 1/杠杆 − 0.005)")
    void shortLiquidationPrice() {
        assertThat(ShadowMath.liqPrice(100d, false, 10d)).isCloseTo(100d * (1d + 0.1d - 0.005d), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("止损价按保证金百分比折算：距离 = sl% × 保证金 / 张数")
    void stopPriceUsesMarginPercentage() {
        double stop = ShadowMath.stopPrice(100d, true, 25d, 20d, 2d);
        assertThat(stop).isCloseTo(100d - 0.25d * 20d / 2d, org.assertj.core.data.Offset.offset(1e-9));
        double shortStop = ShadowMath.stopPrice(100d, false, 25d, 20d, 2d);
        assertThat(shortStop).isCloseTo(100d + 0.25d * 20d / 2d, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("张数为 0 时止损价退化为均价，避免除零")
    void stopPriceWithZeroQty() {
        assertThat(ShadowMath.stopPrice(100d, true, 25d, 20d, 0d)).isEqualTo(100d);
    }

    @Test
    @DisplayName("止盈价多空对称")
    void takeProfitPrice() {
        assertThat(ShadowMath.takeProfitPrice(100d, true, 2d)).isEqualTo(102d);
        assertThat(ShadowMath.takeProfitPrice(100d, false, 2d)).isEqualTo(98d);
    }

    @Test
    @DisplayName("未实现盈亏含方向")
    void unrealizedRespectsSide() {
        assertThat(ShadowMath.unrealized(100d, 110d, 2d, true)).isEqualTo(20d);
        assertThat(ShadowMath.unrealized(100d, 110d, 2d, false)).isEqualTo(-20d);
    }

    @Test
    @DisplayName("加仓均价按名义金额加权")
    void mergeAverageWeightsByNotional() {
        assertThat(ShadowMath.mergeAverage(100d, 100d, 300d, 90d)).isEqualTo((100d * 100d + 300d * 90d) / 400d);
        assertThat(ShadowMath.mergeAverage(0d, 100d, 50d, 80d)).isEqualTo(80d);
    }

    @Test
    @DisplayName("第 n 层名义 = 基础名义 × scale^(n-1)，且可反推基础名义")
    void layerNotionalAndBase() {
        assertThat(ShadowMath.layerNotional(100d, 1.5d, 3)).isEqualTo(225d);
        double notional = 100d + 150d + 225d;
        assertThat(ShadowMath.baseNotional(notional, 1.5d, 3)).isCloseTo(100d, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("加仓闸门：已用保证金 + 新层保证金 ≤ (权益 + 已实现盈亏) × 95%")
    void allowsLayerRespectsAccountHeadroom() {
        // 权益 1000、已用 600：新层 300 → 900 ≤ 950 允许；新层 400 → 1000 > 950 拒绝
        assertThat(ShadowMath.allowsLayer(1000d, 0d, 600d, 300d)).isTrue();
        assertThat(ShadowMath.allowsLayer(1000d, 0d, 600d, 400d)).isFalse();
        // 已实现盈利抬高可用额度（上限 1140），已实现亏损压缩额度（上限 760）
        assertThat(ShadowMath.allowsLayer(1000d, 200d, 600d, 400d)).isTrue();
        assertThat(ShadowMath.allowsLayer(1000d, -200d, 600d, 300d)).isFalse();
    }

    @Test
    @DisplayName("权益未知（≤0）或新层保证金非法时一律不许加仓：fail-closed")
    void allowsLayerFailsClosed() {
        assertThat(ShadowMath.allowsLayer(0d, 0d, 0d, 1d)).isFalse();
        assertThat(ShadowMath.allowsLayer(-5d, 0d, 0d, 1d)).isFalse();
        assertThat(ShadowMath.allowsLayer(1000d, 0d, 0d, 0d)).isFalse();
        assertThat(ShadowMath.allowsLayer(Double.NaN, 0d, 0d, 1d)).isFalse();
    }

    @Test
    @DisplayName("滑点随振幅缩放：参考振幅 0.2% → 1×，窄幅封底 0.5×，宽幅封顶 5×")
    void slippageScalesWithRange() {
        // 振幅 = (high − low) / refPx；基准 10bps
        assertThat(ShadowMath.slippageBps(10d, 100.1d, 99.9d, 100d)).isCloseTo(10d, org.assertj.core.data.Offset.offset(1e-9));   // 0.2% → 1×
        assertThat(ShadowMath.slippageBps(10d, 100.4d, 99.6d, 100d)).isCloseTo(40d, org.assertj.core.data.Offset.offset(1e-9));   // 0.8% → 4×
        assertThat(ShadowMath.slippageBps(10d, 100.01d, 99.99d, 100d)).isCloseTo(5d, org.assertj.core.data.Offset.offset(1e-9));  // 0.02% → 0.5×
        assertThat(ShadowMath.slippageBps(10d, 102.5d, 97.5d, 100d)).isCloseTo(50d, org.assertj.core.data.Offset.offset(1e-9));   // 5% → 封顶 5×
    }

    @Test
    @DisplayName("滑点缩放对退化输入保持原值：base ≤ 0 归零，价格非法回退基准")
    void slippageHandlesDegenerateInput() {
        assertThat(ShadowMath.slippageBps(0d, 102d, 98d, 100d)).isZero();
        assertThat(ShadowMath.slippageBps(-1d, 102d, 98d, 100d)).isZero();
        assertThat(ShadowMath.slippageBps(10d, 98d, 102d, 100d)).isEqualTo(10d);   // high < low
        assertThat(ShadowMath.slippageBps(10d, 100.2d, 99.8d, 0d)).isEqualTo(10d); // refPx 非法
    }
}
