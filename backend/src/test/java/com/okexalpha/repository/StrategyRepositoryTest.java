package com.okexalpha.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

/** 策略注册表 SQL 拼装：运行中谓词必须与影子引擎同口径。 */
class StrategyRepositoryTest {

    private static final String PREDICATE = "(mode = 'SHADOW' AND status IN ('CANDIDATE', 'PROMOTED'))";

    private JdbcTemplate jdbc;
    private StrategyRepository repository;

    private final List<String> sqls = new ArrayList<>();
    private final List<Object[]> argumentArrays = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repository = new StrategyRepository(jdbc);
    }

    @Test
    @DisplayName("运行中谓词与影子引擎一致")
    void runningPredicateMatchesShadowEngine() {
        assertThat(StrategyRepository.RUNNING_PREDICATE).isEqualTo(PREDICATE);
        assertThat(StrategyRepository.RUNNING_PREDICATE).contains("'SHADOW'", "'CANDIDATE'", "'PROMOTED'");
    }

    @Test
    @DisplayName("state=running 时追加运行中谓词，按 id 倒序分页")
    void runningStateAddsPredicate() {
        stubList(List.of());

        repository.list("running", null, null, 10, 20);

        assertThat(sqls).hasSize(1);
        assertThat(sqls.get(0))
                .contains("FROM okx_strategy")
                .contains("AND " + PREDICATE)
                .contains("ORDER BY id DESC LIMIT ? OFFSET ?");
        assertThat(argumentArray(0)).containsExactly(10, 20);
    }

    @Test
    @DisplayName("state=history 时取 NOT 运行中谓词")
    void historyStateNegatesPredicate() {
        stubList(List.of());

        repository.list("history", null, null, 10, 0);

        assertThat(sqls.get(0)).contains("AND NOT " + PREDICATE);
    }

    @Test
    @DisplayName("state=all 与未知 state 都不加状态过滤")
    void allAndUnknownStateAddNoStatePredicate() {
        stubList(List.of());

        repository.list("all", null, null, 10, 0);
        repository.list("weird", null, null, 10, 0);
        repository.list(null, null, null, 10, 0);

        assertThat(sqls).hasSize(3);
        for (String sql : sqls) {
            assertThat(sql).doesNotContain("mode = 'SHADOW'").contains("WHERE 1 = 1");
        }
    }

    @Test
    @DisplayName("status/mode 过滤值统一大写并作为参数绑定")
    void statusAndModeAreUppercased() {
        stubList(List.of());

        repository.list("running", "promoted", "shadow", 10, 5);

        assertThat(sqls.get(0))
                .contains("AND status = ?")
                .contains("AND mode = ?")
                .contains("AND " + PREDICATE);
        assertThat(argumentArray(0)).containsExactly("PROMOTED", "SHADOW", 10, 5);
    }

    @Test
    @DisplayName("空白 status/mode 视为不过滤")
    void blankFiltersAreIgnored() {
        stubList(List.of());

        repository.list("all", "  ", "   ", 1, 0);

        assertThat(sqls.get(0)).doesNotContain("status = ?").doesNotContain("mode = ?");
        assertThat(argumentArray(0)).containsExactly(1, 0);
    }

    @Test
    @DisplayName("count 复用同一套过滤条件，只换 SELECT 头")
    void countReusesFilters() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenAnswer(inv -> {
            sqls.add(inv.getArgument(0));
            argumentArrays.add(flatten(inv.getRawArguments()));
            return 42L;
        });

        long total = repository.count("running", "promoted", null);

        assertThat(total).isEqualTo(42L);
        assertThat(sqls.get(0)).startsWith("SELECT count(*) FROM okx_strategy")
                .contains("AND status = ?")
                .contains("AND " + PREDICATE);
        assertThat(argumentArray(0)).containsExactly("PROMOTED");
    }

    @Test
    @DisplayName("count 遇到 null 结果返回 0")
    void countReturnsZeroWhenNull() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);

        assertThat(repository.count(null, null, null)).isZero();
    }

    @Test
    @DisplayName("find 无行时返回 Optional.empty")
    void findReturnsEmptyWhenNoRows() {
        stubList(List.of());

        assertThat(repository.find(7L)).isEmpty();
        assertThat(sqls.get(0)).contains("WHERE id = ?");
        assertThat(argumentArray(0)).containsExactly(7L);
    }

    @Test
    @DisplayName("find 命中时映射成 StrategyRow")
    void findMapsRow() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        StrategyRepository.StrategyRow row = new StrategyRepository.StrategyRow(7L, "martingale-m5", "PROMOTED", "SHADOW",
                "{\"layers\":8}", "[\"BTC\"]", 100d, 11L, "note", createdAt, null);
        stubList(List.of(row));

        Optional<StrategyRepository.StrategyRow> found = repository.find(7L);

        assertThat(found).contains(row);
        assertThat(found.get().paramsJson()).isEqualTo("{\"layers\":8}");
    }

    @Test
    @DisplayName("stats 传空集合时直接返回空 Map，不查库")
    void statsWithEmptyIdsSkipsDatabase() {
        assertThat(repository.stats(List.of())).isEmpty();
        assertThat(repository.stats(null)).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("stats 合并持仓/订单聚合，缺失策略按 EMPTY 处理")
    void statsMergeAggregatesAndDefaultMissingStrategies() throws Exception {
        ResultSet positionRs = mock(ResultSet.class);
        when(positionRs.getLong("strategy_id")).thenReturn(7L);
        when(positionRs.getInt("open_cnt")).thenReturn(2);
        when(positionRs.getDouble("open_notional")).thenReturn(5000d);
        when(positionRs.getDouble("upl")).thenReturn(12.5d);
        when(positionRs.getInt("closed_cnt")).thenReturn(3);
        ResultSet orderRs = mock(ResultSet.class);
        OffsetDateTime lastOrderAt = OffsetDateTime.parse("2026-02-02T03:04:05Z");
        when(orderRs.getLong("strategy_id")).thenReturn(7L);
        when(orderRs.getInt("order_cnt")).thenReturn(9);
        when(orderRs.getObject("last_decided_at", OffsetDateTime.class)).thenReturn(lastOrderAt);

        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            sqls.add(sql);
            argumentArrays.add(flatten(inv.getRawArguments()));
            RowCallbackHandler handler = inv.getArgument(1);
            handler.processRow(sql.contains("okx_shadow_position") ? positionRs : orderRs);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        Map<Long, StrategyRepository.StrategyStats> stats = repository.stats(List.of(7L, 8L));

        assertThat(stats).containsOnlyKeys(7L, 8L);
        StrategyRepository.StrategyStats seven = stats.get(7L);
        assertThat(seven.openPositions()).isEqualTo(2);
        assertThat(seven.openNotionalUsd()).isEqualTo(5000d);
        assertThat(seven.unrealizedPnlUsd()).isEqualTo(12.5d);
        assertThat(seven.closedPositions()).isEqualTo(3);
        assertThat(seven.orderCount()).isEqualTo(9);
        assertThat(seven.lastOrderAt()).isEqualTo(lastOrderAt);
        assertThat(stats.get(8L)).isEqualTo(StrategyRepository.StrategyStats.EMPTY);

        assertThat(sqls).hasSize(2);
        assertThat(sqls.get(0)).contains("FROM okx_shadow_position").contains("IN (?,?)");
        assertThat(sqls.get(1)).contains("FROM okx_shadow_order").contains("IN (?,?)");
        assertThat(argumentArray(0)).containsExactly(7L, 8L);
    }

    // ------------------------------------------------------------------ 辅助

    private void stubList(List<StrategyRepository.StrategyRow> rows) {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<StrategyRepository.StrategyRow>>any(),
                any(Object[].class)))
                .thenAnswer(inv -> {
                    sqls.add(inv.getArgument(0));
                    argumentArrays.add(flatten(inv.getRawArguments()));
                    return rows;
                });
    }

    private static Object[] flatten(Object[] rawArguments) {
        if (rawArguments.length >= 3 && rawArguments[2] instanceof Object[] nested) {
            return nested;
        }
        return Arrays.copyOfRange(rawArguments, 2, rawArguments.length);
    }

    private Object[] argumentArray(int index) {
        return argumentArrays.get(index);
    }
}
