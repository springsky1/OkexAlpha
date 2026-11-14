package com.okexalpha.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.okexalpha.domain.KlineCoverage.Segment;

/**
 * KlineCoverage：覆盖率 → 缺失段换算。
 *
 * <p>重点盯住「中间有缺口必须保守整段重拉」这条不变量——覆盖率优化宁可多拉，不可漏。</p>
 */
class KlineCoverageTest {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;
    private static final long S = 1_000_000_000_000L;
    private static final long E = S + 10 * HOUR;
    /** 窗口整体落在过去：endMs 距 now 已超过 refresh horizon，不触发尾部重拉。 */
    private static final long PAST = E + 10 * DAY;
    private static final long AT_END = E;

    private static List<Segment> plan(Long minTs, Long maxTs, long rows, long startMs, long endMs,
            long refreshMs, long nowMs) {
        return KlineCoverage.plan(minTs, maxTs, rows, startMs, endMs, HOUR, refreshMs, nowMs);
    }

    @Test
    void noDataReturnsFullRange() {
        assertThat(plan(null, null, 0L, S, E, DAY, PAST)).containsExactly(new Segment(S, E));
    }

    @Test
    void fullyCoveredWindowNeedsNothing() {
        // 库内 S..E 每根都有：左缘、右缘都不缺，窗口末端又早已确认
        assertThat(plan(S, E, 11L, S, E, DAY, PAST)).isEmpty();
    }

    @Test
    void denseMiddleWithBothEdgesMissingFetchesOnlyTheEdges() {
        // 库内 [S+2h, S+6h] 五根连续，左右各缺一段
        assertThat(plan(S + 2 * HOUR, S + 6 * HOUR, 5L, S, E, DAY, PAST))
                .containsExactly(new Segment(S, S + HOUR), new Segment(S + 7 * HOUR, E));
    }

    @Test
    void denseFromWindowStartFetchesOnlyTheRightEdge() {
        assertThat(plan(S, S + 6 * HOUR, 7L, S, E, DAY, PAST))
                .containsExactly(new Segment(S + 7 * HOUR, E));
    }

    @Test
    void denseUpToWindowEndFetchesOnlyTheLeftEdge() {
        assertThat(plan(S + 2 * HOUR, E, 9L, S, E, DAY, PAST))
                .containsExactly(new Segment(S, S + HOUR));
    }

    @Test
    void singleBarFetchesBothEdgesAroundIt() {
        assertThat(plan(S + 5 * HOUR, S + 5 * HOUR, 1L, S, E, DAY, PAST))
                .containsExactly(new Segment(S, S + 4 * HOUR), new Segment(S + 6 * HOUR, E));
    }

    @Test
    void interiorGapFallsBackToFullRange() {
        // 库内有 S..E 的 10 根，缺中间一根（interior=11）→ 整段重拉
        assertThat(plan(S, E, 10L, S, E, DAY, PAST)).containsExactly(new Segment(S, E));
    }

    @Test
    void interiorGapAtTheEdgeStillFallsBackToFullRange() {
        // 缺的是第二根：minTs..maxTs 仍跨满窗口，rows 少一根
        assertThat(plan(S, E, 9L, S, E, DAY, PAST)).containsExactly(new Segment(S, E));
    }

    @Test
    void offGridTimestampsFallBackToFullRange() {
        // maxTs 不在周期网格上（脏数据），span 不是 intervalMs 的整数倍 → 保守
        assertThat(plan(S, S + 5 * HOUR + 30 * 60_000L, 6L, S, E, DAY, PAST))
                .containsExactly(new Segment(S, E));
    }

    @Test
    void invalidArgumentsFallBackToFullRange() {
        // intervalMs <= 0（拿不到周期网格）→ 保守整段
        assertThat(KlineCoverage.plan(S, E, 11L, S, E, 0L, DAY, PAST)).containsExactly(new Segment(S, E));
        assertThat(plan(S, E, 11L, E, E, DAY, PAST)).containsExactly(new Segment(E, E));
        assertThat(plan(E, S, 11L, S, E, DAY, PAST)).containsExactly(new Segment(S, E));
    }

