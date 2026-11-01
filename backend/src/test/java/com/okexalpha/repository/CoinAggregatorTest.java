package com.okexalpha.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** CoinRepository.rebuild()：先清空 okx_coin，再用 okx_instrument 聚合结果整表回填。 */
class CoinAggregatorTest {

    @Test
    void rebuildDeletesThenAggregatesFromInstrument() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString())).thenReturn(3, 7);

        int affected = new CoinRepository(jdbc).rebuild();

        assertThat(affected).isEqualTo(7);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture());
        List<String> statements = sql.getAllValues();
        assertThat(statements.get(0)).isEqualTo("DELETE FROM okx_coin");
        assertThat(statements.get(1))
                .contains("INSERT INTO okx_coin")
                .contains("okx_instrument")
                .contains("GROUP BY")
                .contains("base_ccy")
                .contains("updated_at");
    }

    @Test
    void rebuildSqlAggregatesChannelsAndRoleCounters() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString())).thenReturn(1);

        new CoinRepository(jdbc).rebuild();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture());
        String insert = sql.getAllValues().get(1);
        assertThat(insert).contains("spot_inst_count");
        assertThat(insert).contains("swap_inst_count");
        assertThat(insert).contains("futures_inst_count");
        assertThat(insert).contains("option_inst_count");
        assertThat(insert).contains("margin_inst_count");
    }

    @Test
    void rebuildReturnsInsertAffectedRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString())).thenReturn(0, 42);

        assertThat(new CoinRepository(jdbc).rebuild()).isEqualTo(42);
    }
}
