package com.okexalpha.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.okexalpha.service.account.AccountService;
import com.okexalpha.web.dto.ApiResponse;

/** 账户控制器只是薄壳：包成 ApiResponse 并原样透传参数。 */
class AccountControllerTest {

    private AccountService accountService;
    private AccountController controller;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        controller = new AccountController(accountService);
    }

    @Test
    @DisplayName("summary 包成 code=0 的 ApiResponse")
    void summaryWrapsServiceResult() {
        Map<String, Object> payload = payload("configured", false);
        given(accountService.summary()).willReturn(payload);

        ApiResponse<Map<String, Object>> response = controller.summary();

        assertThat(response.code()).isEqualTo("0");
        assertThat(response.msg()).isEmpty();
        assertThat(response.data()).isSameAs(payload);
        verify(accountService).summary();
    }

    @Test
    @DisplayName("balance 包成 code=0 的 ApiResponse")
    void balanceWrapsServiceResult() {
        Map<String, Object> payload = payload("totalEq", "12.5");
        given(accountService.balance()).willReturn(payload);

        ApiResponse<Map<String, Object>> response = controller.balance();

        assertThat(response.code()).isEqualTo("0");
        assertThat(response.data()).isSameAs(payload);
    }

    @Test
    @DisplayName("positions 把 instType 原样透传（归一化由 service 负责）")
    void positionsPassThroughInstType() {
        Map<String, Object> payload = payload("instType", "SWAP");
        given(accountService.positions("SWAP")).willReturn(payload);

        ApiResponse<Map<String, Object>> response = controller.positions("SWAP");

        assertThat(response.data()).isSameAs(payload);
        verify(accountService).positions("SWAP");
    }

    @Test
    @DisplayName("positions 允许省略 instType")
    void positionsAllowsNullInstType() {
        given(accountService.positions(null)).willReturn(payload("instType", "SWAP"));

        assertThat(controller.positions(null).code()).isEqualTo("0");
        verify(accountService).positions(null);
    }

    @Test
    @DisplayName("orders 四个查询参数全部透传")
    void ordersPassThroughAllArguments() {
        Map<String, Object> payload = payload("state", "pending");
        given(accountService.orders("pending", "SWAP", "BTC-USDT-SWAP", 20)).willReturn(payload);

        ApiResponse<Map<String, Object>> response = controller.orders("pending", "SWAP", "BTC-USDT-SWAP", 20);

        assertThat(response.data()).isSameAs(payload);
        verify(accountService).orders("pending", "SWAP", "BTC-USDT-SWAP", 20);
    }

    @Test
    @DisplayName("orders 允许全部参数省略（走 service 默认 local/50）")
    void ordersAllowsAllNulls() {
        given(accountService.orders(null, null, null, null)).willReturn(payload("state", "local"));

        assertThat(controller.orders(null, null, null, null).code()).isEqualTo("0");
        verify(accountService).orders(null, null, null, null);
    }

    // ------------------------------------------------------------ 订单中心

    @Test
    @DisplayName("positions-history 透传 instType 与 limit")
    void positionsHistoryPassesArguments() {
        Map<String, Object> payload = payload("state", "positions-history");
        given(accountService.positionsHistory("SWAP", 100)).willReturn(payload);

        ApiResponse<Map<String, Object>> response = controller.positionsHistory("SWAP", 100);

        assertThat(response.code()).isEqualTo("0");
        assertThat(response.data()).isSameAs(payload);
        verify(accountService).positionsHistory("SWAP", 100);
    }

    @Test
    @DisplayName("fills / bills / borrow-repay 各自透传参数")
    void fillsBillsBorrowRepayPassArguments() {
        given(accountService.fills(null, null)).willReturn(payload("state", "fills"));
        given(accountService.bills(100)).willReturn(payload("state", "bills"));
        given(accountService.borrowRepay(30, "USDT")).willReturn(payload("state", "borrow-repay"));

        assertThat(controller.fills(null, null).data()).isNotNull();
        assertThat(controller.bills(100).data()).isNotNull();
        assertThat(controller.borrowRepay(30, "USDT").data()).isNotNull();
        verify(accountService).fills(null, null);
        verify(accountService).bills(100);
        verify(accountService).borrowRepay(30, "USDT");
    }

    @Test
    @DisplayName("strategy-center 透传 state/limit/offset")
    void strategyCenterPassesArguments() {
        Map<String, Object> payload = payload("state", "all");
        given(accountService.strategyCenter("all", 50, 0)).willReturn(payload);

        ApiResponse<Map<String, Object>> response = controller.strategyCenter("all", 50, 0);

        assertThat(response.data()).isSameAs(payload);
        verify(accountService).strategyCenter("all", 50, 0);
    }

    @Test
    @DisplayName("POST strategy-center/{id}/status 用请求体里的 status 调 service")
    void strategyCenterStatusPassesBodyStatus() {
        Map<String, Object> payload = payload("status", "ARCHIVED");
        given(accountService.setStrategyStatus(7L, "STOPPED")).willReturn(payload);

        ApiResponse<Map<String, Object>> response = controller.strategyCenterStatus(7L,
                new AccountController.StatusRequest("STOPPED"));

        assertThat(response.data()).isSameAs(payload);
        verify(accountService).setStrategyStatus(7L, "STOPPED");
    }

    @Test
    @DisplayName("POST strategy-center/{id}/status 缺少请求体时拒绝")
    void strategyCenterStatusRequiresBody() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> controller.strategyCenterStatus(7L, null))
                .withMessage("request body is required");
    }

    private static Map<String, Object> payload(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
