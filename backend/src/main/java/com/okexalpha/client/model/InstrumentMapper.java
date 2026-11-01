package com.okexalpha.client.model;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.okexalpha.domain.InstrumentRow;

/** instruments 的 data 元素 -> okx_instrument 行（保留原始 JSON 到 raw 列）。 */
@Component
public class InstrumentMapper {

    private final ObjectMapper objectMapper;

    public InstrumentMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public InstrumentRow toRow(JsonNode node) {
        OkxInstrument dto;
        try {
            dto = objectMapper.treeToValue(node, OkxInstrument.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("cannot parse instrument element: " + node, ex);
        }
        if (dto.instId() == null || dto.instId().isBlank()) {
            throw new IllegalArgumentException("instrument element without instId: " + node);
        }
        if (dto.instType() == null || dto.instType().isBlank()) {
            throw new IllegalArgumentException("instrument element without instType: " + node);
        }
        return new InstrumentRow(
                dto.instId(),
                dto.instType(),
                dto.instFamily(),
                dto.uly(),
                dto.baseCcy(),
                dto.quoteCcy(),
                dto.settleCcy(),
                dto.ctType(),
                dto.ctVal(),
                dto.ctValCcy(),
                dto.ctMult(),
                dto.lever(),
                dto.tickSz(),
                dto.lotSz(),
                dto.minSz(),
                dto.listTime(),
                dto.expTime(),
                dto.state(),
                dto.ruleType(),
                dto.category(),
                dto.instCategory(),
                dto.optType(),
                dto.alias(),
                node.toString());
    }
}
