package com.okexalpha.web.dto;

/**
 * 实盘下单请求（阶段 C / P4）。
 *
 * <p>只有 {@link com.okexalpha.service.strategy.live.TradingGate} 全部通过才会真正提交；
 * REAL 模式还要求 {@code confirm="CONFIRM-REAL-TRADING"} 作为二次确认。</p>
 *
 * @param instId   合约，如 BTC-USDT-SWAP
 * @param side     buy / sell
 * @param sz       张数（按合约面值，不是 USD）
 * @param ordType  market / limit（默认 market）
 * @param px       限价单价格（market 忽略）
 * @param tdMode   isolated / cross（默认 isolated）
 * @param reduceOnly 是否只减仓
 * @param confirm  实盘二次确认口令
 */
public record LiveOrderRequest(String instId, String side, Double sz, String ordType, Double px, String tdMode,
        Boolean reduceOnly, String confirm) {
}
