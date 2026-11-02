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
}
