package com.okexalpha.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.service.strategy.live.GateReport;
import com.okexalpha.service.strategy.live.LiveTradingService;
import com.okexalpha.service.strategy.live.TradingGate;
import com.okexalpha.web.dto.ApiResponse;
import com.okexalpha.web.dto.LiveOrderRequest;

/**
 * 实盘门禁与受控下单接口（阶段 C / P4）。
 *
 * <ul>
 *   <li>GET  /api/v1/strategy/live/gate     门禁检查明细（默认 mode=SHADOW 时 liveAllowed=false）</li>
 *   <li>GET  /api/v1/strategy/live/account  交易所账户余额/持仓（未配密钥时 configured=false）</li>
 *   <li>POST /api/v1/strategy/live/orders   受控下单（门禁不通过返回 422，绝不触网）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/strategy/live")
public class StrategyLiveController {

    private final LiveTradingService service;

    public StrategyLiveController(LiveTradingService service) {
        this.service = service;
    }

    @GetMapping("/gate")
    public ApiResponse<Map<String, Object>> gate() {
        GateReport report = service.report();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", report.mode());
        body.put("liveAllowed", report.liveAllowed());
        body.put("blockers", report.blockers());
        body.put("checks", report.checks());
        body.put("caps", report.caps());
        return ApiResponse.ok(body);
    }

    @GetMapping("/account")
    public ApiResponse<Map<String, Object>> account() {
        return ApiResponse.ok(service.account());
    }

    @PostMapping("/orders")
    public ApiResponse<Map<String, Object>> placeOrder(@RequestBody LiveOrderRequest request) {
        return ApiResponse.ok(service.placeOrder(request));
    }
}
