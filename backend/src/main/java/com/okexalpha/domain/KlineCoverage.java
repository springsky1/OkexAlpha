package com.okexalpha.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 补拉前的库内覆盖率换算：把一次窗口化查询得到的 (minTs, maxTs, rows) 变成「还缺哪些区间」。
 *
 * <p>纯函数、不碰 IO，查库由调用方负责（见 {@code KlineRepository#coverage}）。</p>
 *
 * <p><b>为什么不变量保证不会漏数据</b>：覆盖率查询带时间窗口，所以已存在的行 ts 全部落在
 * {@code [startMs, endMs]} 内；主键 (kline_type, inst_id, bar, ts_ms) 又保证同一品种同一周期内 ts 互异。
 * 于是已存在的行构成 {@code [minTs, maxTs]} 内网格槽位集合的子集，{@code rows <= interior} 恒成立，
 * 且 {@code rows == interior} 当且仅当 {@code [minTs, maxTs]} 内每个槽位都有行。因此只要中间缺一根，
 * 必然 {@code rows < interior}，本类退化为「整段重拉」——与优化前完全一致，不可能跳过缺口。</p>
 *
 * <p>上游若混进非网格 ts（脏数据）：{@code maxTs - minTs} 不再是 intervalMs 的整数倍，同样走保守分支。
 * 唯一能骗过判定的组合是「中间恰好多一根脏数据、同时又缺一根网格数据」，而 OKX 的 K 线接口返回的
 * ts 都严格落在周期网格上，实际不会出现。</p>
 */
public final class KlineCoverage {

    private KlineCoverage() {
    }

    /** 一段还缺的 K 线时间范围（闭区间）。 */
    public record Segment(long fromMs, long toMs) {
    }

    /**
     * 算出还需要补拉哪些区间（按 fromMs 升序、彼此不相邻）。
     *
     * @param minTs      窗口内已存在 K 线的最早 ts（没数据时为 null）
     * @param maxTs      窗口内已存在 K 线的最晚 ts（没数据时为 null）
     * @param rows       窗口内已存在的行数（0 表示没数据）
     * @param startMs    请求窗口起点
     * @param endMs      请求窗口终点
     * @param intervalMs K 线周期（{@link Bar#intervalMs()}）
     * @param refreshMs  尾部强制重拉 horizon：窗口末端距 now 不超过该值时，连同最年轻的已落库 K 线一起重拉
     *                   （OKX 的 K 线要过一会儿才 confirm，值会变）；0 = 只补缺口
     * @param nowMs      当前时间（由调用方传入，保证可测）
     * @return 缺失段列表；空列表表示库内已全覆盖，一次上游请求都不需要
     */
    public static List<Segment> plan(Long minTs, Long maxTs, long rows, long startMs, long endMs,
            long intervalMs, long refreshMs, long nowMs) {
        if (rows <= 0L || minTs == null || maxTs == null || maxTs < minTs || intervalMs <= 0L
                || startMs >= endMs) {
            return List.of(new Segment(startMs, endMs));
        }
        long span = maxTs - minTs;
        if (span % intervalMs != 0L || rows < span / intervalMs + 1L) {
            // 中间有缺口（或 ts 不在周期网格上）→ 保守整段重拉
            return List.of(new Segment(startMs, endMs));
        }
        List<Segment> segments = new ArrayList<>(2);
        if (minTs - intervalMs >= startMs) {
            // 左缘：库内最早那一根之前全缺
            segments.add(new Segment(startMs, minTs - intervalMs));
        }
        long rightFrom = maxTs + intervalMs;
        if (nowMs - endMs < refreshMs) {
            // 窗口末端还年轻：把可能还没 confirm 的已落库 K 线一起重拉
            long refreshFrom = Math.max(startMs, nowMs - refreshMs);
            if (refreshFrom <= maxTs) {
                rightFrom = Math.min(rightFrom, refreshFrom);
            }
        }
        if (rightFrom <= endMs) {
            // 右缘：库内最晚那一根之后全缺
            segments.add(new Segment(rightFrom, endMs));
        }
        return normalize(segments, intervalMs);
    }

    /** 丢空段、按起点排序、重叠或仅隔一个周期的段合并（合并只会多拉几根，不会漏）。 */
    static List<Segment> normalize(List<Segment> segments, long intervalMs) {
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.removeIf(segment -> segment.fromMs() > segment.toMs());
        sorted.sort(Comparator.comparingLong(Segment::fromMs).thenComparingLong(Segment::toMs));
        List<Segment> merged = new ArrayList<>(sorted.size());
        for (Segment segment : sorted) {
            if (!merged.isEmpty()) {
                Segment last = merged.get(merged.size() - 1);
                if (segment.fromMs() <= last.toMs() + intervalMs) {
                    if (segment.toMs() > last.toMs()) {
                        merged.set(merged.size() - 1, new Segment(last.fromMs(), segment.toMs()));
                    }
                    continue;
                }
            }
            merged.add(segment);
        }
        return List.copyOf(merged);
    }
}
