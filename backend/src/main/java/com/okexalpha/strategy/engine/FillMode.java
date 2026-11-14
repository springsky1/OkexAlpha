package com.okexalpha.strategy.engine;

/**
 * 成交时点口径（方案 §2.3）。
 *
 * <ul>
 *   <li>{@link #NEXT_OPEN}：信号 bar 之后一根 bar 的开盘价成交（默认，实盘可执行性最好）；</li>
 *   <li>{@link #SIGNAL_CLOSE}：信号 bar 收盘价成交（理想化口径，只作对照）。</li>
 * </ul>
 */
public enum FillMode {
    NEXT_OPEN,
    SIGNAL_CLOSE
}
