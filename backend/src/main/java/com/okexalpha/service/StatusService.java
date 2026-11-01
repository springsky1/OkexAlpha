package com.okexalpha.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.okexalpha.repository.BackfillJobRepository;
import com.okexalpha.repository.CoinRepository;
import com.okexalpha.repository.FundingRateRepository;
import com.okexalpha.repository.InstrumentRepository;
import com.okexalpha.repository.KlineRepository;
import com.okexalpha.repository.StatisticsRepository;
import com.okexalpha.repository.SyncCursorRepository;

/** 数据面/补拉面的整体状态概览。 */
@Service
public class StatusService {

    private final InstrumentRepository instrumentRepository;
    private final CoinRepository coinRepository;
    private final KlineRepository klineRepository;
    private final FundingRateRepository fundingRateRepository;
    private final StatisticsRepository statisticsRepository;
    private final BackfillJobRepository backfillJobRepository;
    private final SyncCursorRepository syncCursorRepository;

    public StatusService(InstrumentRepository instrumentRepository, CoinRepository coinRepository,
            KlineRepository klineRepository, FundingRateRepository fundingRateRepository,
            StatisticsRepository statisticsRepository, BackfillJobRepository backfillJobRepository,
            SyncCursorRepository syncCursorRepository) {
        this.instrumentRepository = instrumentRepository;
        this.coinRepository = coinRepository;
        this.klineRepository = klineRepository;
        this.fundingRateRepository = fundingRateRepository;
        this.statisticsRepository = statisticsRepository;
        this.backfillJobRepository = backfillJobRepository;
        this.syncCursorRepository = syncCursorRepository;
    }

    public record StatusSnapshot(Map<String, Long> rows, List<Map<String, Object>> instrumentsByType,
            List<Map<String, Object>> backfillJobs, Map<String, Long> cursors) {
    }

    public StatusSnapshot snapshot() {
        Map<String, Long> rows = new LinkedHashMap<>();
        rows.put("instruments", instrumentRepository.count());
        rows.put("coins", coinRepository.count());
        rows.put("klines", klineRepository.count());
        rows.put("fundingRates", fundingRateRepository.count());
        rows.put("longShortRatios", statisticsRepository.countRatios());
        rows.put("openInterestVolume", statisticsRepository.countOpenInterestVolume());
        rows.put("takerVolume", statisticsRepository.countTakerVolume());
        rows.put("openInterest", statisticsRepository.countOpenInterest());
        return new StatusSnapshot(rows, instrumentRepository.countByInstType(),
                backfillJobRepository.statusSummary(), syncCursorRepository.all());
    }
}
