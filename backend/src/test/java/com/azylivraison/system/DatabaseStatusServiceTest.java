package com.azylivraison.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseStatusServiceTest {

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final DatabaseStatusService service = new DatabaseStatusService(jdbcTemplate);

    @Test
    void reportsAvailableWhenQuerySucceeds() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void reportsUnavailableWhenQueryFails() {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThat(service.isAvailable()).isFalse();
    }
}

