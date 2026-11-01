package com.okexalpha.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.domain.BackfillJobRow;
import com.okexalpha.service.backfill.BackfillService;
import com.okexalpha.service.backfill.FundingBackfillRequest;
import com.okexalpha.service.backfill.KlineBackfillRequest;
import com.okexalpha.service.backfill.MarketStatsBackfillRequest;
import com.okexalpha.service.backfill.RatioBackfillRequest;
import com.okexalpha.web.dto.ApiResponse;
import com.okexalpha.web.dto.PageArgs;

@RestController
@RequestMapping("/api/v1/backfill")
public class BackfillController {

    private final BackfillService backfillService;

    public BackfillController(BackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @PostMapping("/klines")
    public ResponseEntity<ApiResponse<BackfillService.JobSubmission>> klines(
            @RequestBody KlineBackfillRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(backfillService.submitKlines(request)));
    }

    @PostMapping("/funding-rates")
    public ResponseEntity<ApiResponse<BackfillService.JobSubmission>> fundingRates(
            @RequestBody FundingBackfillRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(backfillService.submitFundingRate(request)));
    }

    @PostMapping("/ratios")
    public ResponseEntity<ApiResponse<BackfillService.JobSubmission>> ratios(
            @RequestBody RatioBackfillRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(backfillService.submitRatios(request)));
    }

    @PostMapping("/market-stats")
    public ResponseEntity<ApiResponse<BackfillService.JobSubmission>> marketStats(
            @RequestBody MarketStatsBackfillRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(backfillService.submitMarketStats(request)));
    }

    @GetMapping("/jobs")
    public ApiResponse<List<BackfillJobRow>> jobs(
            @RequestParam(required = false) String dataType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        PageArgs page = PageArgs.of(limit, offset, 50, 500);
        return ApiResponse.ok(backfillService.listJobs(dataType, status, page.limit(), page.offset()));
    }

    @GetMapping("/jobs/{id}")
    public ApiResponse<JobDetail> job(@PathVariable long id,
            @RequestParam(required = false, defaultValue = "false") boolean includeTasks) {
        BackfillJobRow job = backfillService.getJob(id);
        List<BackfillService.BackfillTaskRowView> tasks = includeTasks ? backfillService.listTasks(id) : null;
        return ApiResponse.ok(new JobDetail(job, tasks));
    }

    @PostMapping("/jobs/{id}/pause")
    public ApiResponse<BackfillJobRow> pause(@PathVariable long id) {
        return ApiResponse.ok(backfillService.pause(id));
    }

    @PostMapping("/jobs/{id}/resume")
    public ApiResponse<BackfillJobRow> resume(@PathVariable long id) {
        return ApiResponse.ok(backfillService.resume(id));
    }

    @PostMapping("/jobs/{id}/cancel")
    public ApiResponse<BackfillJobRow> cancel(@PathVariable long id) {
        return ApiResponse.ok(backfillService.cancel(id));
    }

    public record JobDetail(BackfillJobRow job, List<BackfillService.BackfillTaskRowView> tasks) {
    }
}
