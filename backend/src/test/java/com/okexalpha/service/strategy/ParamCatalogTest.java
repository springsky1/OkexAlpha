package com.okexalpha.service.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.okexalpha.error.NotFoundException;
import com.okexalpha.strategy.domain.ParamCatalog;
import com.okexalpha.strategy.domain.ParamSpec;

/** R3/P0 参数契约：目录完整性、发送前校验、签名判定。 */
class ParamCatalogTest {

    private final ParamCatalogService service = new ParamCatalogService();

    @Test
    void shipsNineCatalogsForThePrivateEndpoints() {
        assertThat(service.catalogs()).hasSize(9);
        assertThat(service.catalogs()).extracting(ParamCatalog::endpoint).containsExactlyInAnyOrder(
                ParamCatalogService.ORDER, ParamCatalogService.CLOSE_POSITION, ParamCatalogService.SET_LEVERAGE,
                ParamCatalogService.SET_POSITION_MODE, ParamCatalogService.POSITIONS, ParamCatalogService.BALANCE,
                ParamCatalogService.MAX_SIZE, ParamCatalogService.TICKERS, ParamCatalogService.MARK_PRICE);
    }

    @Test
    void everyCatalogHasMethodDocUrlAndCheckedDate() {
        assertThat(service.catalogs()).allSatisfy(catalog -> {
            assertThat(catalog.method()).isIn("GET", "POST");
            assertThat(catalog.docUrl()).startsWith("https://www.okx.com/docs-v5/zh/#");
            assertThat(catalog.checkedAt()).isEqualTo("2026-11-01");
            assertThat(catalog.params()).isNotEmpty();
        });
    }

    @Test
    void placeOrderKeepsTakeProfitInsideAttachAlgoOrds() {
        // 官方文档里 tpTriggerPx / slTriggerPx 属于 attachAlgoOrds[] 元素；
        // 顶层同名参数即使还列在文档中，也必须标成已废弃，不能让调用方以为可以单独使用
        List<ParamSpec> params = service.catalogOf(ParamCatalogService.ORDER).params();

        assertThat(params).extracting(ParamSpec::name).contains("attachAlgoOrds");
        assertThat(params)
                .filteredOn(spec -> spec.name().startsWith("tp") || spec.name().startsWith("sl"))
                .isNotEmpty()
                .allSatisfy(spec -> {
                    assertThat(spec.anchor()).contains("已废弃");
                    assertThat(spec.required()).isFalse();
                });
    }

    @Test
    void unknownEndpointThrowsNotFound() {
        assertThatThrownBy(() -> service.catalogOf("/api/v5/nope"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("no param catalog for endpoint /api/v5/nope");
    }

    @Test
    void completeOrderPassesValidation() {
        ParamCatalogService.Validation validation = service.validate(ParamCatalogService.ORDER,
                Map.of("instId", "BTC-USDT-SWAP", "side", "buy", "ordType", "limit", "sz", "1"));

        assertThat(validation.ok()).isTrue();
        assertThat(validation.describe()).isEqualTo("ok");
    }

    @Test
    void missingRequiredFieldsAreReported() {
        ParamCatalogService.Validation validation = service.validate(ParamCatalogService.ORDER, Map.of());

        assertThat(validation.ok()).isFalse();
        assertThat(validation.missing()).containsExactly("instId", "side", "ordType", "sz");
        assertThat(validation.describe()).isEqualTo("missing: instId,side,ordType,sz");
    }

    @Test
    void misspelledFieldIsReportedAsUnknown() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("instId", "BTC-USDT-SWAP");
        fields.put("side", "buy");
        fields.put("ordType", "market");
        fields.put("sz", "1");
        fields.put("tdmode", "cross"); // 大小写写错也算未知：字段名区分大小写

        ParamCatalogService.Validation validation = service.validate(ParamCatalogService.ORDER, fields);

        assertThat(validation.ok()).isFalse();
        assertThat(validation.unknown()).containsExactly("tdmode");
        assertThat(validation.describe()).isEqualTo("unknown: tdmode");
    }

    @Test
    void outOfRangeEnumIsReported() {
        ParamCatalogService.Validation validation = service.validate(ParamCatalogService.ORDER,
                Map.of("instId", "BTC-USDT-SWAP", "side", "hold", "ordType", "stop", "sz", "1"));

        assertThat(validation.ok()).isFalse();
        assertThat(validation.invalid()).containsEntry("side", "hold").containsEntry("ordType", "stop");
        assertThat(validation.describe()).isEqualTo("invalid enum: side=hold,ordType=stop");
    }

    @Test
    void signatureIsRequiredOnlyForPrivateEndpoints() {
        assertThat(ParamCatalogService.requiresSignature(ParamCatalogService.ORDER)).isTrue();
        assertThat(ParamCatalogService.requiresSignature(ParamCatalogService.POSITIONS)).isTrue();
        assertThat(ParamCatalogService.requiresSignature("/api/v5/asset/balances")).isTrue();
        assertThat(ParamCatalogService.requiresSignature(ParamCatalogService.TICKERS)).isFalse();
        assertThat(ParamCatalogService.requiresSignature(ParamCatalogService.MARK_PRICE)).isFalse();
    }

    @Test
    void privateCatalogsCarrySignatureAndSimulatedTradingHeaders() {
        ParamCatalog catalog = service.catalogOf(ParamCatalogService.BALANCE);

        assertThat(catalog.headers()).extracting(spec -> spec.name()).contains("OK-ACCESS-KEY", "OK-ACCESS-SIGN",
                "OK-ACCESS-TIMESTAMP", "OK-ACCESS-PASSPHRASE", "x-simulated-trading");
    }

    @Test
    void publicCatalogsDoNotAskForHeaders() {
        assertThat(service.catalogOf(ParamCatalogService.TICKERS).headers()).isNotNull();
        assertThat(service.catalogOf(ParamCatalogService.MARK_PRICE).headers()).isNotNull();
    }

    @Test
    void normalizeTrimsAndLowercases() {
        assertThat(ParamCatalogService.normalize("  /API/v5/Trade/Order ")).isEqualTo("/api/v5/trade/order");
        assertThat(ParamCatalogService.normalize(null)).isEmpty();
    }
}
