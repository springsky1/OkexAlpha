package com.okexalpha.strategy.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 账户权益字段的口径：缺省 / 非法时归一成首仓预算，保证"不加仓"是默认行为。 */
class StrategyParamsTest {

    @Test
    @DisplayName("accountCapitalUsd 缺省（0）时归一成 initialMarginUsd：只够开首仓")
    void accountCapitalDefaultsToFirstLayerBudget() {
        StrategyParams p = new StrategyParams(StrategyParams.LONG, 100d, 5, 8, 2.5d, 1.5d, 2d, 25d, 48,
                0.0005d, 0.0002d, 0.01d, 2d, 0d);
        assertThat(p.accountCapitalUsd()).isEqualTo(100d);
    }

    @Test
    @DisplayName("accountCapitalUsd 正常值原样保留")
    void accountCapitalKept() {
        StrategyParams p = new StrategyParams(StrategyParams.LONG, 100d, 5, 8, 2.5d, 1.5d, 2d, 25d, 48,
                0.0005d, 0.0002d, 0.01d, 2d, 3216.4d);
        assertThat(p.accountCapitalUsd()).isEqualTo(3216.4d);
    }

    @Test
    @DisplayName("withScale 透传账户权益（邻域扰动只改连续参数）")
    void withScaleKeepsAccountCapital() {
        StrategyParams p = new StrategyParams(StrategyParams.LONG, 100d, 5, 8, 2.5d, 1.5d, 2d, 25d, 48,
                0.0005d, 0.0002d, 0.01d, 2d, 5000d);
        assertThat(p.withScale(1.2d).accountCapitalUsd()).isEqualTo(5000d);
    }

    @Test
    @DisplayName("withScale 透传 maker 费率（挂单费率不是可调参数，不能被邻域扰动改掉）")
    void withScaleKeepsMakerFee() {
        StrategyParams p = new StrategyParams(StrategyParams.LONG, 100d, 5, 8, 2.5d, 1.5d, 2d, 25d, 48,
                0.0005d, 0.0002d, 0.01d, 2d, 5000d);
        assertThat(p.withScale(1.2d).feeMaker()).isEqualTo(0.0002d);
    }

    @Test
    @DisplayName("负费率一律拒绝：taker/maker/资金费/滑点任一为负即抛")
    void rejectsNegativeCosts() {
        assertThatThrownBy(() -> new StrategyParams(StrategyParams.LONG, 100d, 5, 8, 2.5d, 1.5d, 2d, 25d, 48,
                -0.0001d, 0.0002d, 0.01d, 2d, 5000d)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StrategyParams(StrategyParams.LONG, 100d, 5, 8, 2.5d, 1.5d, 2d, 25d, 48,
                0.0005d, -0.0001d, 0.01d, 2d, 5000d)).isInstanceOf(IllegalArgumentException.class);
    }
}
