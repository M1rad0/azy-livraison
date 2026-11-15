package com.azylivraison.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.delivery.domain.DeliveryRequestKind;
import com.azylivraison.delivery.domain.DeliveryTimePreference;
import com.azylivraison.pricing.domain.AdvancedPricingPolicy;
import com.azylivraison.pricing.domain.AdvancedPricingQuote;
import com.azylivraison.pricing.domain.PricingCenter;
import com.azylivraison.pricing.domain.PricingDistanceMode;
import com.azylivraison.pricing.domain.PricingKeywordRule;
import com.azylivraison.pricing.domain.PricingTimeSlot;
import com.azylivraison.pricing.domain.PricingWeightTier;
import com.azylivraison.pricing.domain.PricingZone;
import com.azylivraison.pricing.repository.AdvancedPricingPolicyRepository;
import com.azylivraison.routing.service.RoutingService;

import org.junit.jupiter.api.Test;

class AdvancedPricingServiceTest {

    private final AdvancedPricingPolicyRepository repository = mock(AdvancedPricingPolicyRepository.class);
    private final AdvancedPricingService service = new AdvancedPricingService(repository, mock(RoutingService.class));

    @Test
    void periodSurchargesReplaceTimeSlotsAndHaveIndependentPolicyAmounts() {
        AdvancedPricingPolicy policy = policy(List.of());

        AdvancedPricingQuote morning = quote(policy, DeliveryTimePreference.MORNING, null, null, null, null);
        assertThat(morning.timeSurchargeAriary()).isEqualTo(500);
        assertThat(morning.timeSlotLabel()).isEqualTo("Matin");
        assertThat(morning.desiredDeliveryTime()).isEqualTo(LocalTime.MIN);
        assertThat(morning.desiredDeliveryEndTime()).isEqualTo(LocalTime.NOON);
        assertThat(morning.deliveryFeeAriary()).isEqualTo(3_700);

        AdvancedPricingQuote afternoon = quote(policy, DeliveryTimePreference.AFTERNOON,
                LocalTime.NOON, LocalTime.of(23, 59, 59), null, null);
        assertThat(afternoon.timeSurchargeAriary()).isEqualTo(700);
        assertThat(afternoon.timeSlotLabel()).isEqualTo("Soir");
        assertThat(afternoon.deliveryFeeAriary()).isEqualTo(3_900);
    }

    @Test
    void customUsesOnlyStartTimeSlotAndKeepsTheRequestedEnd() {
        AdvancedPricingQuote custom = quote(policy(List.of()), DeliveryTimePreference.CUSTOM,
                LocalTime.of(11, 30), LocalTime.of(15, 45), null, null);

        assertThat(custom.timeSurchargeAriary()).isEqualTo(900);
        assertThat(custom.timeSlotLabel()).isEqualTo("Heure exacte");
        assertThat(custom.desiredDeliveryEndTime()).isEqualTo(LocalTime.of(15, 45));
        assertThat(custom.deliveryFeeAriary()).isEqualTo(4_100);
    }

    @Test
    void legacyStartOnlyStillDefaultsToOneHourAndCanCrossMidnight() {
        AdvancedPricingQuote quote = service.quote(policy(List.of()), 0, 0, null, LocalTime.of(23, 30));

        assertThat(quote.deliveryTimePreference()).isEqualTo(DeliveryTimePreference.CUSTOM);
        assertThat(quote.desiredDeliveryEndTime()).isEqualTo(LocalTime.of(0, 30));
        assertThat(quote.timeSurchargeAriary()).isEqualTo(900);
    }

    @Test
    void noPreferenceHasNoTimeSurchargeAndPeriodsRejectArbitraryHours() {
        AdvancedPricingQuote none = quote(policy(List.of()), DeliveryTimePreference.NONE, null, null, null, null);
        assertThat(none.timeSurchargeAriary()).isZero();
        assertThat(none.desiredDeliveryTime()).isNull();
        assertThat(none.desiredDeliveryEndTime()).isNull();
        assertThat(none.deliveryFeeAriary()).isEqualTo(3_200);

        assertThatThrownBy(() -> quote(policy(List.of()), DeliveryTimePreference.MORNING,
                LocalTime.of(10, 0), LocalTime.NOON, null, null))
                .isInstanceOf(PricingConfigurationException.class);
        assertThatThrownBy(() -> quote(policy(List.of()), DeliveryTimePreference.CUSTOM,
                LocalTime.of(10, 0), null, null, null))
                .isInstanceOf(PricingConfigurationException.class);
    }

    @Test
    void keywordPriceReplacesZoneBaseBeforeWeightAndTimeAndMatchesEitherAddressField() {
        AdvancedPricingPolicy policy = policy(List.of(rule("école", 1_000)));
        AdvancedPricingQuote label = quote(policy, DeliveryTimePreference.MORNING, null, null,
                "Livraison ÉCOLE centrale", "Autre adresse");
        assertThat(label.baseDeliveryFeeAriary()).isEqualTo(1_000);
        assertThat(label.weightSurchargeAriary()).isEqualTo(200);
        assertThat(label.timeSurchargeAriary()).isEqualTo(500);
        assertThat(label.deliveryFeeAriary()).isEqualTo(1_700);
        assertThat(label.matchedKeyword()).isEqualTo("école");
        assertThat(label.toLegacySnapshot().baseDeliveryFeeAriary()).isEqualTo(1_000);

        AdvancedPricingQuote address = quote(policy, DeliveryTimePreference.NONE, null, null,
                "Autre libelle", "Route de l'ECOLE");
        assertThat(address.baseDeliveryFeeAriary()).isEqualTo(1_000);
    }

