package com.okexalpha.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.okexalpha.domain.InstrumentRow;
import com.okexalpha.error.NotFoundException;
import com.okexalpha.repository.InstrumentRepository;

@Service
public class InstrumentQueryService {

    private final InstrumentRepository instrumentRepository;

    public InstrumentQueryService(InstrumentRepository instrumentRepository) {
        this.instrumentRepository = instrumentRepository;
    }

    public List<InstrumentRow> list(String instType, String baseCcy, String state, int limit, int offset) {
        return instrumentRepository.findAll(instType, baseCcy, state, limit, offset);
    }

    public InstrumentRow get(String instId) {
        InstrumentRow row = instrumentRepository.findById(instId);
        if (row == null) {
            throw new NotFoundException("instrument not found: " + instId);
        }
        return row;
    }

    public long count() {
        return instrumentRepository.count();
    }
}
