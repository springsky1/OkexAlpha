package com.okexalpha.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.domain.OpenInterestRow;
import com.okexalpha.domain.OpenInterestVolumeRow;
import com.okexalpha.domain.Period;
import com.okexalpha.domain.RatioRow;
import com.okexalpha.domain.RatioType;
import com.okexalpha.domain.TakerVolumeRow;
import com.okexalpha.service.StatisticsQueryService;
import com.okexalpha.web.dto.ApiResponse;
import com.okexalpha.web.dto.PageArgs;
import com.okexalpha.web.dto.TimeArgs;

@RestController
@RequestMapping("/api/v1")
public class StatisticsController {

    private final StatisticsQueryService statisticsQueryService;

    public StatisticsController(StatisticsQueryService statisticsQueryService) {
        this.statisticsQueryService = statisticsQueryService;
    }

    @GetMapping("/ratios/long-short")
    public ApiResponse<List<RatioRow>> longShort(
            @RequestParam String ratioType,
            @RequestParam String scope,
            @RequestParam String period,
            @RequestParam(required = false) Long startMs,
            @RequestParam(required = false) Long endMs,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "desc") String order) {
        RatioType type = RatioType.fromCode(ratioType);
        String periodCode = Period.fromCode(period).code();
        TimeArgs time = TimeArgs.of(startMs, endMs, start, end);
        PageArgs page = PageArgs.of(limit, 0, 500, 1000);
        return ApiResponse.ok(statisticsQueryService.ratios(type.name(), scope, periodCode, time.startMs(),
                time.endMs(), page.limit(), !"asc".equalsIgnoreCase(order)));
    }

    @GetMapping("/stats/open-interest-volume")
    public ApiResponse<List<OpenInterestVolumeRow>> openInterestVolume(
            @RequestParam String ccy,
            @RequestParam String period,
            @RequestParam(required = false) Long startMs,
            @RequestParam(required = false) Long endMs,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "desc") String order) {
        String periodCode = Period.fromCode(period).code();
        TimeArgs time = TimeArgs.of(startMs, endMs, start, end);
        PageArgs page = PageArgs.of(limit, 0, 500, 1000);
        return ApiResponse.ok(statisticsQueryService.openInterestVolume(ccy, periodCode, time.startMs(),
                time.endMs(), page.limit(), !"asc".equalsIgnoreCase(order)));
    }

    @GetMapping("/stats/taker-volume")
    public ApiResponse<List<TakerVolumeRow>> takerVolume(
            @RequestParam(defaultValue = "CCY") String scopeKind,
            @RequestParam String scope,
            @RequestParam String period,
            @RequestParam(required = false) Long startMs,
            @RequestParam(required = false) Long endMs,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "desc") String order) {
        String periodCode = Period.fromCode(period).code();
        TimeArgs time = TimeArgs.of(startMs, endMs, start, end);
        PageArgs page = PageArgs.of(limit, 0, 500, 1000);
        return ApiResponse.ok(statisticsQueryService.takerVolume(scopeKind.toUpperCase(), scope, periodCode,
                time.startMs(), time.endMs(), page.limit(), !"asc".equalsIgnoreCase(order)));
    }

    @GetMapping("/stats/open-interest")
    public ApiResponse<List<OpenInterestRow>> openInterest(
            @RequestParam String instId,
            @RequestParam(required = false) Long startMs,
            @RequestParam(required = false) Long endMs,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "desc") String order) {
        TimeArgs time = TimeArgs.of(startMs, endMs, start, end);
        PageArgs page = PageArgs.of(limit, 0, 500, 1000);
        return ApiResponse.ok(statisticsQueryService.openInterest(instId, time.startMs(), time.endMs(),
                page.limit(), !"asc".equalsIgnoreCase(order)));
    }
}
