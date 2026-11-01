package com.okexalpha.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.domain.FundingRateRow;
import com.okexalpha.service.FundingRateQueryService;
import com.okexalpha.web.dto.ApiResponse;
import com.okexalpha.web.dto.PageArgs;
import com.okexalpha.web.dto.TimeArgs;

@RestController
@RequestMapping("/api/v1/funding-rates")
public class FundingRateController {

    private final FundingRateQueryService fundingRateQueryService;

    public FundingRateController(FundingRateQueryService fundingRateQueryService) {
        this.fundingRateQueryService = fundingRateQueryService;
    }

    @GetMapping
    public ApiResponse<List<FundingRateRow>> query(
            @RequestParam String instId,
            @RequestParam(required = false) Long startMs,
            @RequestParam(required = false) Long endMs,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "desc") String order) {
        TimeArgs time = TimeArgs.of(startMs, endMs, start, end);
        PageArgs page = PageArgs.of(limit, 0, 500, 1000);
        boolean descending = !"asc".equalsIgnoreCase(order);
        return ApiResponse.ok(fundingRateQueryService.query(instId, time.startMs(), time.endMs(), page.limit(),
                descending));
    }
}
