package com.azylivraison.administration.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.azylivraison.administration.domain.VehiclePresetConfiguration;
import com.azylivraison.planning.domain.VehicleType;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VehiclePresetConfigurationRepository {

    private final JdbcTemplate jdbcTemplate;

    public VehiclePresetConfigurationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<VehiclePresetConfiguration> findAll() {
        return jdbcTemplate.query("""
                SELECT vehicle_type, max_weight_kg, average_speed_kmh,
                       version, created_at, updated_at
                FROM vehicle_preset
                ORDER BY CASE vehicle_type WHEN 'MOTO' THEN 0 ELSE 1 END, vehicle_type
                """, (resultSet, rowNumber) -> map(resultSet));
    }

    public Optional<VehiclePresetConfiguration> findByType(VehicleType vehicleType) {
        List<VehiclePresetConfiguration> presets = jdbcTemplate.query("""
                SELECT vehicle_type, max_weight_kg, average_speed_kmh,
                       version, created_at, updated_at
                FROM vehicle_preset
                WHERE vehicle_type = ?
                """, (resultSet, rowNumber) -> map(resultSet), vehicleType.name());
        return presets.stream().findFirst();
    }

    public boolean update(VehiclePresetConfiguration preset, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE vehicle_preset
                SET max_weight_kg = ?,
                    average_speed_kmh = ?,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE vehicle_type = ? AND version = ?
                """,
                preset.maxWeightKg(),
                preset.averageSpeedKmh(),
                preset.vehicleType().name(),
                expectedVersion) == 1;
    }

    private VehiclePresetConfiguration map(ResultSet resultSet) throws SQLException {
        return new VehiclePresetConfiguration(
                VehicleType.valueOf(resultSet.getString("vehicle_type")),
                resultSet.getBigDecimal("max_weight_kg"),
                resultSet.getBigDecimal("average_speed_kmh"),
                resultSet.getLong("version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}
