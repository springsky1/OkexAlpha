package com.okexalpha.service.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.okexalpha.client.OkxApiClient;
import com.okexalpha.repository.InstrumentRepository;

/**
 * TopVolumeSelector：24h USD 成交额（volCcy24h × last）降序挑 live SWAP。
 *
 * <p>纯单元测试：行情客户端与品种库全部 mock，不启 Spring、不联网。</p>
 */
class TopVolumeSelectorTest {

    private OkxApiClient client;
    private InstrumentRepository instrumentRepository;
    private TopVolumeSelector selector;

    @BeforeEach
    void setUp() {
        client = mock(OkxApiClient.class);
        instrumentRepository = mock(InstrumentRepository.class);
        selector = new TopVolumeSelector(client, instrumentRepository);
        when(instrumentRepository.findInstIds(eq("SWAP"), any(), anyInt())).thenReturn(List.of());
    }

    @Test
    @DisplayName("按 volCcy24h×last 降序排名，且只保留库内 live 的品种")
    void ranksByUsdVolumeDescAndKeepsOnlyLiveSwaps() {
        when(instrumentRepository.findInstIds(eq("SWAP"), any(), anyInt()))
                .thenReturn(List.of("AAA-USDT-SWAP", "BBB-USDT-SWAP", "CCC-USDT-SWAP"));
        when(client.getTickers("SWAP")).thenReturn(List.of(
                ticker("BBB-USDT-SWAP", "2", "3"),      // 6 USD
                ticker("AAA-USDT-SWAP", "10", "1"),     // 10 USD
                ticker("CCC-USDT-SWAP", "5", "1"),      // 5 USD，但不在 live 集合
                ticker("DDD-USDT-SWAP", "999", "999"))); // 成交额最高，但不在 live 集合

        assertThat(selector.select(2)).containsExactly("AAA-USDT-SWAP", "BBB-USDT-SWAP");
        assertThat(selector.select(1)).containsExactly("AAA-USDT-SWAP");
    }

    @Test
    @DisplayName("volCcy24h/last 缺失或非数字的条目剔除，全部不可用返回空列表")
    void dropsEntriesWithMissingOrUnparseableFields() {
        when(instrumentRepository.findInstIds(eq("SWAP"), any(), anyInt()))
                .thenReturn(List.of("AAA-USDT-SWAP", "BBB-USDT-SWAP", "CCC-USDT-SWAP"));
        when(client.getTickers("SWAP")).thenReturn(List.of(
                ticker("AAA-USDT-SWAP", "", "1"),        // volCcy24h 为空
                ticker("BBB-USDT-SWAP", "1", null),      // last 缺字段
                ticker("CCC-USDT-SWAP", "abc", "2")));   // volCcy24h 非数字

        assertThat(selector.select(3)).isEmpty();
    }

    @Test
    @DisplayName("成交额并列时按 instId 升序，保证选择结果确定")
    void tieBreaksDeterministicallyByInstId() {
        when(instrumentRepository.findInstIds(eq("SWAP"), any(), anyInt()))
                .thenReturn(List.of("ZZZ-USDT-SWAP", "AAA-USDT-SWAP"));
        when(client.getTickers("SWAP")).thenReturn(List.of(
                ticker("ZZZ-USDT-SWAP", "5", "2"),       // 10 USD
                ticker("AAA-USDT-SWAP", "10", "1")));    // 10 USD

        assertThat(selector.select(2)).containsExactly("AAA-USDT-SWAP", "ZZZ-USDT-SWAP");
    }

    @Test
    @DisplayName("行情接口失败直接向上抛，由调用方决定退路")
    void propagatesClientFailures() {
        when(client.getTickers("SWAP")).thenThrow(new IllegalStateException("tickers down"));

        assertThatThrownBy(() -> selector.select(20)).isInstanceOf(IllegalStateException.class)
                .hasMessage("tickers down");
    }

    /** last 传 null 表示构造出的行情 JSON 里没有该字段。 */
    private static JsonNode ticker(String instId, String volCcy24h, String last) {
        StringBuilder json = new StringBuilder("{\"instId\":\"").append(instId).append("\"");
        if (volCcy24h != null) {
            json.append(",\"volCcy24h\":\"").append(volCcy24h).append("\"");
        }
        if (last != null) {
            json.append(",\"last\":\"").append(last).append("\"");
        }
        json.append("}");
        return JsonMapper.builder().build().readTree(json.toString());
    }
}
