package com.okexalpha.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.domain.BacktestResultRow;
import com.okexalpha.domain.BacktestRunRow;
import com.okexalpha.service.strategy.BacktestService;
import com.okexalpha.web.dto.ApiResponse;
import com.okexalpha.web.dto.BacktestRequest;
import com.okexalpha.web.dto.PageArgs;

/**
 * 回测接口（阶段 C / P1，纯本地计算，不下单）。
 *
 * <ul>
 *   <li>POST /backtests                       提交批次（202）</li>
 *   <li>GET  /backtests                       批次列表</li>
 *   <li>GET  /backtests/{id}                  批次详情（含 Top 结果与冠军）</li>
 *   <li>GET  /backtests/{id}/results          参数组合排行</li>
 *   <li>GET  /backtests/{id}/results/{rid}/coins  某组合的逐币明细</li>
 *   <li>GET  /backtests/{id}/trades?resultId= 逐笔成交（冠军组合有数据）</li>
 *   <li>GET  /backtests/{id}/results/{rid}/equity 资金曲线</li>
 *   <li>POST /backtests/{id}/cancel           取消</li>
 *   <li>POST /backtests/{id}/promote          提升为候选策略（写 okx_strategy）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/strategy/backtests")
public class StrategyBacktestController {

    private final BacktestService backtestService;

    public StrategyBacktestController(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(@RequestBody(required = false) BacktestRequest request) {
        long runId = backtestService.submit(request == null
                ? new BacktestRequest(null, null, null, null, null, null, null, null, null, null, null, null, null)
                : request);
        BacktestRunRow run = backtestService.detail(runId).get("run") instanceof BacktestRunRow row ? row : null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("coinTotal", run == null ? null : run.coinTotal());
        body.put("combosTotal", run == null ? null : run.combosTotal());
        body.put("status", run == null ? "PENDING" : run.status());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(body));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        PageArgs page = PageArgs.of(limit, offset, 20, 200);
        return ApiResponse.ok(backtestService.runs(page.limit(), page.offset()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.ok(backtestService.detail(id));
    }

    @GetMapping("/{id}/results")
    public ApiResponse<List<BacktestResultRow>> results(@PathVariable long id,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(backtestService.results(id, PageArgs.of(limit, null, 50, 300).limit()));
    }

    @GetMapping("/{id}/results/{resultId}/coins")
    public ApiResponse<List<BacktestResultRow>> coinResults(@PathVariable long id, @PathVariable long resultId,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(backtestService.coinResults(id, resultId, PageArgs.of(limit, null, 100, 500).limit()));
    }

    @GetMapping("/{id}/trades")
    public ApiResponse<List<Map<String, Object>>> trades(@PathVariable long id,
            @RequestParam(required = false) Long resultId, @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(backtestService.trades(id, resultId, PageArgs.of(limit, null, 200, 2000).limit()));
    }

    @GetMapping("/{id}/results/{resultId}/equity")
    public ApiResponse<List<Map<String, Object>>> equity(@PathVariable long id, @PathVariable long resultId,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(backtestService.equity(id, resultId, PageArgs.of(limit, null, 1000, 5000).limit()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable long id) {
        return ApiResponse.ok(backtestService.cancel(id));
    }

    @PostMapping("/{id}/promote")
    public ApiResponse<Map<String, Object>> promote(@PathVariable long id,
            @RequestParam(required = false) Long resultId, @RequestParam(required = false) String name,
            @RequestParam(required = false) String note) {
        return ApiResponse.ok(backtestService.promote(id, resultId, name, note));
    }
}
