package com.okexalpha.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.domain.Bar;
import com.okexalpha.domain.KlineRow;
import com.okexalpha.domain.KlineType;
import com.okexalpha.service.KlineQueryService;
import com.okexalpha.web.dto.ApiResponse;
import com.okexalpha.web.dto.PageArgs;
import com.okexalpha.web.dto.TimeArgs;

@RestController
@RequestMapping("/api/v1/klines")
public class KlineController {

    private final KlineQueryService klineQueryService;

    public KlineController(KlineQueryService klineQueryService) {
        this.klineQueryService = klineQueryService;
    }

    @GetMapping
    public ApiResponse<List<KlineRow>> query(
            @RequestParam String instId,
            @RequestParam String bar,
            @RequestParam(required = false, defaultValue = "CANDLE") String klineType,
            @RequestParam(required = false) Long startMs,
            @RequestParam(required = false) Long endMs,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "desc") String order) {
        String barCode = Bar.fromCode(bar).code();
        String typeCode = KlineType.fromCode(klineType).name();
        TimeArgs time = TimeArgs.of(startMs, endMs, start, end);
        PageArgs page = PageArgs.of(limit, 0, 500, 5000);
        boolean descending = !"asc".equalsIgnoreCase(order);
        return ApiResponse.ok(klineQueryService.query(instId, barCode, typeCode, time.startMs(), time.endMs(),
                page.limit(), descending));
    }
}
