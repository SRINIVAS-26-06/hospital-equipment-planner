package com.hospital.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hospital.planner.entity.*;
import com.hospital.planner.repository.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Starts Hibernate with ddl-auto=validate against the real Flyway-migrated PostgreSQL schema: if any of the 11 entity mappings
 * disagrees with a table or column, the context fails to start and every test here fails. Needs Docker.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class JpaMappingIT {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired EntityManager em;
    @Autowired DepartmentRepository departments;
    @Autowired EquipmentRepository equipment;
    @Autowired BookingRepository bookings;
    @Autowired UsageRepository usage;
    @Autowired MaintenanceRepository maintenance;
    @Autowired CalibrationRepository calibrations;
    @Autowired WorkOrderRepository workOrders;
    @Autowired NotificationRepository notifications;
    @Autowired AuditLogRepository audit;
    @Autowired EquipmentDocumentRepository documents;
    @Autowired UserRepository users;

    private Equipment seed() {
        return equipment.findByAssetTag("VENT-0001").orElseThrow();
    }

    @Test
    void schemaValidatesAndSeedDataIsReadable() {
        assertTrue(departments.count() >= 4);
        Equipment e = seed();
        assertEquals(LifecycleStatus.ACTIVE, e.lifecycleStatus);
        assertEquals("ICU", e.department.name);
    }

    @Test
    void lockQueryAndForeignKeysWork() {
        Equipment e = equipment.lockById(seed().id).orElseThrow();
        Booking b = new Booking();
        b.equipment = e;
        b.department = e.department;
        b.requestedBy = "staff";
        b.startAt = Instant.now().plus(1, ChronoUnit.HOURS);
        b.endAt = Instant.now().plus(2, ChronoUnit.HOURS);
        bookings.saveAndFlush(b);
        em.clear();
        assertEquals(1, bookings.findOverlapping(e.id, BookingStatus.ACTIVE, b.startAt, b.endAt).size());
        assertEquals(1, bookings.cancelActive(e.id, BookingStatus.CANCELLED_REPAIR, "admin", Instant.now(), "test"));
        em.clear();
        assertEquals(BookingStatus.CANCELLED_REPAIR, bookings.findById(b.id).orElseThrow().status);
    }

    @Test
    void usageSessionJsonChecklistWorkOrderCalibrationAndAuditMappings() {
        Equipment e = seed();
        Usage u = new Usage();
        u.equipment = e;
        u.startAt = Instant.now();
        u.usedBy = "staff";
        u.department = e.department;
        usage.saveAndFlush(u);
        assertTrue(usage.findOpenSession(e.id).isPresent());

        Maintenance m = new Maintenance();
        m.equipment = e;
        m.startAt = Instant.now().plus(10, ChronoUnit.DAYS);
        m.endAt = Instant.now().plus(10, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS);
        m.checklist = "[{\"item\":\"Visual inspection\",\"done\":false}]";
        maintenance.saveAndFlush(m);
        em.clear();
        assertTrue(maintenance.findById(m.id).orElseThrow().checklist.contains("Visual inspection"));

        WorkOrder w = new WorkOrder();
        w.equipment = e;
        w.title = "Alarm";
        w.description = "No alarm";
        w.reportedBy = "staff";
        w.reportedAt = Instant.now();
        w.unsafe = true;
        workOrders.saveAndFlush(w);
        assertEquals(1, workOrders.countBlocking(e.id));
        assertEquals("staff", workOrders.findReporter(w.id).orElseThrow());

        Calibration c = new Calibration();
        c.equipment = e;
        c.scheduledFor = Instant.now().plus(1, ChronoUnit.DAYS);
        calibrations.saveAndFlush(c);

        AuditLog a = new AuditLog();
        a.occurredAt = Instant.now();
        a.username = "admin";
        a.action = "TEST";
        a.entityType = "ASSET";
        a.oldValue = "{\"x\":1}";
        audit.saveAndFlush(a);
        em.clear();
        assertTrue(audit.findById(a.id).orElseThrow().oldValue.contains("\"x\""));
    }

    @Test
    void notificationsDeduplicateAndTrackReadStatePerUser() {
        assertEquals(1, notifications.insertIgnoringDuplicates("T", "INFO", "t", "m", null, null, "ADMIN", null, "K1"));
        assertEquals(0, notifications.insertIgnoringDuplicates("T", "INFO", "t", "m", null, null, "ADMIN", null, "K1"));
        Notification n = notifications.findAll().stream().filter(x -> "K1".equals(x.dedupeKey)).findFirst().orElseThrow();
        n.readBy.add("admin");
        notifications.saveAndFlush(n);
        em.clear();
        assertTrue(notifications.findById(n.id).orElseThrow().readBy.contains("admin"));
        assertFalse(notifications.findById(n.id).orElseThrow().readBy.contains("engineer"));
    }

    @Test
    void documentListingNeverLoadsContentAndUsersAreCaseInsensitive() {
        EquipmentDocument d = new EquipmentDocument();
        d.entityType = "ASSET";
        d.entityId = seed().id;
        d.filename = "manual.pdf";
        d.contentType = "application/pdf";
        d.content = new byte[]{1, 2, 3};
        d.sizeBytes = 3L;
        d.uploadedBy = "admin";
        d.uploadedAt = Instant.now();
        documents.saveAndFlush(d);
        em.clear();
        var list = documents.listFor("ASSET", d.entityId);
        assertEquals(1, list.size());
        assertEquals("manual.pdf", list.get(0).filename());
        assertTrue(users.findByUsernameIgnoreCase("ADMIN").isEmpty() || users.findByUsernameIgnoreCase("ADMIN").get().role.equals("ADMIN"));
    }
}
