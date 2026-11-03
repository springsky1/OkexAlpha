package com.okexalpha.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.service.BasicSyncService;
import com.okexalpha.service.InstrumentSyncService;
import com.okexalpha.service.StatusService;
import com.okexalpha.service.backfill.IncrementalSyncService;
import com.okexalpha.web.dto.ApiResponse;
import com.okexalpha.web.dto.SyncRequests.BasicSyncRequest;
import com.okexalpha.web.dto.SyncRequests.InstrumentSyncRequest;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final InstrumentSyncService instrumentSyncService;
    private final BasicSyncService basicSyncService;
    private final StatusService statusService;
    private final IncrementalSyncService incrementalSyncService;

    public SyncController(InstrumentSyncService instrumentSyncService, BasicSyncService basicSyncService,
            StatusService statusService, IncrementalSyncService incrementalSyncService) {
        this.instrumentSyncService = instrumentSyncService;
        this.basicSyncService = basicSyncService;
        this.statusService = statusService;
        this.incrementalSyncService = incrementalSyncService;
    }

    @PostMapping("/instruments")
    public ApiResponse<InstrumentSyncService.SyncResult> syncInstruments(
            @RequestBody(required = false) InstrumentSyncRequest request) {
        InstrumentSyncRequest body = request == null
                ? new InstrumentSyncRequest(null, null, null)
                : request;
        return ApiResponse.ok(instrumentSyncService.sync(body.instTypes(), body.includeOptions(), body.families()));
    }

    @PostMapping("/basic")
    public ApiResponse<BasicSyncService.BasicSyncResult> syncBasic(
            @RequestBody(required = false) BasicSyncRequest request) {
        BasicSyncRequest body = request == null
                ? new BasicSyncRequest(null, null, null, null, null, null, null, null, null, null, null, null)
                : request;
        return ApiResponse.ok(basicSyncService.sync(body));
    }

    @GetMapping("/status")
    public ApiResponse<StatusService.StatusSnapshot> status() {
        return ApiResponse.ok(statusService.snapshot());
    }

    /** 定时增量补数据的配置与水位概览（不写库，前端卡片用）。 */
    @GetMapping("/incremental")
    public ApiResponse<IncrementalSyncService.Status> incrementalStatus() {
        return ApiResponse.ok(incrementalSyncService.status());
    }

    /** 手动触发一轮增量补数据（与定时任务共用同一套防重入与跳过规则）。 */
    @PostMapping("/incremental/run")
    public ApiResponse<IncrementalSyncService.RunResult> runIncremental() {
        return ApiResponse.ok(incrementalSyncService.runOnce());
    }
}
