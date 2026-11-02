package com.okexalpha.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.service.strategy.DataQualityService;
import com.okexalpha.web.dto.ApiResponse;

/**
 * 数据缺陷体检接口（阶段 C / P5）。
 *
 * <p>{@code GET /api/v1/data/quality?bar=1m&gapLimit=20} 返回该周期下每个合约的覆盖率、
 * 缺口总数与最大的若干缺口，用于判断"一年数据够不够用、哪几段缺"。</p>
 */
@RestController
@RequestMapping("/api/v1/data")
public class DataQualityController {

    private final DataQualityService service;

    public DataQualityController(DataQualityService service) {
        this.service = service;
    }

    @GetMapping("/quality")
    public ApiResponse<Map<String, Object>> quality(@RequestParam(required = false) String bar,
            @RequestParam(required = false) Integer gapLimit) {
        return ApiResponse.ok(service.report(bar == null || bar.isBlank() ? "1m" : bar,
                gapLimit == null ? 20 : gapLimit));
    }
}
