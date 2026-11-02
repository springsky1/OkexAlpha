package com.okexalpha.service.strategy.live;

import java.util.List;
import java.util.Map;

/**
 * 实盘门禁报告：{@code liveAllowed=true} 才允许向 OKX 发真实/模拟订单。
 *
 * @param mode        当前交易模式（SHADOW / DEMO / REAL）
 * @param liveAllowed 门禁是否全部通过
 * @param blockers    阻断原因（每条对应一个 BLOCK 级检查）
 * @param checks      全部检查结论（含 PASS / WARN）
 * @param caps        当前限额快照（持仓数、每日名义等）
 */
public record GateReport(String mode, boolean liveAllowed, List<String> blockers, List<GateCheck> checks,
        Map<String, Object> caps) {
}
