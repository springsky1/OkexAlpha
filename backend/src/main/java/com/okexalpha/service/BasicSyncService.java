package com.okexalpha.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.okexalpha.domain.InstType;
import com.okexalpha.error.ConflictException;
import com.okexalpha.repository.InstrumentRepository;
import com.okexalpha.service.backfill.BackfillService;
import com.okexalpha.service.backfill.FundingBackfillRequest;
import com.okexalpha.service.backfill.KlineBackfillRequest;
import com.okexalpha.service.backfill.RatioBackfillRequest;
import com.okexalpha.web.dto.SyncRequests.BasicSyncRequest;

/** POST /sync/basic：先同步 instruments，再为头部品种建 K 线/资金费率/多空比补拉任务。 */
@Service
public class BasicSyncService {

    private static final Logger log = LoggerFactory.getLogger(BasicSyncService.class);
    private static final long DAY_MS = 86_400_000L;

    private final InstrumentSyncService instrumentSyncService;
    private final InstrumentRepository instrumentRepository;
    private final BackfillService backfillService;

    public BasicSyncService(InstrumentSyncService instrumentSyncService,
            InstrumentRepository instrumentRepository, BackfillService backfillService) {
        this.instrumentSyncService = instrumentSyncService;
        this.instrumentRepository = instrumentRepository;
        this.backfillService = backfillService;
    }

    public record BasicSyncResult(InstrumentSyncService.SyncResult instrumentSync, Map<String, Object> jobs) {
    }

    public BasicSyncResult sync(BasicSyncRequest request) {
        InstrumentSyncService.SyncResult instrumentSync = instrumentSyncService.sync(request.instTypes(),
                request.includeOptions(), request.families());

        String instTypeCode = request.instType() == null || request.instType().isBlank() ? "SWAP"
                : request.instType();
        InstType instType = InstType.fromCode(instTypeCode);
        int maxInstruments = clamp(request.maxInstruments(), 20, 1, 500);
        int maxCcys = clamp(request.maxCcys(), 10, 1, 100);
        int days = clamp(request.days(), 7, 1, 365);
        boolean withFunding = request.includeFunding() == null || request.includeFunding();
        boolean withRatios = request.includeRatios() == null || request.includeRatios();

        long endMs = System.currentTimeMillis();
        long startMs = endMs - days * DAY_MS;
        List<String> instIds = instrumentRepository.findInstIds(instType.code(), null, maxInstruments);

        Map<String, Object> jobs = new LinkedHashMap<>();
        if (!instIds.isEmpty() && instType != InstType.SWAP) {
            jobs.put("klines", "skipped: klines only support perpetual swap instruments (instType=SWAP)");
        } else if (!instIds.isEmpty()) {
            List<String> bars = request.bars() == null || request.bars().isEmpty() ? List.of("1H")
                    : request.bars();
            List<String> klineTypes = request.klineTypes() == null || request.klineTypes().isEmpty()
                    ? List.of("CANDLE", "MARK")
                    : request.klineTypes();
            submit(jobs, "klines", () -> backfillService
                    .submitKlines(new KlineBackfillRequest(instIds, null, null, bars, klineTypes, startMs, endMs,
                            null))
                    .jobId());
        }

        if (withFunding) {
            if (instType.isDerivative()) {
                submit(jobs, "fundingRates", () -> backfillService
                        .submitFundingRate(new FundingBackfillRequest(instIds, null, startMs, endMs)).jobId());
            } else {
                jobs.put("fundingRates", "skipped: funding rate only applies to derivatives");
            }
        }

        if (withRatios && instType == InstType.SWAP) {
            List<String> ccys = instrumentRepository.distinctBaseCcys(instType.code(), maxCcys);
            List<String> ratioTypes = request.ratioTypes() == null || request.ratioTypes().isEmpty()
                    ? List.of("LSR_ACCOUNT_CCY", "TOP_TRADER_POSITION")
                    : request.ratioTypes();
            List<String> scopes = new ArrayList<>(instIds.subList(0, Math.min(10, instIds.size())));
            submit(jobs, "ratios", () -> backfillService.submitRatios(new RatioBackfillRequest(ratioTypes, scopes,
                    ccys, List.of("1H"), startMs, endMs)).jobId());
        } else if (withRatios) {
            jobs.put("ratios", "skipped: long-short ratios only cover perpetual swap contracts (instType=SWAP)");
        }

        log.info("basic sync done: jobs={}", jobs);
        return new BasicSyncResult(instrumentSync, jobs);
    }

    private void submit(Map<String, Object> jobs, String key, JobIdSupplier supplier) {
        try {
            jobs.put(key, supplier.get());
        } catch (ConflictException ex) {
            jobs.put(key, "skipped: " + ex.getMessage());
        }
    }

    private int clamp(Integer value, int fallback, int min, int max) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }

    @FunctionalInterface
    private interface JobIdSupplier {
        long get();
    }
}
