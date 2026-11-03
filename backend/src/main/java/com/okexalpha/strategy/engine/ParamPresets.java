package com.okexalpha.strategy.engine;

import java.util.List;

/**
 * 回测参数范围预设：给前端 chips 与后端校验共用的一份目录。
 *
 * <p>每个预设只描述 6 个寻优维度的范围，其余参数（保证金、持仓时长、费率、滑点）仍由提交表单决定。
 * 组合数由维度精确计算得出（{@link ParamRanges#combos()}），可能超过
 * {@code strategy.backtest.max-combos}：服务端会按 maxCombos 截断并在 requestJson 里标记
 * {@code rangeTruncated=true}，不会静默少算。</p>
 */
public record ParamPresets(String key, String label, String note, ParamRanges ranges, int combos) {

    /** 保守：小杠杆、少层、窄止盈止损，组合数最少，适合先跑一遍看稳定性。 */
    public static ParamPresets conservative() {
        ParamRanges ranges = new ParamRanges(
                new ParamRanges.Range(3d, 5d, 1d),
                new ParamRanges.Range(3d, 5d, 1d),
                new ParamRanges.Range(1.5d, 2.5d, 0.5d),
                new ParamRanges.Range(1.0d, 1.0d, 0.5d),
                new ParamRanges.Range(1.0d, 2.0d, 0.5d),
                new ParamRanges.Range(15d, 25d, 5d),
                List.of());
        return of("conservative", "保守", "小杠杆与少层数、窄止盈止损，优先看策略稳不稳，组合数最少。", ranges);
    }

    /** 均衡：杠杆 3-10、加仓间隔 1.5%-5%，覆盖面明显更宽，组合数会超过默认上限。 */
    public static ParamPresets balanced() {
        ParamRanges ranges = new ParamRanges(
                new ParamRanges.Range(3d, 10d, 1d),
                new ParamRanges.Range(3d, 5d, 1d),
                new ParamRanges.Range(1.5d, 5.0d, 0.5d),
                new ParamRanges.Range(1.0d, 1.5d, 0.5d),
                new ParamRanges.Range(1.0d, 3.0d, 0.5d),
                new ParamRanges.Range(15d, 30d, 5d),
                List.of());
        return of("balanced", "均衡", "杠杆与加仓间隔铺开，兼顾稳定性与收益弹性；组合数通常超过默认上限，会按上限抽样。", ranges);
    }

    /** 激进：高杠杆、深层加仓、宽止盈，只在能承受大幅回撤时使用。 */
    public static ParamPresets aggressive() {
        ParamRanges ranges = new ParamRanges(
                new ParamRanges.Range(5d, 10d, 1d),
                new ParamRanges.Range(5d, 8d, 1d),
                new ParamRanges.Range(2.5d, 4.0d, 0.5d),
                new ParamRanges.Range(1.5d, 1.5d, 0.5d),
                new ParamRanges.Range(2.0d, 4.0d, 0.5d),
                new ParamRanges.Range(20d, 30d, 5d),
                List.of());
        return of("aggressive", "激进", "高杠杆、深层加仓与宽止盈，追求收益弹性，回撤风险最大。", ranges);
    }

    /** 目录顺序即前端 chips 顺序：保守 → 均衡 → 激进。 */
    public static List<ParamPresets> catalog() {
        return List.of(conservative(), balanced(), aggressive());
    }

    /** 按 key 查预设，未知 key 返回 null（请求里不认识的 key 由调用方决定忽略还是报错）。 */
    public static ParamPresets byKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        for (ParamPresets preset : catalog()) {
            if (preset.key().equals(key.trim())) {
                return preset;
            }
        }
        return null;
    }

    private static ParamPresets of(String key, String label, String note, ParamRanges ranges) {
        // 预设是代码里的常量，构造时先自校验，避免上线后才发现区间写反
        ranges.validate();
        return new ParamPresets(key, label, note, ranges, ranges.combos());
    }
}
