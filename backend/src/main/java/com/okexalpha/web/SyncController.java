package com.okexalpha.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.service.BasicSyncService;
import com.okexalpha.service.InstrumentSyncService;
import com.okexalpha.service.StatusService;
import com.okexalpha.web.dto.ApiResponse;
import com.okexalpha.web.dto.SyncRequests.BasicSyncRequest;
import com.okexalpha.web.dto.SyncRequests.InstrumentSyncRequest;

@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    private final InstrumentSyncService instrumentSyncService;
    private final BasicSyncService basicSyncService;
    private final StatusService statusService;

    public SyncController(InstrumentSyncService instrumentSyncService, BasicSyncService basicSyncService,
            StatusService statusService) {
        this.instrumentSyncService = instrumentSyncService;
        this.basicSyncService = basicSyncService;
        this.statusService = statusService;
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
}