    @Test
    void youngWindowTailIsRefetchedForConfirmation() {
        // 窗口末端就是 now：最年轻的已落库 K 线可能还没 confirm，连它一起重拉
        assertThat(plan(S + 2 * HOUR, E, 9L, S, E, 2 * HOUR, AT_END))
                .containsExactly(new Segment(S, S + HOUR), new Segment(S + 8 * HOUR, E));
    }

    @Test
    void youngWindowShorterThanRefreshHorizonIsRefetchedWhole() {
        // 整个窗口都在 refresh horizon 之内 → 合并成整窗刷新
        assertThat(plan(S + 2 * HOUR, S + 6 * HOUR, 5L, S, E, DAY, AT_END))
                .containsExactly(new Segment(S, E));
        assertThat(plan(S + 2 * HOUR, S + 6 * HOUR, 5L, S, E, 30 * DAY, AT_END))
                .containsExactly(new Segment(S, E));
    }

    @Test
    void windowEndingInTheFutureIsTreatedAsYoung() {
        // endMs 在 now 之后：整窗都在 horizon 内
        assertThat(plan(S + 2 * HOUR, S + 6 * HOUR, 5L, S, E, DAY, S + 5 * HOUR))
                .containsExactly(new Segment(S, E));
    }

    @Test
    void zeroRefreshOnlyFillsGaps() {
        // refresh horizon = 0：只补缺口，已落库的一根都不重拉
        assertThat(plan(S + 2 * HOUR, E, 9L, S, E, 0L, AT_END))
                .containsExactly(new Segment(S, S + HOUR));
    }

    // ---- planGaps：洞由调用方用 LAG 查询定位后传入（holes=null 表示没查，[] 表示查了没有） ----

    private static List<Segment> planGaps(Long minTs, Long maxTs, long rows, List<Segment> holes,
            long startMs, long endMs, long refreshMs, long nowMs) {
        return KlineCoverage.planGaps(minTs, maxTs, rows, holes, startMs, endMs, HOUR, refreshMs, nowMs);
    }

    @Test
    void locatedInteriorGapFetchesOnlyTheHole() {
        // 窗口满跨 S..E，中间缺一根，洞已定位 → 只拉那一根，不再整段
        assertThat(planGaps(S, E, 10L, List.of(new Segment(S + 5 * HOUR, S + 5 * HOUR)), S, E, DAY, PAST))
                .containsExactly(new Segment(S + 5 * HOUR, S + 5 * HOUR));
    }

    @Test
    void locatedGapTogetherWithEdgesFetchesAllThree() {
        // 库内 [S+2h, S+6h] 五根里缺 S+4h：左缘 + 洞 + 右缘
        assertThat(planGaps(S + 2 * HOUR, S + 6 * HOUR, 4L, List.of(new Segment(S + 4 * HOUR, S + 4 * HOUR)),
                S, E, DAY, PAST))
                .containsExactly(new Segment(S, S + HOUR), new Segment(S + 4 * HOUR, S + 4 * HOUR),
                        new Segment(S + 7 * HOUR, E));
    }

    @Test
    void emptyHolesMeansFresherSnapshotFoundNothingSoEdgesOnly() {
        // rows<slots 但洞查询（更晚的快照）一个洞都没找到（并发已补上）→ 只补边缘，不整段
        assertThat(planGaps(S, E, 10L, List.of(), S, E, DAY, PAST)).isEmpty();
    }

    @Test
    void nullHolesKeepsConservativeFullRange() {
        // holes=null：没有洞信息，维持旧语义整段重拉
        assertThat(planGaps(S, E, 10L, null, S, E, DAY, PAST)).containsExactly(new Segment(S, E));
    }

    @Test
    void tooManyHolesFallBackToFullRange() {
        // 段数超上限（长期停牌币等病态情形）：整段比分段便宜，退回保守
        List<Segment> many = new ArrayList<>();
        for (int i = 0; i <= KlineCoverage.MAX_GAP_SEGMENTS; i++) {
            many.add(new Segment(S + i * 2 * HOUR, S + i * 2 * HOUR));
        }
        assertThat(planGaps(S, E, 10L, many, S, E, DAY, PAST)).containsExactly(new Segment(S, E));
        // 恰好等于上限仍然走分段
        List<Segment> atLimit = many.subList(0, KlineCoverage.MAX_GAP_SEGMENTS);
        assertThat(KlineCoverage.planGaps(S, E, 10L, atLimit, S, E, HOUR, DAY, PAST))
                .isNotEqualTo(List.of(new Segment(S, E)));
    }

