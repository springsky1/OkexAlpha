package com.okexalpha.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.domain.Bar;
import com.okexalpha.domain.MinOrderRow;
import com.okexalpha.error.BadRequestException;
import com.okexalpha.error.NotFoundException;
import com.okexalpha.repository.MinOrderRepository;
import com.okexalpha.service.strategy.DataPlanService;
import com.okexalpha.service.strategy.MinOrderService;
import com.okexalpha.service.strategy.ParamCatalogService;
import com.okexalpha.strategy.domain.ParamCatalog;
import com.okexalpha.web.dto.ApiResponse;
import com.okexalpha.web.dto.PageArgs;
import com.okexalpha.web.dto.TimeArgs;

/**
 * 策略模块（阶段 C）只读 + 轻写接口：
 * <ul>
 *   <li>GET  /param-catalog        交易参数契约（白名单 + 必填 + 枚举）</li>
 *   <li>GET  /min-order            最小下单量 / 按美元金额换算 sz</li>
 *   <li>POST /min-order/refresh    刷新最小下单量快照（拉一次 market/tickers）</li>
 *   <li>GET  /data-plan            补拉成本估算 + 库内覆盖率</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/strategy")
public class StrategyController {

    private final ParamCatalogService paramCatalogService;
    private final MinOrderService minOrderService;
    private final MinOrderRepository minOrderRepository;
    private final DataPlanService dataPlanService;

    public StrategyController(ParamCatalogService paramCatalogService, MinOrderService minOrderService,
            MinOrderRepository minOrderRepository, DataPlanService dataPlanService) {
        this.paramCatalogService = paramCatalogService;
        this.minOrderService = minOrderService;
        this.minOrderRepository = minOrderRepository;
        this.dataPlanService = dataPlanService;
    }

    @GetMapping("/param-catalog")
    public ApiResponse<List<ParamCatalog>> paramCatalog(@RequestParam(required = false) String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return ApiResponse.ok(paramCatalogService.catalogs());
        }
        return ApiResponse.ok(List.of(paramCatalogService.catalogOf(endpoint.trim())));
    }

    /** 发送前自检：unknown / missing / invalid enum 三类问题一次返回。 */
    @GetMapping("/param-catalog/validate")
    public ApiResponse<ParamCatalogService.Validation> validate(@RequestParam String endpoint,
            @RequestParam(required = false) List<String> fields) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        if (fields != null) {
            for (String field : fields) {
                int idx = field.indexOf('=');
                if (idx <= 0) {
                    throw new BadRequestException("fields must be name=value pairs, got: " + field);
                }
                map.put(field.substring(0, idx).trim(), field.substring(idx + 1).trim());
            }
        }
        return ApiResponse.ok(paramCatalogService.validate(endpoint.trim(), map));
    }

    /**
     * 不传 sizeUsd：返回平台最小下单量（minSz 对应的 sz 与美元金额）。
     * 传 sizeUsd：把"买 N 美元"换算成合法 sz（lotSz 整数倍、不低于 minSz）。
     */
    @GetMapping("/min-order")
    public ApiResponse<?> minOrder(@RequestParam(required = false) String instId,
            @RequestParam(required = false) String instType,
            @RequestParam(required = false) Boolean tradable,
            @RequestParam(required = false) Double sizeUsd,
            @RequestParam(required = false) Integer limit) {
        if (instId != null && !instId.isBlank()) {
            MinOrderService.Sizing sizing = minOrderService.sizeFor(instId.trim(), sizeUsd);
            if (sizing == null) {
                throw new NotFoundException("no min-order snapshot for " + instId
                        + " (run POST /api/v1/strategy/min-order/refresh first)");
            }
            return ApiResponse.ok(sizing);
        }
        PageArgs page = PageArgs.of(limit, 0, 200, 1000);
        List<MinOrderRow> rows = minOrderRepository.findAll(instType, tradable, page.limit(), 0);
        return ApiResponse.ok(new MinOrderList(rows, minOrderRepository.count(), minOrderRepository.countTradable()));
    }

    @PostMapping("/min-order/refresh")
    public ApiResponse<MinOrderService.RefreshResult> refreshMinOrder() {
        return ApiResponse.ok(minOrderService.refresh());
    }

    /**
     * 补拉成本估算。instrumentCount 不传时按 instIds 数量（都没有则为库里 SWAP 数）。
     * 传 instIds 时额外返回逐合约覆盖率（缺口判断）。
     */
    @GetMapping("/data-plan")
    public ApiResponse<DataPlanResponse> dataPlan(@RequestParam(defaultValue = "1m") String bar,
            @RequestParam(required = false) List<String> klineTypes,
            @RequestParam(required = false) Long startMs,
            @RequestParam(required = false) Long endMs,
            @RequestParam(required = false) List<String> instIds,
            @RequestParam(required = false) Integer instrumentCount) {
        Bar parsed = Bar.fromCode(bar);
        TimeArgs time = TimeArgs.of(startMs, endMs, null, null);
        long resolvedEnd = time.endMs() != null ? time.endMs() : System.currentTimeMillis();
        // 不传 startMs 时按"近一年"（304 天，与阶段 B 的补拉区间一致）估算
        long resolvedStart = time.startMs() != null ? time.startMs() : resolvedEnd - 304L * 86_400_000L;
        List<String> types = klineTypes == null || klineTypes.isEmpty() ? List.of("CANDLE") : klineTypes;
        int count = instrumentCount != null && instrumentCount > 0 ? instrumentCount
                : instIds != null && !instIds.isEmpty() ? instIds.size()
                        : dataPlanService.swapInstrumentCount();
        DataPlanService.Estimate estimate = dataPlanService.estimate(parsed, types, count, resolvedStart,
                resolvedEnd);
        DataPlanService.Coverage coverage = instIds == null || instIds.isEmpty() ? null
                : dataPlanService.coverage(parsed, types.get(0), resolvedStart, resolvedEnd, instIds);
        return ApiResponse.ok(new DataPlanResponse(estimate, coverage));
    }

    public record MinOrderList(List<MinOrderRow> rows, long total, long tradableTotal) {
    }

    public record DataPlanResponse(DataPlanService.Estimate estimate, DataPlanService.Coverage coverage) {
    }
}
