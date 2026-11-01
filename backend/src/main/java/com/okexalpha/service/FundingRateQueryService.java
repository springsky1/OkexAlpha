package com.okexalpha.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.okexalpha.domain.FundingRateRow;
import com.okexalpha.repository.FundingRateRepository;

@Service
public class FundingRateQueryService {

    private final FundingRateRepository fundingRateRepository;

    public FundingRateQueryService(FundingRateRepository fundingRateRepository) {
        this.fundingRateRepository = fundingRateRepository;
    }

    public List<FundingRateRow> query(String instId, Long startMs, Long endMs, int limit, boolean descending) {
        return fundingRateRepository.query(instId, startMs, endMs, limit, descending);
    }
}
