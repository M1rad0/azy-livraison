package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Completes the express fuel schema without changing the already published V16 migration.
 */
public class V19__complete_express_fuel_schema extends BaseJavaMigration {

    @Override
    public Integer getChecksum() {
        return 1;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        execute(connection, """
                ALTER TABLE express_fuel_courier_calculation
                    ADD COLUMN IF NOT EXISTS reposition_distance_meters BIGINT
                """);
        execute(connection, """
                ALTER TABLE courier_fuel_earning
                    ADD COLUMN IF NOT EXISTS reposition_distance_meters BIGINT
                """);

        replaceStatusConstraint(connection);
        replaceConstraint(connection, "express_fuel_courier_calculation",
                "express_fuel_line_efficiency_check", """
                CHECK (
                    (fuel_reimbursement_enabled
                         AND ((fuel_efficiency_km_per_liter IS NOT NULL
                                   AND fuel_efficiency_km_per_liter > 0)
                              OR manual))
                    OR (NOT fuel_reimbursement_enabled)
                )
                """);
        replaceConstraint(connection, "express_fuel_courier_calculation",
                "express_fuel_line_reposition_distance_check", """
                CHECK (reposition_distance_meters IS NULL OR reposition_distance_meters >= 0)
                """);
        replaceConstraint(connection, "courier_fuel_earning",
                "courier_fuel_earning_reposition_distance_check", """
                CHECK (reposition_distance_meters IS NULL OR reposition_distance_meters >= 0)
                """);
    }

    private void replaceStatusConstraint(Connection connection) throws SQLException {
        List<CheckConstraint> statusConstraints = checkConstraints(connection,
                "express_fuel_calculation").stream()
                .filter(constraint -> {
                    String clause = constraint.clause().toUpperCase(Locale.ROOT);
                    return clause.contains("STATUS") && clause.contains("WAITING_FOR_OUTCOMES");
                })
                .toList();

        if (statusConstraints.isEmpty()) {
            throw new SQLException("The express fuel status constraint could not be found.");
        }

        boolean desiredConstraintAlreadyExists = false;
        for (CheckConstraint constraint : statusConstraints) {
            if (constraint.clause().toUpperCase(Locale.ROOT).contains("MANUAL_INCOMPLETE")) {
                desiredConstraintAlreadyExists = true;
            } else {
                dropConstraint(connection, "express_fuel_calculation", constraint.name());
            }
        }

        if (!desiredConstraintAlreadyExists) {
            execute(connection, """
                    ALTER TABLE express_fuel_calculation
                        ADD CONSTRAINT express_fuel_calculation_status_check
                        CHECK (status IN ('WAITING_FOR_OUTCOMES', 'CALCULATED', 'ROUTING_FAILED',
                                          'CONFIGURATION_MISSING', 'MANUAL_INCOMPLETE', 'MANUAL'))
                    """);
        }
    }

    private List<CheckConstraint> checkConstraints(Connection connection, String tableName)
            throws SQLException {
        String schema = connection.getSchema();
        String sql = """
                SELECT tc.constraint_name, cc.check_clause
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_catalog = tc.constraint_catalog
                 AND cc.constraint_schema = tc.constraint_schema
                 AND cc.constraint_name = tc.constraint_name
                WHERE LOWER(tc.table_name) = LOWER(?)
                  AND LOWER(tc.constraint_schema) = LOWER(?)
                  AND UPPER(tc.constraint_type) = 'CHECK'
                """;
        List<CheckConstraint> constraints = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    constraints.add(new CheckConstraint(
                            resultSet.getString("constraint_name"),
                            resultSet.getString("check_clause")));
                }
            }
        }
        return constraints;
    }

    private void replaceConstraint(Connection connection, String tableName,
            String constraintName, String definition) throws SQLException {
        execute(connection, "ALTER TABLE " + tableName + " DROP CONSTRAINT IF EXISTS " + constraintName);
        execute(connection, "ALTER TABLE " + tableName + " ADD CONSTRAINT "
                + constraintName + " " + definition);
    }

    private void dropConstraint(Connection connection, String tableName, String constraintName)
            throws SQLException {
        if (!constraintName.matches("[A-Za-z0-9_$]+")) {
            throw new SQLException("Unsafe database constraint name: " + constraintName);
        }
        execute(connection, "ALTER TABLE " + tableName + " DROP CONSTRAINT \""
                + constraintName.replace("\"", "\"\"") + "\"");
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record CheckConstraint(String name, String clause) {
    }
}
