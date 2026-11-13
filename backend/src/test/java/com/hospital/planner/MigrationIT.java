package com.hospital.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Upgrades a database holding data from the previous release (V1-V3) to the latest schema and checks nothing is lost. */
@Testcontainers
class MigrationIT {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    private long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void upgradePreservesLegacyDataAndBackfillsHistory() throws Exception {
        Flyway old = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).target("3").load();
        old.migrate();
        String legacy = StreamUtils.copyToString(getClass().getResourceAsStream("/legacy_data.sql"), StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement st = c.createStatement()) {
            st.execute(legacy);
            long bookings = count(st, "SELECT count(*) FROM bookings");
            long usage = count(st, "SELECT count(*) FROM usage_events");
            long maintenance = count(st, "SELECT count(*) FROM maintenance_windows");

            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();

            assertEquals(bookings, count(st, "SELECT count(*) FROM bookings"));
            assertEquals(usage, count(st, "SELECT count(*) FROM usage_events"));
            assertEquals(maintenance, count(st, "SELECT count(*) FROM maintenance_windows"));
            assertEquals(1, count(st, "SELECT count(*) FROM assets WHERE asset_tag = 'DEFB-0001' AND lifecycle_status = 'OUT_OF_SERVICE'"));
            // usage 12 days ago happened before both transfers, so it belongs to the original department
            assertEquals(1, count(st, "SELECT count(*) FROM usage_events u JOIN departments d ON d.id = u.department_id WHERE d.name = 'Emergency'"));
            assertTrue(count(st, "SELECT count(*) FROM asset_department_history") >= 6);
        }
    }
}
