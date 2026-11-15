package com.azylivraison.delivery.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import com.azylivraison.pricing.service.PricingConfigurationException;

class DeliveryTimeWindowTest {
    @Test void normalizesPeriodsWithoutArbitraryOneHourLimit() {
        var morning = DeliveryTimeWindow.normalize(null, null, DeliveryTimePreference.MORNING);
        assertThat(morning.start()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(morning.end()).isEqualTo(LocalTime.NOON);
        var evening = DeliveryTimeWindow.normalize(null, null, DeliveryTimePreference.AFTERNOON);
        assertThat(evening.start()).isEqualTo(LocalTime.NOON);
        assertThat(evening.end()).isEqualTo(LocalTime.of(23, 59, 59));
        var custom = DeliveryTimeWindow.normalize(LocalTime.of(8, 0), LocalTime.of(18, 0), DeliveryTimePreference.CUSTOM);
        assertThat(custom.end()).isEqualTo(LocalTime.of(18, 0));
    }

    @Test void keepsLegacyStartOnlyAndOvernightWindows() {
        var legacy = DeliveryTimeWindow.normalize(LocalTime.of(23, 30), null, null);
        assertThat(legacy.end()).isEqualTo(LocalTime.of(0, 30));
        assertThat(legacy.endsNextDay()).isTrue();
        var overnight = DeliveryTimeWindow.normalize(LocalTime.of(22, 0), LocalTime.of(2, 0), DeliveryTimePreference.CUSTOM);
        assertThat(overnight.endsNextDay()).isTrue();
    }

    @Test void rejectsIncompleteOrEmptyExplicitWindowsAndConflictingModes() {
        assertThatThrownBy(() -> DeliveryTimeWindow.normalize(LocalTime.NOON, null, DeliveryTimePreference.CUSTOM))
                .isInstanceOf(PricingConfigurationException.class);
        assertThatThrownBy(() -> DeliveryTimeWindow.normalize(LocalTime.NOON, LocalTime.NOON, DeliveryTimePreference.CUSTOM))
                .isInstanceOf(PricingConfigurationException.class);
        assertThatThrownBy(() -> DeliveryTimeWindow.normalize(LocalTime.NOON, null, DeliveryTimePreference.NONE))
                .isInstanceOf(PricingConfigurationException.class);
    }
}
