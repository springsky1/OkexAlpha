package com.okexalpha.service.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.okexalpha.client.OkxApiClient;
import com.okexalpha.domain.MinOrderRow;
import com.okexalpha.repository.InstrumentRepository;
import com.okexalpha.repository.MinOrderRepository;

/** R5 下单张数换算：正向/反向合约的名义价值、lotSz 向上取整、minSz 兜底。 */
class MinOrderSizingTest {

    private static final double EPS = 1e-9;

    @Test
    void linearContractNotionalIsCtValTimesPrice() {
        // BTC-USDT-SWAP：ctVal=0.01 BTC，价格 100,000 -> 一张 = 1,000 U
        assertThat(MinOrderService.notionalPerContractUsd(0.01, "BTC", 100_000d))
                .isCloseTo(1_000d, within(EPS));
    }

    @Test
    void inverseContractNotionalIsCtValInUsd() {
        // BTC-USD-SWAP：ctVal=100 USD，ctValCcy=USD -> 一张 = 100 U，不需要价格
        assertThat(MinOrderService.notionalPerContractUsd(100d, "USD", null)).isCloseTo(100d, within(EPS));
        assertThat(MinOrderService.notionalPerContractUsd(100d, "usd", 12_345d)).isCloseTo(100d, within(EPS));
    }

    @Test
    void notionalIsNullWhenDataMissing() {
        assertThat(MinOrderService.notionalPerContractUsd(null, "BTC", 100_000d)).isNull();
        assertThat(MinOrderService.notionalPerContractUsd(0d, "BTC", 100_000d)).isNull();
        assertThat(MinOrderService.notionalPerContractUsd(0.01, "BTC", null)).isNull();
        assertThat(MinOrderService.notionalPerContractUsd(0.01, "BTC", 0d)).isNull();
    }

    @Test
    void sizesExactlyOneLotAtTargetNotional() {
        MinOrderService.Sizing sizing = MinOrderService.computeSizing(100d, 1_000d, 0.1d, 0.1d);

        assertThat(sizing.tradable()).isTrue();
        assertThat(sizing.size()).isCloseTo(0.1d, within(EPS));
        assertThat(sizing.lots()).isCloseTo(1d, within(EPS));
        assertThat(sizing.notionalUsd()).isCloseTo(100d, within(EPS));
        assertThat(sizing.minNotionalUsd()).isCloseTo(100d, within(EPS));
        assertThat(sizing.minSized()).isFalse();
    }

    @Test
    void roundsLotsUpSoNotionalNeverUndershoots() {
        // 一张 7 U，目标 100 U -> 14.28 张 -> 向上取整 15 张 = 105 U
        MinOrderService.Sizing sizing = MinOrderService.computeSizing(100d, 7d, 1d, 1d);

        assertThat(sizing.size()).isCloseTo(15d, within(EPS));
        assertThat(sizing.lots()).isCloseTo(15d, within(EPS));
        assertThat(sizing.notionalUsd()).isCloseTo(105d, within(EPS));
        assertThat(sizing.minSized()).isFalse();
    }

    @Test
    void roundsUpOnFractionalLotSize() {
        // lotSz=0.05，一张 3 U，目标 100 U -> 33.33 张 -> 667 档 = 33.35 张 = 100.05 U
        MinOrderService.Sizing sizing = MinOrderService.computeSizing(100d, 3d, 0.05d, 0.05d);

        assertThat(sizing.size()).isCloseTo(33.35d, within(EPS));
        assertThat(sizing.lots()).isCloseTo(667d, within(EPS));
        assertThat(sizing.notionalUsd()).isCloseTo(100.05d, within(EPS));
    }

    @Test
    void fallsBackToMinSizeWhenTargetIsTooSmall() {
        // 一张 1 U、minSz=5 张（最小 5 U），目标 2 U -> 兜底 5 张并标记 minSized
        MinOrderService.Sizing sizing = MinOrderService.computeSizing(2d, 1d, 1d, 5d);

        assertThat(sizing.size()).isCloseTo(5d, within(EPS));
        assertThat(sizing.minSized()).isTrue();
        assertThat(sizing.notionalUsd()).isCloseTo(5d, within(EPS));
    }

    @Test
    void zeroTargetFallsBackToMinSize() {
        MinOrderService.Sizing sizing = MinOrderService.computeSizing(0d, 1d, 1d, 5d);

        assertThat(sizing.size()).isCloseTo(5d, within(EPS));
        assertThat(sizing.minSized()).isFalse();
    }

    @Test
    void defaultLotSizeIsOneWhenMissing() {
        MinOrderService.Sizing sizing = MinOrderService.computeSizing(10d, 1d, null, null);

        assertThat(sizing.size()).isCloseTo(10d, within(EPS));
        assertThat(sizing.lots()).isCloseTo(10d, within(EPS));
    }

    @Test
    void unknownNotionalPerContractIsNotTradable() {
        MinOrderService.Sizing sizing = MinOrderService.computeSizing(100d, null, 1d, 1d);

        assertThat(sizing.tradable()).isFalse();
        assertThat(sizing.size()).isNull();
        assertThat(sizing.notionalUsd()).isNull();
    }

    @Test
    void sizeForUnknownInstrumentIsNull() {
        MinOrderService service = new MinOrderService(mock(OkxApiClient.class), mock(InstrumentRepository.class),
                mock(MinOrderRepository.class));

        assertThat(service.sizeFor("NOPE-USDT-SWAP", 100d)).isNull();
    }

    @Test
    void sizeForUsesStoredReferencePriceAndFlagsMinSizing() {
        MinOrderRepository repository = mock(MinOrderRepository.class);
        when(repository.findById("BTC-USDT-SWAP")).thenReturn(new MinOrderRow("BTC-USDT-SWAP", "SWAP", "USDT",
                0.01d, "BTC", 1d, 0.1d, 0.1d, 0.1d, 100_000d, 1_000d, 100d, 1d, true, null));
        MinOrderService service = new MinOrderService(mock(OkxApiClient.class), mock(InstrumentRepository.class),
                repository);

        MinOrderService.Sizing sizing = service.sizeFor("BTC-USDT-SWAP", 100d);

        assertThat(sizing).isNotNull();
        assertThat(sizing.instId()).isEqualTo("BTC-USDT-SWAP");
        assertThat(sizing.refPx()).isCloseTo(100_000d, within(EPS));
        assertThat(sizing.size()).isCloseTo(0.1d, within(EPS));
        assertThat(sizing.notionalUsd()).isCloseTo(100d, within(EPS));
        assertThat(sizing.minSized()).isFalse();

        // 目标金额缺省时按平台最小下单金额换算：100 U -> 0.1 张（正好 1 档）
        MinOrderService.Sizing byMinimum = service.sizeFor("BTC-USDT-SWAP", null);
        assertThat(byMinimum.size()).isCloseTo(0.1d, within(EPS));
    }
}
