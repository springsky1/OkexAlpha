package com.okexalpha.web.dto;

/**
 * 影子「复制开仓」请求：把一笔真实订单复制进影子账本（<b>不向 OKX 发单</b>）。
 *
 * <p>除 instId / side 外都可空：sz 为空取平台最小张数（min_sz），px 为空取最新价，
 * leverage 为空取 5（或策略/params 里的值），strategyId 为空表示手工持仓（不归属任何策略），
 * params 只覆盖需要改动的参数，未给的字段取策略参数或 Trading 默认。</p>
 */
public record ShadowOpenRequest(
        String instId,
        String side,
        Double sz,
        Double px,
        Integer leverage,
        Long strategyId,
        Params params) {

    /** 参数覆盖（全部可空）：只给需要与策略/默认值不同的字段。 */
    public record Params(
            Double initialMarginUsd,
            Integer leverage,
            Integer maxLayers,
            Double layerStepPct,
            Double layerScale,
            Double takeProfitPct,
            Double stopLossPct,
            Integer maxHoldHours,
            Double feeTaker,
            Double fundingPer8hPct,
            Double slippageBps) {

        /** 全部缺省（等价于不传 params）。 */
        public static Params empty() {
            return new Params(null, null, null, null, null, null, null, null, null, null, null);
        }
    }
}
