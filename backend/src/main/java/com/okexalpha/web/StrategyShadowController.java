package com.okexalpha.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.domain.HealthCheckRow;
import com.okexalpha.domain.PortfolioSnapshotRow;
import com.okexalpha.domain.ShadowOrderRow;
import com.okexalpha.domain.ShadowPositionRow;
import com.okexalpha.service.strategy.shadow.HealthCheckEngine;
import com.okexalpha.service.strategy.shadow.ShadowEngine;
import com.okexalpha.service.strategy.shadow.ShadowQueryService;
import com.okexalpha.web.dto.ApiResponse;
import com.okexalpha.web.dto.PageArgs;

/**
 * 影子订单 / 健康度接口（阶段 C / P2、P3，纯本地账本，不发真实订单）。
 *
 * <ul>
 *   <li>GET  /api/v1/strategy/shadow/status                     引擎心跳与最新组合/体检</li>
 *   <li>GET  /api/v1/strategy/shadow/positions?status=OPEN      影子持仓</li>
 *   <li>GET  /api/v1/strategy/shadow/orders?instId=             影子订单</li>
 *   <li>GET  /api/v1/strategy/shadow/portfolio                  组合快照</li>
 *   <li>POST /api/v1/strategy/shadow/tick                       手动跑一轮影子引擎</li>
 *   <li>POST /api/v1/strategy/shadow/positions/{id}/close       手动平掉一个影子持仓</li>
 *   <li>GET  /api/v1/strategy/health/latest                     最近一轮体检结论</li>
 *   <li>GET  /api/v1/strategy/health/checks?level=FAIL          体检历史</li>
 *   <li>POST /api/v1/strategy/health/check                      手动跑一轮体检</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/strategy")
public class StrategyShadowController {

    private final ShadowQueryService service;

    public StrategyShadowController(ShadowQueryService service) {
        this.service = service;
    }

    @GetMapping("/shadow/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(service.status());
    }

    @GetMapping("/shadow/positions")
    public ApiResponse<List<ShadowPositionRow>> positions(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset) {
        PageArgs page = PageArgs.of(limit, offset, 100, 500);
        return ApiResponse.ok(service.positions(status, page.limit(), page.offset()));
    }

    @GetMapping("/shadow/orders")
    public ApiResponse<List<ShadowOrderRow>> orders(@RequestParam(required = false) String status,
            @RequestParam(required = false) String instId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        PageArgs page = PageArgs.of(limit, offset, 100, 500);
        return ApiResponse.ok(service.orders(status, instId, page.limit(), page.offset()));
    }

    @GetMapping("/shadow/portfolio")
    public ApiResponse<List<PortfolioSnapshotRow>> portfolio(@RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        PageArgs page = PageArgs.of(limit, offset, 200, 1000);
        return ApiResponse.ok(service.portfolio(page.limit(), page.offset()));
    }

    @PostMapping("/shadow/tick")
    public ApiResponse<Map<String, Object>> tick() {
        ShadowEngine.TickResult result = service.tick();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strategies", result.strategies());
        body.put("opened", result.opened());
        body.put("closed", result.closed());
        body.put("layered", result.layered());
        body.put("marked", result.marked());
        body.put("skipped", result.skipped());
        body.put("durationMs", result.durationMs());
        body.put("note", result.note());
        return ApiResponse.ok(body);
    }

    @PostMapping("/shadow/positions/{id}/close")
    public ApiResponse<Map<String, Object>> closePosition(@PathVariable long id) {
        return ApiResponse.ok(service.closePosition(id));
    }

    @GetMapping("/health/latest")
    public ApiResponse<List<HealthCheckRow>> latestHealth() {
        return ApiResponse.ok(service.latestHealth());
    }

    @GetMapping("/health/checks")
    public ApiResponse<List<HealthCheckRow>> healthChecks(@RequestParam(required = false) String level,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer offset) {
        PageArgs page = PageArgs.of(limit, offset, 100, 500);
        return ApiResponse.ok(service.healthChecks(level, page.limit(), page.offset()));
    }

    @PostMapping("/health/check")
    public ApiResponse<Map<String, Object>> runHealthCheck() {
        HealthCheckEngine.CheckResult result = service.runHealthCheck();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", result.ok());
        body.put("warn", result.warn());
        body.put("fail", result.fail());
        body.put("autoExited", result.autoExited());
        body.put("durationMs", result.durationMs());
        body.put("summary", result.summary());
        return ApiResponse.ok(body);
    }
}
