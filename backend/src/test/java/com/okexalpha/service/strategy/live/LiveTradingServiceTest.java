package com.okexalpha.service.strategy.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.okexalpha.client.OkxTradeClient;
import com.okexalpha.config.OkxProperties;
import com.okexalpha.error.BadRequestException;
import com.okexalpha.error.UnprocessableException;
import com.okexalpha.repository.ShadowRepository;
import com.okexalpha.security.OkxCredentials;
import com.okexalpha.service.strategy.shadow.ShadowEngine;
import com.okexalpha.web.dto.LiveLeverageRequest;
import com.okexalpha.web.dto.LiveOrderRequest;

/** 门禁不通过时绝不能触网；REAL 模式必须二次确认。 */
class LiveTradingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private OkxTradeClient tradeClient;
    private ShadowRepository repository;
    private ShadowEngine shadowEngine;

    @BeforeEach
    void setUp() {
        tradeClient = mock(OkxTradeClient.class);
        repository = mock(ShadowRepository.class);
        shadowEngine = mock(ShadowEngine.class);
        given(repository.countOpenPositions()).willReturn(0L);
        given(repository.filledNotionalSince(anyLong())).willReturn(0d);
        given(repository.latestHealthFailCount()).willReturn(0L);
        given(repository.countRunningBackfills()).willReturn(0L);
        given(repository.latestKlineTs("1m")).willReturn(NOW.toEpochMilli());
        given(repository.apiOutcomeCounts(10)).willReturn(new long[] {10L, 0L});
        given(shadowEngine.lastPrice("BTC-USDT-SWAP")).willReturn(80_000d);
    }

    @Test
    @DisplayName("SHADOW 模式下单直接 422，且不发任何网络请求")
    void shadowModeIsBlockedWithoutNetwork() {
        LiveTradingService service = service(OkxProperties.defaults().strategy().trading());
        assertThatThrownBy(() -> service.placeOrder(new LiveOrderRequest("BTC-USDT-SWAP", "buy", "long", 1d, "market", null,
                "isolated", false, null)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("MODE:");
        verify(tradeClient, never()).placeOrder(any());
    }

    @Test
    @DisplayName("REAL 模式缺少确认口令时拒绝，且不下单")
    void realModeRequiresConfirm() {
        LiveTradingService service = service(new OkxProperties.Strategy.Trading(true, "REAL", "k", "s", "p", 0.0005d,
                0.0002d, 2d, 50, 100d, 500d, new OkxProperties.Strategy.Trading.DailyBatch(false, "0 30 8 * * *")));
        assertThatThrownBy(() -> service.placeOrder(new LiveOrderRequest("BTC-USDT-SWAP", "buy", "long", 1d, "market", null,
                "isolated", false, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("CONFIRM-REAL-TRADING");
        verify(tradeClient, never()).placeOrder(any());
    }

    @Test
    @DisplayName("aa 参数校验：sz<=0 与非法 side 先被拒")
    void validatesRequestBeforeGate() {
        LiveTradingService service = service(OkxProperties.defaults().strategy().trading());
        assertThatThrownBy(() -> service.placeOrder(new LiveOrderRequest("BTC-USDT-SWAP", "hold", null, 1d, "market", null,
                "isolated", false, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("side");
        assertThatThrownBy(() -> service.placeOrder(new LiveOrderRequest("BTC-USDT-SWAP", "buy", null, 0d, "market", null,
                "isolated", false, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("sz");
        verify(tradeClient, never()).placeOrder(any());
    }

    @Test
    @DisplayName("账户快照：未配密钥时 configured=false，来源 none")
    void accountWithoutKeys() {
        LiveTradingService service = service(OkxProperties.defaults().strategy().trading());
        var account = service.account();
        assertThat(account).containsEntry("configured", false).containsEntry("credentialSource", "none");
        assertThat(account.get("reason").toString()).contains("未配置");
        verify(tradeClient, never()).balance();
        verify(tradeClient, never()).positions("SWAP");
    }

    @Test
    @DisplayName("账户快照：快照里有凭证才去查交易所，并报来源")
    void accountWithVaultCredentials() {
        OkxCredentials vaultBacked = OkxCredentials.of("AAAABBBB-CCCC-DDDD-EEEE-FFFF00001111",
                "0123456789abcdef0123456789abcdef", "passphrase-123");
        LiveTradingService service = service(OkxProperties.defaults().strategy().trading(), vaultBacked);
        given(tradeClient.balance()).willReturn(java.util.List.of());
        given(tradeClient.positions("SWAP")).willReturn(java.util.List.of());

        var account = service.account();

        assertThat(account).containsEntry("configured", true).containsEntry("credentialSource", "env");
        verify(tradeClient).balance();
        verify(tradeClient).positions("SWAP");
    }

    @Test
    @DisplayName("门禁通过时把 posSide 写进下单请求体（long_short_mode 必需），留空则不下发")
    void passesPosSideToExchange() {
        TradingGate gate = mock(TradingGate.class);
        given(gate.evaluate()).willReturn(new GateReport("REAL", true, java.util.List.of(), java.util.List.of(),
                java.util.Map.of()));
        OkxProperties props = TradingGateTest.propsWithTrading(OkxProperties.defaults().strategy().trading());
        OkxCredentials credentials = TradingGateTest.credentialsOf(props.strategy().trading());
        given(tradeClient.placeOrder(any())).willReturn(java.util.List.of());
        LiveTradingService service = new LiveTradingService(props, credentials, gate, tradeClient, repository,
                shadowEngine);

        service.placeOrder(new LiveOrderRequest("BTC-USDT-SWAP", "sell", "short", 1d, "market", null,
                "isolated", false, "CONFIRM-REAL-TRADING"));

        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(tradeClient).placeOrder(captor.capture());
        assertThat(captor.getValue()).containsEntry("posSide", "short").containsEntry("side", "sell");

        service.placeOrder(new LiveOrderRequest("BTC-USDT-SWAP", "buy", null, 1d, "market", null,
                "isolated", false, "CONFIRM-REAL-TRADING"));
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor2 =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(tradeClient, org.mockito.Mockito.times(2)).placeOrder(captor2.capture());
        assertThat(captor2.getAllValues().get(1)).doesNotContainKey("posSide");
    }

    @Test
    @DisplayName("杠杆调整：门禁通过后把 instId/lever/mgnMode 原样下发")
    void setsLeverageThroughGate() {
        TradingGate gate = mock(TradingGate.class);
        given(gate.evaluate()).willReturn(new GateReport("REAL", true, java.util.List.of(), java.util.List.of(),
                java.util.Map.of()));
        OkxProperties props = TradingGateTest.propsWithTrading(OkxProperties.defaults().strategy().trading());
        OkxCredentials credentials = TradingGateTest.credentialsOf(props.strategy().trading());
        given(tradeClient.setLeverage("BCH-USDT-SWAP", "5", "isolated", "short")).willReturn(java.util.List.of());
        LiveTradingService service = new LiveTradingService(props, credentials, gate, tradeClient, repository,
                shadowEngine);

        var out = service.setLeverage(new LiveLeverageRequest("BCH-USDT-SWAP", "5", null, "short",
                "CONFIRM-REAL-TRADING"));

        assertThat(out).containsEntry("instId", "BCH-USDT-SWAP").containsEntry("lever", "5")
                .containsEntry("mgnMode", "isolated").containsEntry("posSide", "short").containsEntry("accepted", true);
        verify(tradeClient).setLeverage("BCH-USDT-SWAP", "5", "isolated", "short");
    }

    @Test
    @DisplayName("杠杆调整：REAL 模式缺确认口令时 400，且不触网")
    void setLeverageRequiresConfirmInRealMode() {
        TradingGate gate = mock(TradingGate.class);
        given(gate.evaluate()).willReturn(new GateReport("REAL", true, java.util.List.of(), java.util.List.of(),
                java.util.Map.of()));
        OkxProperties props = TradingGateTest.propsWithTrading(OkxProperties.defaults().strategy().trading());
        OkxCredentials credentials = TradingGateTest.credentialsOf(props.strategy().trading());
        LiveTradingService service = new LiveTradingService(props, credentials, gate, tradeClient, repository,
                shadowEngine);

        assertThatThrownBy(() -> service.setLeverage(new LiveLeverageRequest("BCH-USDT-SWAP", "5", null, "short", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("CONFIRM-REAL-TRADING");
        verify(tradeClient, never()).setLeverage(any(), any(), any(), any());
    }

    private LiveTradingService service(OkxProperties.Strategy.Trading trading) {
        return service(trading, TradingGateTest.credentialsOf(trading));
    }

    private LiveTradingService service(OkxProperties.Strategy.Trading trading, OkxCredentials credentials) {
        OkxProperties props = TradingGateTest.propsWithTrading(trading);
        TradingGate gate = new TradingGate(props, credentials, repository, Clock.fixed(NOW, ZoneOffset.UTC),
                TradingGateTest.availableAccount());
        return new LiveTradingService(props, credentials, gate, tradeClient, repository, shadowEngine);
    }
}
