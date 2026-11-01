package com.okexalpha.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.okexalpha.domain.OpenInterestRow;
import com.okexalpha.domain.OpenInterestVolumeRow;
import com.okexalpha.domain.RatioRow;
import com.okexalpha.domain.TakerVolumeRow;
import com.okexalpha.repository.StatisticsRepository;

@Service
public class StatisticsQueryService {

    private final StatisticsRepository statisticsRepository;

    public StatisticsQueryService(StatisticsRepository statisticsRepository) {
        this.statisticsRepository = statisticsRepository;
    }

    public List<RatioRow> ratios(String ratioType, String scope, String period, Long startMs, Long endMs,
            int limit, boolean descending) {
        return statisticsRepository.queryRatios(ratioType, scope, period, startMs, endMs, limit, descending);
    }

    public List<OpenInterestVolumeRow> openInterestVolume(String ccy, String period, Long startMs, Long endMs,
            int limit, boolean descending) {
        return statisticsRepository.queryOpenInterestVolume(ccy, period, startMs, endMs, limit, descending);
    }

    public List<TakerVolumeRow> takerVolume(String scopeKind, String scope, String period, Long startMs,
            Long endMs, int limit, boolean descending) {
        return statisticsRepository.queryTakerVolume(scopeKind, scope, period, startMs, endMs, limit, descending);
    }

    public List<OpenInterestRow> openInterest(String instId, Long startMs, Long endMs, int limit,
            boolean descending) {
        return statisticsRepository.queryOpenInterest(instId, startMs, endMs, limit, descending);
    }
}
