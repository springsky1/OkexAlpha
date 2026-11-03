package com.okexalpha.web.dto;

import com.okexalpha.strategy.engine.ParamRanges;

import java.util.List;

/**
 * 回测提交请求（全部可选，缺省走服务端默认）。
 *
 * @param instIds          指定币种；缺省取库内 live SWAP 前 coinLimit 个
 * @param bar              K 线周期（默认 1m）
 * @param startMs          回测起点毫秒；缺省 = endMs - 90 天
 * @param endMs            回测终点毫秒；缺省 = 现在
 * @param coinLimit        未指定 instIds 时取多少个币（默认 12，上限 50）
 * @param combos           抽样参数组合数（默认 okx.strategy.backtest.default-sample-size）
 * @param topN             精调阶段保留的 Top-N 组合（默认 5，上限 20）
 * @param marginUsd        单币保证金（缺省 0 = 用 okx.strategy.trading.capital-per-coin-usd，再缺省 100）
 * @param maxHoldHours     最长持仓小时（默认 48）
 * @param fundingPer8hPct  每 8 小时资金费率 %（默认 0.01）
 * @param seed             抽样随机种子（默认 20261101）
 * @param label            批次备注
 * @param strategyId       关联策略 id（可空）
 * @param ranges           自定义参数范围（可空 = 用内置 648 组网格）；按范围展开后再抽 combos 组
 */
public record BacktestRequest(
        List<String> instIds,
        String bar,
        Long startMs,
        Long endMs,
        Integer coinLimit,
        Integer combos,
        Integer topN,
        Double marginUsd,
        Integer maxHoldHours,
        Double fundingPer8hPct,
        Long seed,
        String label,
        Long strategyId,
        ParamRanges ranges) {
}