    @Test
    void offGridTimestampsStayFullEvenWithLocatedHoles() {
        assertThat(planGaps(S, S + 5 * HOUR + 30 * 60_000L, 6L,
                List.of(new Segment(S + HOUR, S + HOUR)), S, E, DAY, PAST))
                .containsExactly(new Segment(S, E));
    }

    @Test
    void completeRowsIgnoreJunkHoles() {
        // rows==slots 本来就无洞：传入的脏 holes 必须被忽略，不得造出幻觉段
        assertThat(planGaps(S + 2 * HOUR, S + 6 * HOUR, 5L, List.of(new Segment(S + 4 * HOUR, S + 4 * HOUR)),
                S, E, DAY, PAST))
                .containsExactly(new Segment(S, S + HOUR), new Segment(S + 7 * HOUR, E));
    }

    @Test
    void youngTailRefreshCombinesWithLocatedHole() {
        // 中间有洞 + 窗口末端年轻：左缘 + 洞 + 尾部刷新各拉各的
        assertThat(planGaps(S + 2 * HOUR, E, 8L, List.of(new Segment(S + 5 * HOUR, S + 5 * HOUR)), S, E,
                2 * HOUR, AT_END))
                .containsExactly(new Segment(S, S + HOUR), new Segment(S + 5 * HOUR, S + 5 * HOUR),
                        new Segment(S + 8 * HOUR, E));
    }

    @Test
    void noDataWithHolesStillReturnsFullRange() {
        // rows=0 却带洞（自相矛盾的输入）：保守整段
        assertThat(planGaps(null, null, 0L, List.of(new Segment(S + HOUR, S + 2 * HOUR)), S, E, DAY, PAST))
                .containsExactly(new Segment(S, E));
    }

    @Test
    void holeAdjacentToLeftEdgeStaysSeparate() {
        // 左缘段 [S, S+1h]，洞在 S+3h（中间只隔着 S+2h 一根已存在）→ 相隔 2 个周期不合并
        assertThat(planGaps(S + 2 * HOUR, E, 8L, List.of(new Segment(S + 3 * HOUR, S + 3 * HOUR)), S, E, DAY,
                PAST))
                .containsExactly(new Segment(S, S + HOUR), new Segment(S + 3 * HOUR, S + 3 * HOUR));
    }

    @Test
    void normalizeDropsEmptySegmentsAndSortsByStart() {
        assertThat(KlineCoverage.normalize(
                List.of(new Segment(S + 5 * HOUR, S + 6 * HOUR), new Segment(S, S - HOUR),
                        new Segment(S, S + HOUR)),
                HOUR))
                .containsExactly(new Segment(S, S + HOUR), new Segment(S + 5 * HOUR, S + 6 * HOUR));
    }

    @Test
    void normalizeMergesOverlappingAndAdjacentSegments() {
        assertThat(KlineCoverage.normalize(
                List.of(new Segment(S, S + 2 * HOUR), new Segment(S + HOUR, S + 3 * HOUR)), HOUR))
                .containsExactly(new Segment(S, S + 3 * HOUR));
        // 只隔一个周期也合并（那一格要么已存在、要么顺带重拉，都不会漏）
        assertThat(KlineCoverage.normalize(
                List.of(new Segment(S, S + HOUR), new Segment(S + 2 * HOUR, S + 3 * HOUR)), HOUR))
                .containsExactly(new Segment(S, S + 3 * HOUR));
    }

    @Test
    void normalizeKeepsSegmentsSeparatedByMoreThanOneBar() {
        assertThat(KlineCoverage.normalize(
                List.of(new Segment(S, S + HOUR), new Segment(S + 3 * HOUR, S + 4 * HOUR)), HOUR))
                .containsExactly(new Segment(S, S + HOUR), new Segment(S + 3 * HOUR, S + 4 * HOUR));
    }
}
