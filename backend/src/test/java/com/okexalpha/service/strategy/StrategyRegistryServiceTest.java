package com.okexalpha.service.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.okexalpha.domain.ShadowOrderRow;
import com.okexalpha.domain.ShadowPositionRow;
import com.okexalpha.error.NotFoundException;
import com.okexalpha.repository.ShadowRepository;
import com.okexalpha.repository.StrategyRepository;

import tools.jackson.databind.ObjectMapper;

/** 策略注册表读模型：state 归一、分页夹取、JSON 安全降级、缺失策略 404。 */
class StrategyRegistryServiceTest {

    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    private static final OffsetDateTime DECIDED_AT = OffsetDateTime.parse("2026-02-02T03:04:05Z");

    private StrategyRepository strategyRepository;
    private ShadowRepository shadowRepository;
    private StrategyRegistryService service;

    @BeforeEach
    void setUp() {
        strategyRepository = mock(StrategyRepository.class);
        shadowRepository = mock(ShadowRepository.class);
        service = new StrategyRegistryService(strategyRepository, shadowRepository, new ObjectMapper());
    }

    // ------------------------------------------------------------ list

    @Test
    @DisplayName("默认 state=all、limit=50、offset=0，并带上运行中口径说明")
    void listDefaults() {
        given(strategyRepository.list("all", null, null, 50, 0)).willReturn(List.of(row(1L, "SHADOW", "PROMOTED")));
        given(strategyRepository.count("all", null, null)).willReturn(1L);
        given(strategyRepository.stats(anyList()))
                .willReturn(Map.of(1L, new StrategyRepository.StrategyStats(2, 5000d, 12.5d, 3, 9, DECIDED_AT)));

        Map<String, Object> out = service.list(null, null, null, null, null);

        assertThat(out).containsEntry("state", "all")
                .containsEntry("total", 1L)
                .containsEntry("limit", 50)
                .containsEntry("offset", 0);
        assertThat((String) out.get("runningDefinition")).contains("mode='SHADOW'").contains("CANDIDATE")
                .contains("PROMOTED");
        List<Map<String, Object>> items = list(out.get("items"));
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("id", 1L)
                .containsEntry("running", true)
                .containsEntry("openPositions", 2)
                .containsEntry("orderCount", 9)
                .containsEntry("lastOrderAt", DECIDED_AT.toString());
    }

    @Test
    @DisplayName("state 归一化：trim + 小写后透传给仓储")
    void listNormalizesState() {
        given(strategyRepository.list("running", null, null, 10, 0)).willReturn(List.of());
        given(strategyRepository.count("running", null, null)).willReturn(0L);
        given(strategyRepository.stats(anyList())).willReturn(Map.of());

        Map<String, Object> out = service.list("  RUNNING ", null, null, 10, 0);

        assertThat(out).containsEntry("state", "running");
        verify(strategyRepository).list("running", null, null, 10, 0);
    }

