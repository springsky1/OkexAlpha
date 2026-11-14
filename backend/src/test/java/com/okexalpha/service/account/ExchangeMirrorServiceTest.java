package com.okexalpha.service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.okexalpha.client.OkxApiException;
import com.okexalpha.client.OkxTradeClient;
import com.okexalpha.repository.ExchangeMirrorRepository;
import com.okexalpha.security.OkxCredentials;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 订单中心镜像同步：抓取交易所数据落本地、失败不破坏已有镜像、未配置凭证短路。 */
class ExchangeMirrorServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OkxTradeClient tradeClient;
    private ExchangeMirrorRepository repository;

    @BeforeEach
    void setUp() {
        tradeClient = mock(OkxTradeClient.class);
        repository = mock(ExchangeMirrorRepository.class);
    }

    @Test
    @DisplayName("一次同步把挂单/历史委托/已平仓仓位/成交都 UPSERT 进本地，并回报数量")
    void syncStoresEverythingAndReportsCounts() {
        given(tradeClient.pendingOrders("SWAP")).willReturn(List.of(json("""
                {"ordId":"LIVE1","instId":"BTC-USDT-SWAP","instType":"SWAP","side":"buy","posSide":"long",
                 "ordType":"limit","sz":"1","px":"70000","accFillSz":"0","state":"live","cTime":"1700000000000",
                 "uTime":"1700000100000"}
                """)));
        given(tradeClient.ordersHistory("SWAP", null, 100)).willReturn(List.of(
                json("""
                        {"ordId":"F1","instId":"BTC-USDT-SWAP","instType":"SWAP","side":"sell","posSide":"long",
                         "ordType":"market","sz":"1","avgPx":"70100","accFillSz":"1","state":"filled","fee":"-0.14",
                         "pnl":"2.4","cTime":"1700000000000","uTime":"1700000200000"}
                        """),
                json("""
                        {"ordId":"F2","instId":"ETH-USDT-SWAP","instType":"SWAP","side":"buy","posSide":"short",
                         "ordType":"market","sz":"2","avgPx":"3100","accFillSz":"2","state":"filled","fee":"-0.2",
                         "pnl":"-1.1","cTime":"1700000000000","uTime":"1700000300000"}
                        """)));
        given(tradeClient.positionsHistory("SWAP", 100)).willReturn(List.of(json("""
                {"instId":"ETH-USDT-SWAP","instType":"SWAP","mgnMode":"cross","posSide":"short","type":"2",
                 "lever":"3","openAvgPx":"3200","closeAvgPx":"3100","pnl":"-12.5","pnlRatio":"-0.1",
                 "realizedPnl":"-12.5","fee":"-0.2","fundingFee":"0.05","cTime":"1700000000000","uTime":"1700000100000"}
                """)));
        given(tradeClient.fills("SWAP", 100)).willReturn(List.of(json("""
                {"tradeId":"T1","ordId":"F1","instId":"BTC-USDT-SWAP","instType":"SWAP","side":"sell",
                 "posSide":"long","fillSz":"1","fillPx":"70100","fillTime":"1700000200000","fee":"-0.14","pnl":"2.4"}
                """)));

        Map<String, Object> out = service(configured()).sync("SWAP", 100, false);

        assertThat(out).containsEntry("ok", true).containsEntry("instType", "SWAP").containsEntry("limit", 100);
        Map<String, Object> fetched = map(out.get("fetched"));
        assertThat(fetched).containsEntry("pending", 1).containsEntry("history", 2)
                .containsEntry("positions", 1).containsEntry("fills", 1);
        Map<String, Object> stored = map(out.get("stored"));
        assertThat(stored).containsEntry("orders", 3).containsEntry("positions", 1).containsEntry("fills", 1);
        verify(repository).upsertOrders(org.mockito.ArgumentMatchers.argThat(
                rows -> ((java.util.List<?>) rows).size() == 3));
        verify(repository).upsertPositions(org.mockito.ArgumentMatchers.argThat(
                rows -> ((java.util.List<?>) rows).size() == 1));
        verify(repository).upsertFills(org.mockito.ArgumentMatchers.argThat(
                rows -> ((java.util.List<?>) rows).size() == 1));
        // 本次挂单快照里只有 LIVE1，其余 live 行要清掉，避免假挂单
        verify(repository).deleteStaleLiveOrders(Set.of("LIVE1"));
    }

    @Test
    @DisplayName("历史委托为空时回落到归档接口，并标记 archive=true")
    void fallsBackToArchiveWhenHistoryIsEmpty() {
        given(tradeClient.pendingOrders("SWAP")).willReturn(List.of());
        given(tradeClient.ordersHistory("SWAP", null, 100)).willReturn(List.of());
        given(tradeClient.ordersHistoryArchive("SWAP", null, 100)).willReturn(List.of(json("""
                {"ordId":"OLD1","instId":"BTC-USDT-SWAP","instType":"SWAP","side":"buy","posSide":"long",
                 "ordType":"market","sz":"1","avgPx":"69000","accFillSz":"1","state":"filled","fee":"-0.1",
                 "pnl":"1.2","cTime":"1690000000000","uTime":"1690000100000"}
                """)));
        given(tradeClient.positionsHistory("SWAP", 100)).willReturn(List.of());
        given(tradeClient.fills("SWAP", 100)).willReturn(List.of());

        Map<String, Object> out = service(configured()).sync("SWAP", 100, false);

        assertThat(out).containsEntry("ok", true).containsEntry("archive", true);
        assertThat(map(out.get("fetched"))).containsEntry("history", 1);
        verify(repository).upsertOrders(org.mockito.ArgumentMatchers.argThat(
                rows -> ((java.util.List<?>) rows).size() == 1));
        verify(repository).deleteStaleLiveOrders(Set.of());
    }

    @Test
    @DisplayName("交易所失败时把错误折成 error 块，且完全不碰本地镜像")
    void failureLeavesMirrorUntouched() {
        given(tradeClient.pendingOrders(anyString()))
                .willThrow(new OkxApiException(OkxApiException.Kind.NETWORK, null, 0, "boom", null));

        Map<String, Object> out = service(configured()).sync("SWAP", 100, false);

        assertThat(out).containsEntry("ok", false);
        Map<String, Object> error = map(out.get("error"));
        assertThat(error).containsEntry("kind", "NETWORK");
        assertThat((String) error.get("message")).contains("boom");
        // 失败只允许读一次 lastSyncedAt（finally 里给页面回显），绝不能写镜像表
        verify(repository, never()).upsertOrders(any());
        verify(repository, never()).upsertPositions(any());
        verify(repository, never()).upsertFills(any());
        verify(repository, never()).deleteStaleLiveOrders(any());
        verify(tradeClient, never()).ordersHistory(any(), any(), anyInt());
    }

    @Test
    @DisplayName("未配置凭证时直接短路，不打交易所也不写库")
    void missingCredentialsShortCircuits() {
        Map<String, Object> out = service(OkxCredentials.empty()).sync("SWAP", 100, true);

        assertThat(out).containsEntry("ok", false).containsEntry("configured", false);
        assertThat(map(out.get("error"))).containsEntry("kind", "MISSING_CREDENTIALS");
        verifyNoInteractions(tradeClient);
        verify(repository, never()).upsertOrders(any());
        verify(repository, never()).upsertPositions(any());
        verify(repository, never()).upsertFills(any());
        verify(repository, never()).deleteStaleLiveOrders(any());
    }

    // ------------------------------------------------------------------ helpers

    private ExchangeMirrorService service(OkxCredentials credentials) {
        return new ExchangeMirrorService(tradeClient, repository, credentials, "OkexAlpha");
    }

    private static OkxCredentials configured() {
        return OkxCredentials.of("AAAA1111BBBB2222", "SECRET-KEY-9999", "PASSPHRASE-123456");
    }

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
