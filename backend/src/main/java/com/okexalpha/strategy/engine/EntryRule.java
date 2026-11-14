package com.okexalpha.strategy.engine;

/**
 * 首仓入场规则（方案 §2.3 / F1）。
 *
 * <p>马丁的"逆势加仓"不决定生死，<b>入场</b>才决定；always-in 基准必须显式标注，
 * 不能把"每天都开仓"的结果当成可执行策略。</p>
 */
public enum EntryRule {

    /** 平仓后立即准备再开（回测窗口首根即可入场）；与旧实现一致，作基准线。 */
    ALWAYS_IN,

    /** 收盘突破过去 N 根高点（long）/ 跌破低点（short）。 */
    BREAKOUT,

    /** EMA(12) 上穿 EMA(48) 且价格 &gt; EMA(200)（short 反之）。 */
    EMA_CROSS,

    /** 不开新仓（只管理已有持仓，用于状态续跑与"仅平仓"场景）。 */
    NONE
}