    @Test
    @DisplayName("未知 state 直接 400（不是归一 all）")
    void listRejectsUnknownState() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.list("archive", null, null, 10, 0))
                .withMessage("state must be one of running|history|all");
        verify(strategyRepository, never()).list(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("limit < 1 报错；limit > 200 夹到 200；负 offset 归零")
    void listClampsPaging() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.list(null, null, null, 0, 0))
                .withMessage("limit must be >= 1");

        given(strategyRepository.list("all", null, null, 200, 0)).willReturn(List.of());
        given(strategyRepository.count("all", null, null)).willReturn(0L);
        given(strategyRepository.stats(anyList())).willReturn(Map.of());

        Map<String, Object> out = service.list(null, null, null, 500, -5);

        assertThat(out).containsEntry("limit", 200).containsEntry("offset", 0);
        verify(strategyRepository).list("all", null, null, 200, 0);
    }

    @Test
    @DisplayName("status/mode 原样透传（大写由仓储负责）")
    void listPassesStatusAndModeThrough() {
        given(strategyRepository.list("all", "promoted", "shadow", 5, 10)).willReturn(List.of());
        given(strategyRepository.count("all", "promoted", "shadow")).willReturn(0L);
        given(strategyRepository.stats(anyList())).willReturn(Map.of());

        service.list(null, "promoted", "shadow", 5, 10);

        verify(strategyRepository).list("all", "promoted", "shadow", 5, 10);
        verify(strategyRepository).count("all", "promoted", "shadow");
    }

    @Test
    @DisplayName("仓储没给聚合时按 EMPTY 兜底")
    void listFallsBackToEmptyStats() {
        given(strategyRepository.list("all", null, null, 50, 0)).willReturn(List.of(row(1L, "SHADOW", "DRAFT")));
        given(strategyRepository.count("all", null, null)).willReturn(1L);
        given(strategyRepository.stats(anyList())).willReturn(Map.of());

        Map<String, Object> out = service.list(null, null, null, null, null);

        Map<String, Object> item = list(out.get("items")).get(0);
        assertThat(item).containsEntry("openPositions", 0)
                .containsEntry("closedPositions", 0)
                .containsEntry("openNotionalUsd", 0.0d)
                .containsEntry("unrealizedPnlUsd", 0.0d)
                .containsEntry("orderCount", 0)
                .containsEntry("lastOrderAt", null);
    }

    @Test
    @DisplayName("running 标记只对 SHADOW + CANDIDATE/PROMOTED 成立")
    void runningFlagFollowsEngineDefinition() {
        given(strategyRepository.list("all", null, null, 50, 0)).willReturn(List.of(
                row(1L, "SHADOW", "PROMOTED"), row(2L, "SHADOW", "CANDIDATE"),
                row(3L, "SHADOW", "DRAFT"), row(4L, "REAL", "PROMOTED")));
        given(strategyRepository.count("all", null, null)).willReturn(4L);
        given(strategyRepository.stats(anyList())).willReturn(Map.of());

        List<Map<String, Object>> items = list(service.list(null, null, null, null, null).get("items"));

        assertThat(items).extracting(item -> item.get("running")).containsExactly(true, true, false, false);
    }

    @Test
    @DisplayName("params/universe 解析成 JsonNode")
    void listParsesJsonColumns() {
        given(strategyRepository.list("all", null, null, 50, 0))
                .willReturn(List.of(rowWithJson(1L, "{\"layers\":8}", "[\"BTC-USDT-SWAP\"]")));
        given(strategyRepository.count("all", null, null)).willReturn(1L);
        given(strategyRepository.stats(anyList())).willReturn(Map.of());

        Map<String, Object> item = list(service.list(null, null, null, null, null).get("items")).get(0);

        assertThat(item.get("params")).isInstanceOf(tools.jackson.databind.JsonNode.class);
        assertThat(((tools.jackson.databind.JsonNode) item.get("params")).get("layers").asInt(0)).isEqualTo(8);
        assertThat(((tools.jackson.databind.JsonNode) item.get("universe")).get(0).asString())
                .isEqualTo("BTC-USDT-SWAP");
    }

    @Test
    @DisplayName("非法 JSON 安全降级成原始字符串，空白降级成 null")
    void listDegradesInvalidJson() {
        given(strategyRepository.list("all", null, null, 50, 0))
                .willReturn(List.of(rowWithJson(1L, "{oops", "   ")));
        given(strategyRepository.count("all", null, null)).willReturn(1L);
        given(strategyRepository.stats(anyList())).willReturn(Map.of());

        Map<String, Object> item = list(service.list(null, null, null, null, null).get("items")).get(0);

        assertThat(item).containsEntry("params", "{oops").containsEntry("universe", null);
    }

    // ------------------------------------------------------------ detail

    @Test
    @DisplayName("detail 找不到策略时 404")
    void detailThrowsNotFound() {
        given(strategyRepository.find(42L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(42L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("strategy 42 not found");
        verify(shadowRepository, never()).ordersByStrategy(anyLong(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("detail 组装基础信息 + 最近订单 + 当前持仓")
    void detailIncludesRecentOrdersAndOpenPositions() {
        given(strategyRepository.find(7L)).willReturn(Optional.of(row(7L, "SHADOW", "PROMOTED")));
        given(strategyRepository.stats(List.of(7L))).willReturn(Map.of(7L,
                new StrategyRepository.StrategyStats(1, 2000d, 5d, 0, 2, DECIDED_AT)));
        given(shadowRepository.ordersByStrategy(7L, null, 20, 0)).willReturn(List.of(orderRow()));
        given(shadowRepository.positionsByStrategy(7L, "OPEN", 50, 0)).willReturn(List.of(positionRow()));

        Map<String, Object> out = service.detail(7L);

        // openPositions 是计数（与列表视图同义），持仓明细在 positions 数组里。
        assertThat(out).containsEntry("id", 7L).containsEntry("running", true).containsEntry("orderCount", 2)
                .containsEntry("closedPositions", 0).containsEntry("openPositions", 1);
        assertThat(list(out.get("recentOrders"))).hasSize(1);
        assertThat(list(out.get("positions"))).hasSize(1);
        verify(shadowRepository).ordersByStrategy(7L, null, 20, 0);
        verify(shadowRepository).positionsByStrategy(7L, "OPEN", 50, 0);
    }

    // ------------------------------------------------------------ orders / positions

    @Test
    @DisplayName("orders 找不到策略时 404，不查影子账本")
    void ordersRequireExistingStrategy() {
        given(strategyRepository.find(42L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.orders(42L, "FILLED", 10, 0))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("strategy 42 not found");
        verify(shadowRepository, never()).ordersByStrategy(anyLong(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("orders 映射影子订单字段并夹取分页")
    void ordersMapShadowOrderFields() {
        given(strategyRepository.find(7L)).willReturn(Optional.of(row(7L, "SHADOW", "PROMOTED")));
        given(shadowRepository.ordersByStrategy(7L, "FILLED", 200, 0)).willReturn(List.of(orderRow()));

        Map<String, Object> out = service.orders(7L, "FILLED", 500, -1);

        assertThat(out).containsEntry("strategyId", 7L)
                .containsEntry("status", "FILLED")
                .containsEntry("limit", 200)
                .containsEntry("offset", 0);
        Map<String, Object> item = list(out.get("items")).get(0);
        assertThat(item).containsEntry("id", 11L)
                .containsEntry("strategyId", 7L)
                .containsEntry("instId", "BTC-USDT-SWAP")
                .containsEntry("side", "buy")
                .containsEntry("status", "FILLED")
                .containsEntry("decidedAt", DECIDED_AT.toString());
    }

    @Test
    @DisplayName("positions 映射影子持仓字段（closedAt 为 null 时保持 null）")
    void positionsMapShadowPositionFields() {
        given(strategyRepository.find(7L)).willReturn(Optional.of(row(7L, "SHADOW", "PROMOTED")));
        given(shadowRepository.positionsByStrategy(7L, "OPEN", 50, 0)).willReturn(List.of(positionRow()));

        Map<String, Object> out = service.positions(7L, "OPEN", null, null);

        assertThat(out).containsEntry("strategyId", 7L).containsEntry("limit", 50).containsEntry("offset", 0);
        Map<String, Object> item = list(out.get("items")).get(0);
        assertThat(item).containsEntry("id", 21L)
                .containsEntry("instId", "BTC-USDT-SWAP")
                .containsEntry("leverage", 5.0d)
                .containsEntry("openedAt", CREATED_AT.toString())
                .containsEntry("closedAt", null);
    }

    @Test
    @DisplayName("positions 里 limit 非法时 400")
    void positionsRejectBadLimit() {
        given(strategyRepository.find(7L)).willReturn(Optional.of(row(7L, "SHADOW", "PROMOTED")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.positions(7L, "OPEN", 0, 0))
                .withMessage("limit must be >= 1");
    }

    // ------------------------------------------------------------ 辅助

    private static StrategyRepository.StrategyRow row(long id, String mode, String status) {
        return new StrategyRepository.StrategyRow(id, "martingale-" + id, status, mode, null, null, 100d, null, null,
                CREATED_AT, CREATED_AT);
    }

    private static StrategyRepository.StrategyRow rowWithJson(long id, String paramsJson, String universeJson) {
        return new StrategyRepository.StrategyRow(id, "martingale-" + id, "PROMOTED", "SHADOW", paramsJson,
                universeJson, 100d, null, null, CREATED_AT, null);
    }

    private static ShadowOrderRow orderRow() {
        return new ShadowOrderRow(11L, 7L, "BTC-USDT-SWAP", "buy", "market", 0.5d, 40000d, 20000d, "FILLED",
                "signal-hit", 1735689600000L, DECIDED_AT);
    }

    private static ShadowPositionRow positionRow() {
        return new ShadowPositionRow(21L, 7L, "BTC-USDT-SWAP", "long", 0.5d, 40000d, 41000d, 20500d, 500d, 5.0d,
                33000d, 4100d, "OPEN", CREATED_AT, CREATED_AT, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
