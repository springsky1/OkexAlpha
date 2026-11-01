package com.okexalpha.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.okexalpha.repository.CoinRepository;
import com.okexalpha.repository.CoinRepository.CoinRecord;

@Service
public class CoinService {

    private final CoinRepository coinRepository;

    public CoinService(CoinRepository coinRepository) {
        this.coinRepository = coinRepository;
    }

    public List<CoinRecord> list(String channel, String query, int limit, int offset) {
        return coinRepository.findAll(channel, query, limit, offset);
    }

    public long count() {
        return coinRepository.count();
    }
}
