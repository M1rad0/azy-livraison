package com.azylivraison.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.azylivraison.account.api.UpdateClientPayoutDestinationsRequest.Item;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PayoutOperatorNormalizerTest {
    @ParameterizedTest
    @CsvSource({
            "MVola,MVola", "mVoLa,MVola", "m_vola,MVola",
            "Orange Money,Orange Money", "ORANGE_MONEY,Orange Money", "orange-money,Orange Money",
            "Airtel Money,Airtel Money", "AIRTEL_MONEY,Airtel Money", "airtel-money,Airtel Money"
    })
    void normalizesOnlyTheThreeSupportedOperators(String value, String canonical) {
        assertThat(PayoutOperatorNormalizer.normalize("  " + value + "  ")).isEqualTo(canonical);
        assertThat(PayoutOperatorNormalizer.supportedName(value)).contains(canonical);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "Banque", "Virement", "Autre", "Espèces", "Orange", "Airtel", "MVola mobile"})
    void rejectsUnsupportedNewPayoutOperators(String value) {
        assertThat(PayoutOperatorNormalizer.supportedName(value)).isEmpty();
        assertThatThrownBy(() -> PayoutOperatorNormalizer.normalize(value))
                .isInstanceOf(InvalidPayoutOperatorException.class)
                .hasMessageContaining("MVola, Orange Money ou Airtel Money");
    }

    @Test
    void normalizingBeforeDuplicateDetectionPreventsAliasDuplicates() {
        assertThatThrownBy(() -> ClientPayoutDestinationNormalizer.normalize(List.of(
                new Item(null, "Orange Money", "Soa", "0321415216", 1, true),
                new Item(null, "ORANGE_MONEY", "Soa", "0321415216", 2, true))))
                .isInstanceOf(InvalidClientProfileException.class)
                .hasMessageContaining("même opérateur");
    }
}