    @Test
    void longestNormalizedKeywordWinsAndEqualLengthPreservesListOrder() {
        AdvancedPricingPolicy policy = policy(List.of(rule("école", 1_000),
                rule("  Ecole   Centrale ", 1_500), rule("ÉCOLE CENTRALE", 9_000)));

        AdvancedPricingQuote quote = quote(policy, DeliveryTimePreference.NONE, null, null,
                "  Livraison école\u00a0   centrale ", null);

        assertThat(quote.baseDeliveryFeeAriary()).isEqualTo(1_500);
        assertThat(quote.matchedKeyword()).isEqualTo("  Ecole   Centrale ");
    }

    @Test
    void unmatchedKeywordKeepsZoneAndDoesNotMatchAcrossSeparateAddressFields() {
        AdvancedPricingPolicy policy = policy(List.of(rule("école centrale", 1_000)));
        AdvancedPricingQuote quote = quote(policy, DeliveryTimePreference.NONE, null, null, "école", "centrale");
        assertThat(quote.baseDeliveryFeeAriary()).isEqualTo(3_000);
        assertThat(quote.matchedKeyword()).isNull();
    }

    @Test
    void quotesUseTheEffectiveClientAndKindPolicyWithoutLeakingRules() {
        UUID client = UUID.randomUUID();
        when(repository.findEffective(client, DeliveryRequestKind.EXPRESS))
                .thenReturn(Optional.of(policy(List.of(rule("école", 2_000)))));
        when(repository.findEffective(null, DeliveryRequestKind.NORMAL))
                .thenReturn(Optional.of(policy(List.of())));

        assertThat(service.quote(client, DeliveryRequestKind.EXPRESS, 0, 0, null,
                null, null, DeliveryTimePreference.NONE, "école", null).baseDeliveryFeeAriary()).isEqualTo(2_000);
        assertThat(service.quote(null, DeliveryRequestKind.NORMAL, 0, 0, null,
                null, null, DeliveryTimePreference.NONE, "école", null).baseDeliveryFeeAriary()).isEqualTo(3_000);
    }

    @Test
    void keywordsDoNotSilentlyExpandGeographicCoverage() {
        assertThatThrownBy(() -> service.quote(policy(List.of(rule("école", 1_000))), 0, 1,
                null, null, null, DeliveryTimePreference.NONE, "école", null))
                .isInstanceOf(DestinationOutsidePricingZonesException.class);
    }

    @Test
    void policyValidationRejectsNegativePeriodAmountsAndEmptyKeywords() {
        AdvancedPricingPolicy policy = policy(List.of());
        when(repository.findExact(null, DeliveryRequestKind.NORMAL)).thenReturn(Optional.of(policy));
        assertThatThrownBy(() -> service.save(null, DeliveryRequestKind.NORMAL, policy.distanceMode(),
                policy.center(), policy.defaultWeightKg(), policy.zones(), policy.weightTiers(), policy.timeSlots(),
                -1, 500, List.of(), 0L)).isInstanceOf(PricingConfigurationException.class);
        assertThatThrownBy(() -> service.save(null, DeliveryRequestKind.NORMAL, policy.distanceMode(),
                policy.center(), policy.defaultWeightKg(), policy.zones(), policy.weightTiers(), policy.timeSlots(),
                500, 500, List.of(rule("  ", 1_000)), 0L)).isInstanceOf(PricingConfigurationException.class);
    }

    private AdvancedPricingQuote quote(AdvancedPricingPolicy policy, DeliveryTimePreference preference,
            LocalTime start, LocalTime end, String label, String query) {
        return service.quote(policy, 0, 0, null, start, end, preference, label, query);
    }

    private PricingKeywordRule rule(String keyword, long amount) {
        return new PricingKeywordRule(UUID.randomUUID(), keyword, amount);
    }

    private AdvancedPricingPolicy policy(List<PricingKeywordRule> keywords) {
        return new AdvancedPricingPolicy((short) 1, null, DeliveryRequestKind.NORMAL,
                PricingDistanceMode.AIR_DISTANCE, new PricingCenter("Centre", null, 0, 0), BigDecimal.ONE,
                List.of(new PricingZone(UUID.randomUUID(), "Centre", 2_000, 3_000)),
                List.of(new PricingWeightTier(UUID.randomUUID(), BigDecimal.TEN, 200)),
                List.of(new PricingTimeSlot(UUID.randomUUID(), "Heure exacte", LocalTime.MIN,
                        LocalTime.of(23, 59, 59), 900)),
                500, 700, keywords, 0, Instant.EPOCH, Instant.EPOCH);
    }
}
