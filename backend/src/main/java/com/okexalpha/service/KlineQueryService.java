package com.okexalpha.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.okexalpha.domain.KlineRow;
import com.okexalpha.repository.KlineRepository;

@Service
public class KlineQueryService {

    private final KlineRepository klineRepository;

    public KlineQueryService(KlineRepository klineRepository) {
        this.klineRepository = klineRepository;
    }

    public List<KlineRow> query(String instId, String bar, String klineType, Long startMs, Long endMs, int limit,
            boolean descending) {
        return klineRepository.query(instId, bar, klineType, startMs, endMs, limit, descending);
    }
}
