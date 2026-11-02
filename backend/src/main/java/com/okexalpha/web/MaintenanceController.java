package com.okexalpha.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.okexalpha.service.storage.StorageReport;
import com.okexalpha.service.storage.StorageService;
import com.okexalpha.web.dto.ApiResponse;

/** 运维只读接口：存储/压缩状况。 */
@RestController
@RequestMapping("/api/v1/maintenance")
public class MaintenanceController {

    private final StorageService storageService;

    public MaintenanceController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping("/storage")
    public ApiResponse<StorageReport> storage() {
        return ApiResponse.ok(storageService.report());
    }
}
