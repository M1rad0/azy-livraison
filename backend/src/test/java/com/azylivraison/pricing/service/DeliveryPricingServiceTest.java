package com.azylivraison.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.pricing.domain.DeliveryPricingQuote;
import com.azylivraison.pricing.domain.PricingCenter;
import com.azylivraison.pricing.domain.PricingPolicy;
import com.azylivraison.pricing.domain.PricingZone;
import com.azylivraison.pricing.repository.PricingPolicyRepository;

import org.junit.jupiter.api.Test;

class DeliveryPricingServiceTest {

    private static final UUID INNER_ID = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private static final UUID OUTER_ID = UUID.fromString("81000000-0000-0000-0000-000000000002");

    private final PricingPolicyRepository repository = mock(PricingPolicyRepository.class);
    private final DeliveryPricingService service = new DeliveryPricingService(repository);

    @Test
    void assignsTheFirstInclusiveRadiusUsingHaversineDistance() {
        PricingPolicy policy = policy();
        double longitudeAtOneKilometer = Math.toDegrees(1_000 / DeliveryPricingService.EARTH_RADIUS_METERS);

        DeliveryPricingQuote quote = service.quote(policy, 0, longitudeAtOneKilometer);

        assertThat(quote.pricingZoneId()).isEqualTo(INNER_ID);
        assertThat(quote.pricingZoneName()).isEqualTo("Centre");
        assertThat(quote.deliveryFeeAriary()).isEqualTo(3_000);
        assertThat(quote.distanceFromCenterMeters()).isEqualTo(1_000);
        assertThat(quote.pricingZoneMaxDistanceMeters()).isEqualTo(1_000);
        assertThat(quote.pricingPolicyVersion()).isEqualTo(7);
    }

    @Test
    void rejectsADestinationBeyondTheLastRadius() {
        PricingPolicy policy = policy();

        assertThatThrownBy(() -> service.quote(policy, 0, 1))
                .isInstanceOf(DestinationOutsidePricingZonesException.class)
                .hasMessageContaining("hors de la zone");
    }

    @Test
    void rejectsDuplicateNamesDecreasingRadiiAndDecreasingFees() {
        when(repository.find()).thenReturn(Optional.of(policy()));

        assertThatThrownBy(() -> service.update(
                new PricingCenter("Centre", null, 0, 0),
                List.of(
                        new PricingZone(INNER_ID, "Centre", 2_000, 4_000),
                        new PricingZone(OUTER_ID, " centre ", 3_000, 5_000)),
                7)).isInstanceOf(PricingConfigurationException.class)
                .hasMessageContaining("noms");

        assertThatThrownBy(() -> service.update(
                new PricingCenter("Centre", null, 0, 0),
                List.of(
                        new PricingZone(INNER_ID, "Centre", 2_000, 4_000),
                        new PricingZone(OUTER_ID, "Exterieur", 1_000, 5_000)),
                7)).isInstanceOf(PricingConfigurationException.class)
                .hasMessageContaining("strictement croissants");

        assertThatThrownBy(() -> service.update(
                new PricingCenter("Centre", null, 0, 0),
                List.of(
                        new PricingZone(INNER_ID, "Centre", 1_000, 5_000),
                        new PricingZone(OUTER_ID, "Exterieur", 2_000, 4_000)),
                7)).isInstanceOf(PricingConfigurationException.class)
                .hasMessageContaining("ne peuvent pas diminuer");
    }

    private PricingPolicy policy() {
        return new PricingPolicy(
                new PricingCenter("Centre", null, 0, 0),
                List.of(
                        new PricingZone(INNER_ID, "Centre", 1_000, 3_000),
                        new PricingZone(OUTER_ID, "Peripherie", 2_000, 4_000)),
                7,
                Instant.EPOCH,
                Instant.EPOCH);
    }
}
