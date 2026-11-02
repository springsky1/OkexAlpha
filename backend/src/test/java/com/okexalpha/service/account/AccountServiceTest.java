package com.okexalpha.service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.okexalpha.client.OkxApiException;
import com.okexalpha.client.OkxTradeClient;
import com.okexalpha.client.TransportResponse;
import com.okexalpha.config.OkxProperties;
import com.okexalpha.domain.ShadowOrderRow;
import com.okexalpha.repository.ShadowRepository;
import com.okexalpha.security.OkxCredentials;
import com.okexalpha.security.SecretMasker;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 账户节点：未配置凭证安全降级、凭证脱敏、上游错误折成 error 块、三种订单来源。 */
class AccountServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_KEY = "AAAA1111BBBB2222CCCC3333";
    private static final String SECRET_KEY = "SECRET-KEY-9999-8888-7777";
    private static final String PASSPHRASE = "PASSPHRASE-123456";

    private OkxTradeClient tradeClient;
    private ShadowRepository shadowRepository;

    @BeforeEach
    void setUp() {
        tradeClient = mock(OkxTradeClient.class);
        shadowRepository = mock(ShadowRepository.class);
    }

    // ------------------------------------------------------------ summary

    @Test
    @DisplayName("未配置凭证时 summary 不抛异常、不发请求，只给 notice")
    void summaryWithoutCredentialsIsSafe() {
        AccountService service = service(OkxProperties.defaults(), "OkexAlpha");

        Map<String, Object> out = service.summary();

        assertThat(out).containsEntry("configured", false).containsEntry("mode", "SHADOW");
        assertThat((String) out.get("notice")).contains("未配置完整凭证").contains("OKX_API_KEY");
        assertThat(out).doesNotContainKey("account");
        Map<String, Object> credentials = map(out.get("credentials"));
        assertThat(credentials).containsEntry("apiKey", null).containsEntry("apiKeyConfigured", false)
                .containsEntry("secretKeyConfigured", false).containsEntry("passphraseConfigured", false)
                .containsEntry("source", "none");
        verifyNoInteractions(tradeClient);
    }

    @Test
    @DisplayName("已配置凭证时 summary 返回脱敏 apiKey 与脱敏 uid，且不含 secret/passphrase")
    void summaryMasksCredentialsAndAccount() {
        AccountService service = service(configured(), "OkexAlpha");
        given(tradeClient.accountConfig()).willReturn(List.of(json("""
                {"uid":"1234567890","mainUid":"0987654321","acctLv":"2","posMode":"net_mode",
                 "acctStpMode":"cancel_maker","perm":"read_only","kycLv":"2"}
                """)));

        Map<String, Object> out = service.summary();

        assertThat(out).containsEntry("configured", true).containsEntry("ok", true);
        assertThat((String) out.get("permissionNote")).contains("只读查询");

        Map<String, Object> credentials = map(out.get("credentials"));
        assertThat(credentials).containsEntry("apiKey", "AAAA****3333")
                .containsEntry("apiKeyConfigured", true)
                .containsEntry("secretKeyConfigured", true)
                .containsEntry("passphraseConfigured", true)
                .containsEntry("label", "OkexAlpha");
        assertThat(credentials.keySet()).containsExactly("label", "apiKey", "apiKeyConfigured",
                "secretKeyConfigured", "passphraseConfigured", "source");
        assertThat(credentials.get("source")).isEqualTo("env");

        Map<String, Object> account = map(out.get("account"));
        assertThat(account).containsEntry("uid", "12***90")
                .containsEntry("mainUid", "09***21")
                .containsEntry("acctLv", "2")
                .containsEntry("posMode", "net_mode")
                .containsEntry("perm", "read_only")
                .containsEntry("kycLv", "2")
                .containsEntry("level", null);

        assertThat(out.toString()).doesNotContain(SECRET_KEY).doesNotContain(PASSPHRASE)
                .doesNotContain(API_KEY).doesNotContain("1234567890");
    }

    @Test
    @DisplayName("accountConfig 返回空列表时不写 account 键，但整体仍 ok")
    void summaryWithoutAccountConfigData() {
        AccountService service = service(configured(), "OkexAlpha");
        given(tradeClient.accountConfig()).willReturn(List.of());

        Map<String, Object> out = service.summary();

        assertThat(out).containsEntry("ok", true).doesNotContainKey("account");
        assertThat((String) out.get("permissionNote")).contains("只读查询");
    }

    @Test
    @DisplayName("上游限流折成 error 块（不抛 500），apiKey 仍脱敏")
    void summaryFoldsRateLimitError() {
        AccountService service = service(configured(), "OkexAlpha");
        given(tradeClient.accountConfig()).willThrow(new OkxApiException(OkxApiException.Kind.RATE_LIMITED, "50011",
                429, "Too Many Requests", new TransportResponse(429, "{}")));

        Map<String, Object> out = service.summary();

        assertThat(out).containsEntry("ok", false);
        Map<String, Object> error = map(out.get("error"));
        assertThat(error).containsEntry("kind", "RATE_LIMITED").containsEntry("code", "50011")
                .containsEntry("message", "Too Many Requests");
        assertThat((String) error.get("hint")).contains("限流");
        assertThat(map(out.get("credentials"))).containsEntry("apiKey", "AAAA****3333");
        assertThat(out).doesNotContainKey("account");
    }

    @Test
    @DisplayName("非 OkxApiException 异常折成 INTERNAL，且不带 hint")
    void summaryFoldsUnexpectedRuntimeError() {
        AccountService service = service(configured(), "OkexAlpha");
        given(tradeClient.accountConfig()).willThrow(new IllegalStateException("boom"));

        Map<String, Object> out = service.summary();

        assertThat(out).containsEntry("ok", false);
        Map<String, Object> error = map(out.get("error"));
        assertThat(error).containsEntry("kind", "INTERNAL")
                .containsEntry("code", null)
                .containsEntry("message", "IllegalStateException: boom");
        assertThat(error).doesNotContainKey("hint");
    }

    @Test
    @DisplayName("网络错误映射到 NETWORK 并带网络提示")
    void summaryFoldsNetworkError() {
        AccountService service = service(configured(), "OkexAlpha");
        given(tradeClient.accountConfig()).willThrow(new OkxApiException(OkxApiException.Kind.NETWORK, null, 0,
                "connect timed out", null));

        Map<String, Object> out = service.summary();

        Map<String, Object> error = map(out.get("error"));
        assertThat(error).containsEntry("kind", "NETWORK").containsEntry("code", null);
        assertThat((String) error.get("hint")).contains("网络不可达");
    }

    @Test
    @DisplayName("凭证快照自带 label/source 时优先使用（库内密文的来源与标签）")
    void summaryPrefersSnapshotLabelAndSource() {
        OkxCredentials vaultBacked = new OkxCredentials();
        vaultBacked.apply(new OkxCredentials.Credential(API_KEY, SECRET_KEY, PASSPHRASE, "vault-label",
                OkxCredentials.SOURCE_DB, null));
        AccountService service = service(configured(), vaultBacked, "OkexAlpha");

        Map<String, Object> credentials = map(service.summary().get("credentials"));

        assertThat(credentials).containsEntry("label", "vault-label").containsEntry("source", "db")
                .containsEntry("apiKey", "AAAA****3333");
    }

    @Test
    @DisplayName("keyLabel 为空白串时回落到 OkexAlpha，否则 trim")
    void keyLabelIsNormalized() {
        assertThat((String) map(service(configured(), "   ").summary().get("credentials")).get("label"))
                .isEqualTo("OkexAlpha");
        assertThat((String) map(service(configured(), " 我的Key ").summary().get("credentials")).get("label"))
                .isEqualTo("我的Key");
    }

    // ------------------------------------------------------------ balance

    @Test
    @DisplayName("余额解析总额与币种明细")
    void balanceParsesTotalsAndDetails() {
        AccountService service = service(configured(), "OkexAlpha");
        given(tradeClient.balance()).willReturn(List.of(json("""
                {"totalEq":"12345.67","isoEq":"0","adjEq":"12000.5","availEq":"9000.1","mgnRatio":"3.2",
                 "imr":"1000","mmr":"500","notionalUsd":"20000","uTime":"1735689600000",
                 "details":[
                   {"ccy":"USDT","eq":"10000.5","eqUsd":"10001.5","cashBal":"9999.5","availBal":"8000",
                    "availEq":"8000","frozenBal":"2000","ordFrozen":"100","upl":"12.5","uplLiab":"0","disEq":"10001.5"},
                   {"ccy":"BTC","eq":"0.1"}
                 ]}
                """)));

        Map<String, Object> out = service.balance();

        assertThat(out).containsEntry("ok", true)
                .containsEntry("state", "balance")
                .containsEntry("totalEq", "12345.67")
                .containsEntry("mmr", "500")
                .containsEntry("uTime", "1735689600000");
        List<Map<String, Object>> details = list(out.get("details"));
        assertThat(details).hasSize(2);
        assertThat(details.get(0)).containsEntry("ccy", "USDT").containsEntry("eq", "10000.5")
                .containsEntry("upl", "12.5").containsEntry("disEq", "10001.5");
        assertThat(details.get(1)).containsEntry("ccy", "BTC").containsEntry("cashBal", null);
    }

    @Test
    @DisplayName("余额上游返回空时 totalEq=null、details 空列表")
    void balanceEmptyUpstreamYieldsNullTotal() {
        AccountService service = service(configured(), "OkexAlpha");
        given(tradeClient.balance()).willReturn(List.of());

        Map<String, Object> out = service.balance();

        assertThat(out).containsEntry("totalEq", null).containsEntry("ok", true);
        assertThat(list(out.get("details"))).isEmpty();
    }

    @Test
    @DisplayName("未配置凭证时 balance 不调上游，带 notice")
    void balanceWithoutCredentials() {
        AccountService service = service(OkxProperties.defaults(), "OkexAlpha");

        Map<String, Object> out = service.balance();

        assertThat(out).containsEntry("configured", false);
        assertThat(out).doesNotContainKey("ok");
        assertThat((String) out.get("notice")).contains("OKX_API_KEY");
        verifyNoInteractions(tradeClient);
    }

    // ------------------------------------------------------------ positions

    @Test
    @DisplayName("positions 默认 SWAP 并映射持仓字段")
    void positionsDefaultToSwapAndMapFields() {
        AccountService service = service(configured(), "OkexAlpha");
        given(tradeClient.positions("SWAP")).willReturn(List.of(json("""
                {"instId":"BTC-USDT-SWAP","instType":"SWAP","posSide":"long","pos":"3","availPos":"3",
                 "avgPx":"80000","markPx":"81000","upl":"300","uplRatio":"0.0125","lever":"5",
                 "liqPx":"65000","mgnMode":"isolated","notionalUsd":"2400","margin":"480","mgnRatio":"2.5"}
                """)));

        Map<String, Object> out = service.positions(null);

        assertThat(out).containsEntry("ok", true).containsEntry("instType", "SWAP");
        List<Map<String, Object>> items = list(out.get("items"));
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("instId", "BTC-USDT-SWAP")
                .containsEntry("pos", "3")
                .containsEntry("lever", "5")
                .containsEntry("upl", "300")
                .containsEntry("adl", null);
    }

    @Test
    @DisplayName("positions 归一化 instType（trim + 大写）")
    void positionsNormalizeInstType() {
        AccountService service = service(configured(), "OkexAlpha");
        given(tradeClient.positions("FUTURES")).willReturn(List.of());

        Map<String, Object> out = service.positions(" futures ");

        assertThat(out).containsEntry("instType", "FUTURES");
        assertThat(list(out.get("items"))).isEmpty();
        verify(tradeClient).positions("FUTURES");
    }

    @Test
    @DisplayName("未配置凭证时 positions 不发请求")
    void positionsWithoutCredentials() {
        AccountService service = service(OkxProperties.defaults(), "OkexAlpha");

        Map<String, Object> out = service.positions("SWAP");

        assertThat(out).containsEntry("configured", false).doesNotContainKey("ok");
        assertThat(list(out.get("items"))).isEmpty();
        verifyNoInteractions(tradeClient);
    }

    // ------------------------------------------------------------ orders

    @Test
    @DisplayName("state=local 只读本地影子账本，绝不调交易所")
    void localOrdersUseShadowLedgerOnly() {
        AccountService service = service(configured(), "OkexAlpha");
        OffsetDateTime decidedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        given(shadowRepository.orders(null, "BTC-USDT-SWAP", 10, 0)).willReturn(List.of(new ShadowOrderRow(7L, 3L,
                "BTC-USDT-SWAP", "buy", "market", 0.5d, null, 40000d, "FILLED", "signal-hit", 1735689600000L,
                decidedAt)));

        Map<String, Object> out = service.orders("local", "SWAP", "BTC-USDT-SWAP", 10);

        assertThat(out).containsEntry("ok", true).containsEntry("state", "local").containsEntry("limit", 10);
        List<Map<String, Object>> items = list(out.get("items"));
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("source", "LOCAL")
                .containsEntry("id", 7L)
                .containsEntry("strategyId", 3L)
                .containsEntry("state", "FILLED")
                .containsEntry("px", null)
                .containsEntry("notionalUsd", 40000d)
                .containsEntry("decidedAt", decidedAt.toString());
        verifyNoInteractions(tradeClient);
    }

    @Test
    @DisplayName("state 省略时默认 local")
    void ordersDefaultStateIsLocal() {
        AccountService service = service(configured(), "OkexAlpha");
        given(shadowRepository.orders(null, null, 50, 0)).willReturn(List.of());

        Map<String, Object> out = service.orders(null, null, null, null);

        assertThat(out).containsEntry("state", "local").containsEntry("limit", 50).containsEntry("ok", true);
        verify(shadowRepository).orders(null, null, 50, 0);
        verifyNoInteractions(tradeClient);
    }

    @Test
    @DisplayName("local 分支 limit 上限 200")
    void localOrdersClampLimitTo200() {
        AccountService service = service(configured(), "OkexAlpha");
        given(shadowRepository.orders(null, null, 200, 0)).willReturn(List.of());

        Map<String, Object> out = service.orders("LOCAL", null, null, 1000);

        assertThat(out).containsEntry("limit", 200);
        verify(shadowRepository).orders(null, null, 200, 0);
    }

    @Test
    @DisplayName("local 分支 limit 为 0 时夹取到 1")
    void localOrdersClampZeroLimitToOne() {
        AccountService service = service(configured(), "OkexAlpha");
        given(shadowRepository.orders(null, null, 1, 0)).willReturn(List.of());

        service.orders("local", null, null, 0);

        verify(shadowRepository).orders(null, null, 1, 0);
    }

    @Test
    @DisplayName("state=pending 读交易所当前挂单，limit 夹到 100")
    void pendingOrdersUseTradeClient() {
        AccountService service = service(configured(), "OkexAlpha");
        given(tradeClient.pendingOrders("SWAP")).willReturn(List.of(json("""
                {"ordId":"111","clOrdId":"cli-1","instId":"BTC-USDT-SWAP","instType":"SWAP","tdMode":"isolated",
                 "side":"buy","posSide":"long","ordType":"limit","sz":"1","px":"79000","state":"live"}
                """)));

        Map<String, Object> out = service.orders("pending", "SWAP", null, 5000);

        assertThat(out).containsEntry("ok", true).containsEntry("state", "pending").containsEntry("limit", 100);
        List<Map<String, Object>> items = list(out.get("items"));
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("source", "OKX")
                .containsEntry("ordId", "111")
                .containsEntry("clOrdId", "cli-1")
                .containsEntry("px", "79000")
                .containsEntry("state", "live");
        verify(tradeClient).pendingOrders("SWAP");
        verifyNoInteractions(shadowRepository);
    }

    @Test
    @DisplayName("pending 结果按 limit 截断")
    void pendingOrdersAreTruncatedToLimit() {
        AccountService service = service(configured(), "OkexAlpha");
        given(tradeClient.pendingOrders("SWAP")).willReturn(List.of(order("1"), order("2"), order("3")));

        Map<String, Object> out = service.orders("pending", "SWAP", null, 2);

        assertThat(out).containsEntry("limit", 2);
        assertThat(list(out.get("items"))).extracting(row -> row.get("ordId")).containsExactly("1", "2");
    }

    @Test
    @DisplayName("state=history 读交易所历史订单并透传 instId/limit")
    void historyOrdersUseTradeClient() {
        AccountService service = service(configured(), "OkexAlpha");
        given(tradeClient.ordersHistory("SWAP", "BTC-USDT-SWAP", 50)).willReturn(List.of(order("9")));

        Map<String, Object> out = service.orders("history", "SWAP", "BTC-USDT-SWAP", null);

        assertThat(out).containsEntry("ok", true).containsEntry("state", "history").containsEntry("limit", 50);
        assertThat(list(out.get("items"))).hasSize(1);
        verify(tradeClient).ordersHistory("SWAP", "BTC-USDT-SWAP", 50);
        verify(tradeClient, never()).pendingOrders(anyString());
    }

    @Test
    @DisplayName("未知 state 直接 400，且不碰任何依赖")
    void ordersRejectUnknownState() {
        AccountService service = service(configured(), "OkexAlpha");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.orders("archive", null, null, null))
                .withMessage("state must be one of local|pending|history");
        verifyNoInteractions(tradeClient, shadowRepository);
    }

    @Test
    @DisplayName("pending 但未配置凭证：给 notice、无 ok 键、不发请求")
    void pendingOrdersWithoutCredentials() {
        AccountService service = service(OkxProperties.defaults(), "OkexAlpha");

        Map<String, Object> out = service.orders("pending", "SWAP", null, 10);

        assertThat(out).containsEntry("configured", false).containsEntry("limit", 10);
        assertThat(out.get("ok")).isNull();
        assertThat((String) out.get("notice")).contains("OKX_API_KEY");
        assertThat(list(out.get("items"))).isEmpty();
        verifyNoInteractions(tradeClient, shadowRepository);
    }

    @Test
    @DisplayName("local 订单的 decidedAt 为 null 时保持 null")
    void localOrdersTolerateNullDecidedAt() {
        AccountService service = service(configured(), "OkexAlpha");
        given(shadowRepository.orders(null, null, 50, 0)).willReturn(List.of(new ShadowOrderRow(1L, null, "ETH-USDT-SWAP",
                "sell", "market", 1d, 2000d, 2000d, "NEW", null, null, null)));

        Map<String, Object> out = service.orders("local", null, null, null);

        Map<String, Object> row = list(out.get("items")).get(0);
        assertThat(row).containsEntry("decidedAt", null).containsEntry("strategyId", null)
                .containsEntry("signalTsMs", null);
    }

    // ------------------------------------------------------------ 辅助

    private AccountService service(OkxProperties properties, String keyLabel) {
        return service(properties, credentialsOf(properties), keyLabel);
    }

    private AccountService service(OkxProperties properties, OkxCredentials okxCredentials, String keyLabel) {
        return new AccountService(properties, okxCredentials, tradeClient, shadowRepository, keyLabel);
    }

    /** 等价于"环境变量装载"的内存快照；三件套不全时就是未配置。 */
    private static OkxCredentials credentialsOf(OkxProperties properties) {
        OkxProperties.Strategy.Trading trading = properties.strategy().trading();
        OkxCredentials okxCredentials = new OkxCredentials();
        if (SecretMasker.configured(trading.apiKey()) && SecretMasker.configured(trading.secretKey())
                && SecretMasker.configured(trading.passphrase())) {
            okxCredentials.apply(new OkxCredentials.Credential(trading.apiKey(), trading.secretKey(),
                    trading.passphrase(), null, OkxCredentials.SOURCE_ENV, null));
        }
        return okxCredentials;
    }

    private static OkxProperties configured() {
        return props(API_KEY, SECRET_KEY, PASSPHRASE, "SHADOW");
    }

    private static OkxProperties props(String apiKey, String secretKey, String passphrase, String mode) {
        OkxProperties defaults = OkxProperties.defaults();
        OkxProperties.Strategy strategy = defaults.strategy();
        OkxProperties.Strategy.Trading trading = new OkxProperties.Strategy.Trading(false, mode, apiKey, secretKey,
                passphrase, 0.0005d, 0.0002d, 2d, 50, 0d, 500d,
                new OkxProperties.Strategy.Trading.DailyBatch(false, "0 30 8 * * *"));
        return new OkxProperties(defaults.baseUrl(), defaults.connectTimeoutMs(), defaults.readTimeoutMs(),
                defaults.userAgent(), defaults.retry(), defaults.rateLimit(), defaults.backfill(), defaults.sync(),
                defaults.schedule(), defaults.apiLog(), defaults.db(),
                new OkxProperties.Strategy(strategy.enabled(), strategy.shadow(), strategy.health(),
                        strategy.backtest(), trading));
    }

    private static JsonNode order(String ordId) {
        return json("{\"ordId\":\"" + ordId + "\",\"instId\":\"BTC-USDT-SWAP\",\"state\":\"live\"}");
    }

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
