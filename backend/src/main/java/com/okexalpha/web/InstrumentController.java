package com.okexalpha.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.domain.InstrumentRow;
import com.okexalpha.service.InstrumentQueryService;
import com.okexalpha.web.dto.ApiResponse;
import com.okexalpha.web.dto.PageArgs;

@RestController
@RequestMapping("/api/v1/instruments")
public class InstrumentController {

    private final InstrumentQueryService instrumentQueryService;

    public InstrumentController(InstrumentQueryService instrumentQueryService) {
        this.instrumentQueryService = instrumentQueryService;
    }

    @GetMapping
    public ApiResponse<List<InstrumentRow>> list(
            @RequestParam(required = false) String instType,
            @RequestParam(required = false) String baseCcy,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        PageArgs page = PageArgs.of(limit, offset, 100, 1000);
        return ApiResponse.ok(instrumentQueryService.list(instType, baseCcy, state, page.limit(), page.offset()));
    }

    @GetMapping("/{instId}")
    public ApiResponse<InstrumentRow> get(@PathVariable String instId) {
        return ApiResponse.ok(instrumentQueryService.get(instId));
    }
}
