package com.okexalpha.service.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 推荐闸门：只有「跑赢同期买入持有 + IS/OOS 回撤都可接受」的组合才配进影子。
 *
 * <p>线上事故背景：result 70 在样本内 BTC 从 64088 涨到 85681（+33%~+62%）、样本外全线下跌
 * 11%~25% 的行情里，报出 IS +440%、OOS +535%，回撤 40.4%，却因为旧规则只卡 OOS 回撤与强平，
 * 被排到 rank 1 当成候选策略推上影子。</p>
 */
class BacktestVerdictTest {

    @Test
    @DisplayName("全条件满足 → CANDIDATE")
    void allConditionsPass() {
        assertThat(BacktestService.verdictFor(214, 120d, 30d, 60, 12d, 0, 20d, 8d)).isEqualTo("CANDIDATE");
    }

    @Test
    @DisplayName("样本内回撤 40.4% → REJECT（旧规则只看 OOS 回撤）")
    void inSampleDrawdownGateRejects() {
        assertThat(BacktestService.verdictFor(214, 440d, 535d, 60, 12d, 0, 40.4d, 800d)).isEqualTo("REJECT");
    }

    @Test
    @DisplayName("OOS 超额收益 <= 0（只是吃 beta）→ REJECT")
    void noExcessOverBenchmarkRejects() {
        assertThat(BacktestService.verdictFor(214, 120d, 30d, 60, 12d, 0, 20d, 0d)).isEqualTo("REJECT");
        assertThat(BacktestService.verdictFor(214, 120d, 30d, 60, 12d, 0, 20d, -15d)).isEqualTo("REJECT");
    }

    @Test
    @DisplayName("成交太少 → INSUFFICIENT（先于其它判断）")
    void tooFewTradesIsInsufficient() {
        assertThat(BacktestService.verdictFor(9, 120d, 30d, 60, 12d, 0, 20d, 8d)).isEqualTo("INSUFFICIENT");
    }

    @Test
    @DisplayName("组合峰值保证金超出账户权益 95% → REJECT（单币看着都够、加起来开不出来）")
    void portfolioMarginGateRejects() {
        // 权益 1000U → 上限 950U；组合峰值 3216U（SOL 8 层）远超
        assertThat(BacktestService.verdictFor(214, 120d, 30d, 60, 12d, 0, 20d, 8d, 3216d, 1000d))
                .isEqualTo("REJECT");
        // 峰值 900U 仍在 950U 以内 → 放行
        assertThat(BacktestService.verdictFor(214, 120d, 30d, 60, 12d, 0, 20d, 8d, 900d, 1000d))
                .isEqualTo("CANDIDATE");
        // 账户权益未知（0）→ 不做组合判定，保持旧行为
        assertThat(BacktestService.verdictFor(214, 120d, 30d, 60, 12d, 0, 20d, 8d, 3216d, 0d))
                .isEqualTo("CANDIDATE");
    }

    @Test
    @DisplayName("OOS 成交不足 / 有强平 / OOS 回撤过大 → REJECT")
    void otherGatesStillApply() {
        assertThat(BacktestService.verdictFor(214, 120d, 30d, 29, 12d, 0, 20d, 8d)).isEqualTo("REJECT");
        assertThat(BacktestService.verdictFor(214, 120d, 30d, 60, 12d, 1, 20d, 8d)).isEqualTo("REJECT");
        assertThat(BacktestService.verdictFor(214, 120d, 30d, 60, 25d, 0, 20d, 8d)).isEqualTo("REJECT");
    }
}
