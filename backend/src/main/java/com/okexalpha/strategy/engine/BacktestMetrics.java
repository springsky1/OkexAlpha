package com.okexalpha.strategy.engine;

/**
 * 回测指标。
 *
 * @param returnPct       净收益率（相对单币保证金，%）
 * @param maxDrawdownPct  最大回撤（%）
 * @param calmar          returnPct / maxDrawdownPct
 * @param sharpe          按日收益算的年化夏普（无风险利率按 0）
 * @param winRate         胜率（%）
 * @param profitFactor    总盈利 / 总亏损（无亏损时为 null）
 * @param trades          成交笔数
 * @param liquidations    强平次数（>0 即高危）
 * @param avgHoldHours    平均持仓小时数
 * @param worstTradePct   最差单笔收益率（%，负数）
 * @param maxConsecutiveLosses 最长连续亏损笔数
 */
public record BacktestMetrics(
        double returnPct,
        double maxDrawdownPct,
        double calmar,
        double sharpe,
        double winRate,
        Double profitFactor,
        int trades,
        int liquidations,
        double avgHoldHours,
        double worstTradePct,
        int maxConsecutiveLosses) {

    public static BacktestMetrics empty() {
        return new BacktestMetrics(0d, 0d, 0d, 0d, 0d, null, 0, 0, 0d, 0d, 0);
    }
}
