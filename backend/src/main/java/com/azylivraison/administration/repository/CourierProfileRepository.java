package com.azylivraison.administration.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.azylivraison.administration.domain.ConfirmedLocation;
import com.azylivraison.administration.domain.CourierProfile;
import com.azylivraison.administration.domain.CourierPayoutDestination;
import com.azylivraison.planning.domain.VehicleType;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CourierProfileRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, account_id, name, contact, phone_number,
                   payout_operator, payout_account_holder, payout_number,
                   active, vehicle_type,
                   max_weight_kg_override, average_speed_kmh_override,
                   default_departure_time,
                   departure_location_label, departure_location_query,
                   departure_latitude, departure_longitude,
                   version, created_at, updated_at
            FROM courier_profile
            """;

    private final JdbcTemplate jdbcTemplate;

    public CourierProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CourierProfile> findAll() {
        return jdbcTemplate.query(
                SELECT_COLUMNS + " ORDER BY active DESC, LOWER(name), id",
                (resultSet, rowNumber) -> map(resultSet));
    }

    public Optional<CourierProfile> findById(UUID id) {
        List<CourierProfile> couriers = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE id = ?",
                (resultSet, rowNumber) -> map(resultSet),
                id);
        return couriers.stream().findFirst();
    }

    public void create(CourierProfile courier) {
        jdbcTemplate.update("""
                INSERT INTO courier_profile (
                    id, account_id, name, contact, phone_number,
                    payout_operator, payout_account_holder, payout_number,
                    active, vehicle_type,
                    max_weight_kg_override, average_speed_kmh_override,
                    default_departure_time,
                    departure_location_label, departure_location_query,
                    departure_latitude, departure_longitude, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                courier.id(),
                courier.accountId(),
                courier.name(),
                courier.contact(),
                courier.phoneNumber(),
                courier.payoutDestination() == null ? null : courier.payoutDestination().operator(),
                courier.payoutDestination() == null ? null : courier.payoutDestination().accountHolder(),
                courier.payoutDestination() == null ? null : courier.payoutDestination().number(),
                courier.active(),
                courier.vehicleType().name(),
                courier.maxWeightKg(),
                courier.averageSpeedKmh(),
                Time.valueOf(courier.defaultDepartureTime()),
                courier.departureLocation().label(),
                courier.departureLocation().destinationQuery(),
                courier.departureLocation().latitude(),
                courier.departureLocation().longitude());
    }

    public boolean update(CourierProfile courier, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE courier_profile
                SET account_id = ?,
                    name = ?,
                    contact = ?,
                    phone_number = ?,
                    payout_operator = ?,
                    payout_account_holder = ?,
                    payout_number = ?,
                    active = ?,
                    vehicle_type = ?,
                    max_weight_kg_override = ?,
                    average_speed_kmh_override = ?,
                    default_departure_time = ?,
                    departure_location_label = ?,
                    departure_location_query = ?,
                    departure_latitude = ?,
                    departure_longitude = ?,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND version = ?
                """,
                courier.accountId(),
                courier.name(),
                courier.contact(),
                courier.phoneNumber(),
                courier.payoutDestination() == null ? null : courier.payoutDestination().operator(),
                courier.payoutDestination() == null ? null : courier.payoutDestination().accountHolder(),
                courier.payoutDestination() == null ? null : courier.payoutDestination().number(),
                courier.active(),
                courier.vehicleType().name(),
                courier.maxWeightKg(),
                courier.averageSpeedKmh(),
                Time.valueOf(courier.defaultDepartureTime()),
                courier.departureLocation().label(),
                courier.departureLocation().destinationQuery(),
                courier.departureLocation().latitude(),
                courier.departureLocation().longitude(),
                courier.id(),
                expectedVersion) == 1;
    }

    public boolean disable(UUID id, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE courier_profile
                SET active = FALSE,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND version = ?
                """, id, expectedVersion) == 1;
    }

    private CourierProfile map(ResultSet resultSet) throws SQLException {
        return new CourierProfile(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("account_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("contact"),
                resultSet.getString("phone_number"),
                payoutDestination(resultSet),
                resultSet.getBoolean("active"),
                VehicleType.valueOf(resultSet.getString("vehicle_type")),
                resultSet.getBigDecimal("max_weight_kg_override"),
                resultSet.getBigDecimal("average_speed_kmh_override"),
                resultSet.getTime("default_departure_time").toLocalTime(),
                new ConfirmedLocation(
                        resultSet.getString("departure_location_label"),
                        resultSet.getString("departure_location_query"),
                        resultSet.getDouble("departure_latitude"),
                        resultSet.getDouble("departure_longitude")),
                resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private CourierPayoutDestination payoutDestination(ResultSet resultSet) throws SQLException {
        String operator = resultSet.getString("payout_operator");
        if (operator == null) {
            return null;
        }
        return new CourierPayoutDestination(
                operator,
                resultSet.getString("payout_account_holder"),
                resultSet.getString("payout_number"));
    }
}
