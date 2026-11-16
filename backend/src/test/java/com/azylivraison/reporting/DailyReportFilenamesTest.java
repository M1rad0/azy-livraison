package com.azylivraison.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import com.azylivraison.reporting.render.DailyReportFilenames;

import org.junit.jupiter.api.Test;

class DailyReportFilenamesTest {
    private static final LocalDate DATE = LocalDate.of(2026, 11, 16);

    @Test
    void keepsTheReadableLoginAndDateWithoutAnInternalUuid() {
        assertThat(DailyReportFilenames.stem("boutique-soa", DATE)).isEqualTo("boutique-soa-2026-11-16");
        assertThat(DailyReportFilenames.stem("general", DATE)).isEqualTo("general-2026-11-16");
    }

    @Test
    void sanitizesAccentsPathsAndUnsupportedCharactersAndBoundsTheFilenameLength() {
        assertThat(DailyReportFilenames.stem("../Société Soa : boutique\\", DATE))
                .isEqualTo("Societe-Soa-boutique-2026-11-16");
        assertThat(DailyReportFilenames.stem("a".repeat(200), DATE)).hasSize(83);
        assertThat(DailyReportFilenames.stem("...", DATE)).isEqualTo("rapport-2026-11-16");
    }
}
