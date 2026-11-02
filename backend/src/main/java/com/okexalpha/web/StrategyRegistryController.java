package com.okexalpha.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.service.strategy.StrategyRegistryService;
import com.okexalpha.web.dto.ApiResponse;

/**
 * 策略注册表（账户节点用）：当前运行的马丁合约策略 + 历史策略 + 单策略订单/持仓。
 *
 * <p>全部 GET、纯本地读，不触发引擎、不下单。</p>
 */
@RestController
@RequestMapping("/api/v1/strategy/strategies")
public class StrategyRegistryController {

    private final StrategyRegistryService service;

    public StrategyRegistryController(StrategyRegistryService service) {
        this.service = service;
    }

    /** 策略清单；state=running（运行中）|history（历史）|all（默认）。 */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return ApiResponse.ok(service.list(state, status, mode, limit, offset));
    }

    /** 单策略详情（含最近 20 条影子订单与当前持仓）。 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    /** 单策略影子订单（status 可选 NEW/FILLED/REJECTED/CANCELED）。 */
    @GetMapping("/{id}/orders")
    public ApiResponse<Map<String, Object>> orders(@PathVariable long id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return ApiResponse.ok(service.orders(id, status, limit, offset));
    }

    /** 单策略影子持仓（status=OPEN/CLOSED）。 */
    @GetMapping("/{id}/positions")
    public ApiResponse<Map<String, Object>> positions(@PathVariable long id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return ApiResponse.ok(service.positions(id, status, limit, offset));
    }
}
