package com.okexalpha.web.dto;

/**
 * 实盘杠杆调整请求（阶段 C / P4）。
 *
 * <p>下单接口不携带杠杆，OKX 的杠杆是按合约单独设置的（{@code /api/v5/account/set-leverage}）。
 * 马丁格尔的回测假设依赖「止损距离 = stopLossPct% ÷ leverage」，因此开仓后必须把每个合约的
 * 杠杆设回回测值，否则实际强平距离会与报告不一致。</p>
 *
 * @param instId  合约，如 BTC-USDT-SWAP
 * @param lever   目标杠杆（"3"、"5" 这类整数字符串，OKX 的 lever 是字符串）
 * @param mgnMode isolated / cross（默认 isolated）
 * @param posSide 持仓方向 long / short（双向持仓模式必填，否则 OKX 回 51000 Parameter posSide error）
 * @param confirm 实盘二次确认口令
 */
public record LiveLeverageRequest(String instId, String lever, String mgnMode, String posSide, String confirm) {
}
