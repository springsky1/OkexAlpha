package com.okexalpha.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.okexalpha.service.strategy.StrategyRegistryService;
import com.okexalpha.web.dto.ApiResponse;

/** 策略注册表控制器：薄壳 + 参数透传。 */
class StrategyRegistryControllerTest {

    private StrategyRegistryService service;
    private StrategyRegistryController controller;

    @BeforeEach
    void setUp() {
        service = mock(StrategyRegistryService.class);
        controller = new StrategyRegistryController(service);
    }

    @Test
    @DisplayName("list 包成 code=0 的 ApiResponse")
    void listWrapsServiceResult() {
        Map<String, Object> payload = payload("items", List.of());
        given(service.list(null, null, null, null, null)).willReturn(payload);

        ApiResponse<Map<String, Object>> response = controller.list(null, null, null, null, null);

        assertThat(response.code()).isEqualTo("0");
        assertThat(response.data()).isSameAs(payload);
        verify(service).list(null, null, null, null, null);
    }

    @Test
    @DisplayName("list 五个查询参数全部透传")
    void listPassesThroughFilters() {
        given(service.list("running", "PROMOTED", "SHADOW", 10, 20)).willReturn(payload("total", 1L));

        assertThat(controller.list("running", "PROMOTED", "SHADOW", 10, 20).data()).containsEntry("total", 1L);
        verify(service).list("running", "PROMOTED", "SHADOW", 10, 20);
    }

    @Test
    @DisplayName("detail 用路径 id 调 service")
    void detailPassesPathId() {
        given(service.detail(7L)).willReturn(payload("id", 7L));

        ApiResponse<Map<String, Object>> response = controller.detail(7L);

        assertThat(response.data()).containsEntry("id", 7L);
        verify(service).detail(7L);
    }

    @Test
    @DisplayName("orders 透传 id/status/limit/offset")
    void ordersPassesThrough() {
        given(service.orders(7L, "FILLED", 5, 10)).willReturn(payload("strategyId", 7L));

        assertThat(controller.orders(7L, "FILLED", 5, 10).data()).containsEntry("strategyId", 7L);
        verify(service).orders(7L, "FILLED", 5, 10);
    }

    @Test
    @DisplayName("positions 透传 id/status/limit/offset")
    void positionsPassesThrough() {
        given(service.positions(7L, "OPEN", 5, 10)).willReturn(payload("strategyId", 7L));

        assertThat(controller.positions(7L, "OPEN", 5, 10).data()).containsEntry("strategyId", 7L);
        verify(service).positions(7L, "OPEN", 5, 10);
    }

    private static Map<String, Object> payload(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
