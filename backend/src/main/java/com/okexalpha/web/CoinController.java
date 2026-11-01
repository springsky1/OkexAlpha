package com.okexalpha.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.repository.CoinRepository.CoinRecord;
import com.okexalpha.service.CoinService;
import com.okexalpha.web.dto.ApiResponse;
import com.okexalpha.web.dto.PageArgs;

@RestController
@RequestMapping("/api/v1/coins")
public class CoinController {

    private final CoinService coinService;

    public CoinController(CoinService coinService) {
        this.coinService = coinService;
    }

    @GetMapping
    public ApiResponse<List<CoinRecord>> list(
            @RequestParam(required = false) String channel,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        PageArgs page = PageArgs.of(limit, offset, 100, 1000);
        return ApiResponse.ok(coinService.list(channel, query, page.limit(), page.offset()));
    }
}
