package com.azylivraison.administration.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.azylivraison.administration.domain.ConfirmedLocation;
import com.azylivraison.administration.domain.OperationalSettings;
import com.azylivraison.planning.domain.RoutingPreset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperationalSettingsRepository {

    private final JdbcTemplate jdbcTemplate;

    public OperationalSettingsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OperationalSettings> find() {
        List<OperationalSettings> settings = jdbcTemplate.query("""
                SELECT daily_package_limit,
                       default_hub_label, default_hub_query,
                       default_hub_latitude, default_hub_longitude,
                       default_package_weight_kg,
                       pickup_service_minutes, hub_service_minutes, delivery_service_minutes,
                       early_tolerance_minutes, late_tolerance_minutes,
                       optimization_time_limit_seconds, routing_preset,
                       default_courier_commission_numerator,
                       default_courier_commission_denominator,
                       default_fuel_price_per_liter_ariary,
                       default_express_return_to_pickup,
                       version, created_at, updated_at
                FROM operational_settings
                WHERE id = 1
                """, (resultSet, rowNumber) -> map(resultSet));
        return settings.stream().findFirst();
    }

    public boolean update(OperationalSettings settings, long expectedVersion) {
        ConfirmedLocation hub = settings.defaultHub();
        return jdbcTemplate.update("""
                UPDATE operational_settings
                SET daily_package_limit = ?,
                    default_hub_label = ?,
                    default_hub_query = ?,
                    default_hub_latitude = ?,
                    default_hub_longitude = ?,
                    default_package_weight_kg = ?,
                    pickup_service_minutes = ?,
                    hub_service_minutes = ?,
                    delivery_service_minutes = ?,
                    early_tolerance_minutes = ?,
                    late_tolerance_minutes = ?,
                    optimization_time_limit_seconds = ?,
                    routing_preset = ?,
                    default_courier_commission_numerator = ?,
                    default_courier_commission_denominator = ?,
                    default_fuel_price_per_liter_ariary = ?,
                    default_express_return_to_pickup = ?,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = 1 AND version = ?
                """,
                settings.dailyPackageLimit(),
                hub == null ? null : hub.label(),
                hub == null ? null : hub.destinationQuery(),
                hub == null ? null : hub.latitude(),
                hub == null ? null : hub.longitude(),
                settings.defaultPackageWeightKg(),
                settings.pickupServiceMinutes(),
                settings.hubServiceMinutes(),
                settings.deliveryServiceMinutes(),
                settings.earlyToleranceMinutes(),
                settings.lateToleranceMinutes(),
                settings.optimizationTimeLimitSeconds(),
                settings.routingPreset().name(),
                settings.defaultCourierCommissionNumerator(),
                settings.defaultCourierCommissionDenominator(),
                settings.defaultFuelPricePerLiterAriary(),
                settings.defaultExpressReturnToPickup(),
                expectedVersion) == 1;
    }

    private OperationalSettings map(ResultSet resultSet) throws SQLException {
        Double latitude = nullableDouble(resultSet, "default_hub_latitude");
        Double longitude = nullableDouble(resultSet, "default_hub_longitude");
        ConfirmedLocation hub = latitude == null || longitude == null
                ? null
                : new ConfirmedLocation(
                        resultSet.getString("default_hub_label"),
                        resultSet.getString("default_hub_query"),
                        latitude,
                        longitude);

        return new OperationalSettings(
                resultSet.getInt("daily_package_limit"),
                hub,
                resultSet.getBigDecimal("default_package_weight_kg"),
                resultSet.getInt("pickup_service_minutes"),
                resultSet.getInt("hub_service_minutes"),
                resultSet.getInt("delivery_service_minutes"),
                resultSet.getInt("early_tolerance_minutes"),
                resultSet.getInt("late_tolerance_minutes"),
                resultSet.getInt("optimization_time_limit_seconds"),
                RoutingPreset.valueOf(resultSet.getString("routing_preset")),
                resultSet.getInt("default_courier_commission_numerator"),
                resultSet.getInt("default_courier_commission_denominator"),
                resultSet.getLong("default_fuel_price_per_liter_ariary"),
                resultSet.getBoolean("default_express_return_to_pickup"),
                resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private Double nullableDouble(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }
}
