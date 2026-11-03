package com.okexalpha.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.service.account.AccountService;
import com.okexalpha.web.dto.ApiResponse;

/**
 * 账户节点（只读）：凭证就绪状态 / 余额 / 持仓 / 我的订单。
 *
 * <p>全部 GET，无任何写操作。响应里不出现 secretKey / passphrase 原文，apiKey 已脱敏。</p>
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** 凭证 + 交易所账户配置（acctLv / posMode / 权限）。 */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.ok(accountService.summary());
    }

    /** 账户余额（多币种明细）。 */
    @GetMapping("/balance")
    public ApiResponse<Map<String, Object>> balance() {
        return ApiResponse.ok(accountService.balance());
    }

    /** 当前持仓（默认 SWAP）。 */
    @GetMapping("/positions")
    public ApiResponse<Map<String, Object>> positions(
            @RequestParam(required = false) String instType) {
        return ApiResponse.ok(accountService.positions(instType));
    }

    /**
     * 我的订单。
     *
     * @param state local（本地影子账本，默认）/ pending（交易所挂单）/ history（交易所历史订单）
     */
    @GetMapping("/orders")
    public ApiResponse<Map<String, Object>> orders(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String instType,
            @RequestParam(required = false) String instId,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(accountService.orders(state, instType, instId, limit));
    }

    /** 历史仓位（已平仓，交易所保留最近 3 个月）。 */
    @GetMapping("/positions-history")
    public ApiResponse<Map<String, Object>> positionsHistory(
            @RequestParam(required = false) String instType,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(accountService.positionsHistory(instType, limit));
    }

    /** 成交明细（最近 3 个月）。 */
    @GetMapping("/fills")
    public ApiResponse<Map<String, Object>> fills(
            @RequestParam(required = false) String instType,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(accountService.fills(instType, limit));
    }

    /** 资金账单（最近 7 天）。 */
    @GetMapping("/bills")
    public ApiResponse<Map<String, Object>> bills(
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(accountService.bills(limit));
    }

    /** 借币/还币记录。 */
    @GetMapping("/borrow-repay")
    public ApiResponse<Map<String, Object>> borrowRepay(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String instId) {
        return ApiResponse.ok(accountService.borrowRepay(limit, instId));
    }

    /** 订单中心策略 tab 的本地聚合（累计投入 / 收益 / 仓位数 / 订单数）。 */
    @GetMapping("/strategy-center")
    public ApiResponse<Map<String, Object>> strategyCenter(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return ApiResponse.ok(accountService.strategyCenter(state, limit, offset));
    }

    /** 停止/启用策略：只改本地状态，不碰交易所。 */
    @PostMapping("/strategy-center/{id}/status")
    public ApiResponse<Map<String, Object>> strategyCenterStatus(
            @PathVariable long id,
            @RequestBody(required = false) StatusRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return ApiResponse.ok(accountService.setStrategyStatus(id, request.status()));
    }

    /** POST /strategy-center/{id}/status 的请求体。 */
    public record StatusRequest(String status) {
    }
}
