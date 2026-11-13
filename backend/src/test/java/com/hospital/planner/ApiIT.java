package com.hospital.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** End-to-end through the real security chain, services and PostgreSQL. Needs Docker. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class ApiIT {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;

    static RequestPostProcessor admin = httpBasic("admin", "admin-dev");
    static RequestPostProcessor engineer = httpBasic("engineer", "engineer-dev");
    static RequestPostProcessor staff = httpBasic("staff", "staff-dev");
    static RequestPostProcessor viewer = httpBasic("viewer", "viewer-dev");
    static int seq = 0;

    private static Instant in(long hours) {
        return Instant.now().plus(hours, ChronoUnit.HOURS);
    }

    private MvcResult call(String method, String url, RequestPostProcessor who, String body) throws Exception {
        var b = method.equals("GET") ? get(url) : post(url);
        b = b.with(who);
        if (body != null) b = b.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(b).andReturn();
    }

    private int status(String method, String url, RequestPostProcessor who, String body) throws Exception {
        return call(method, url, who, body).getResponse().getStatus();
    }

    private String code(String url, RequestPostProcessor who, String body) throws Exception {
        MvcResult r = call("POST", url, who, body);
        if (r.getResponse().getStatus() < 300) return "OK";
        String text = r.getResponse().getContentAsString();
        // errors from the security filter chain (401/403) have no JSON body; service errors carry {code, message}
        return text.isBlank() ? "HTTP_" + r.getResponse().getStatus() : JsonPath.read(text, "$.code");
    }

    private long newAsset() throws Exception {
        long dept = ((Number) JsonPath.read(call("GET", "/api/departments", admin, null).getResponse().getContentAsString(), "$[0].id")).longValue();
        String body = "{\"assetTag\":\"IT-%d\",\"name\":\"Test\",\"category\":\"Test\",\"departmentId\":%d}".formatted(++seq, dept);
        MvcResult r = call("POST", "/api/assets", admin, body);
        assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String booking(long asset, long fromH, long toH) {
        return "{\"assetId\":%d,\"startAt\":\"%s\",\"endAt\":\"%s\"}".formatted(asset, in(fromH), in(toH));
    }

    @Test
    void permissionsAreEnforcedByTheBackendForEveryRole() throws Exception {
        long a = newAsset();
        assertEquals(401, mvc.perform(get("/api/assets")).andReturn().getResponse().getStatus());
        assertEquals(200, status("GET", "/api/assets", viewer, null));
        assertEquals(403, status("POST", "/api/bookings", viewer, booking(a, 5, 6)));
        assertEquals(403, status("GET", "/api/bookings", viewer, null));
        assertEquals(403, status("POST", "/api/assets", staff, "{}"));
        assertEquals(403, status("POST", "/api/assets/" + a + "/transfer", engineer, "{\"toDepartmentId\":1}"));
        assertEquals(403, status("POST", "/api/bookings", engineer, booking(a, 5, 6)));
        assertEquals(403, status("GET", "/api/audit", engineer, null));
        assertEquals(403, status("GET", "/api/users", staff, null));
        assertEquals(200, status("GET", "/api/audit", admin, null));
        assertEquals(403, status("POST", "/api/maintenance", staff, "{}"));
    }

    @Test
    void bookingsConflictOnlyOwnersOrAdminsCancelAndPastIsRejected() throws Exception {
        long a = newAsset();
        MvcResult first = call("POST", "/api/bookings", staff, booking(a, 10, 12));
        assertEquals(201, first.getResponse().getStatus());
        long id = ((Number) JsonPath.read(first.getResponse().getContentAsString(), "$.id")).longValue();
        assertEquals("BOOKING_CONFLICT", code("/api/bookings", admin, booking(a, 11, 13)));
        assertEquals("OK", code("/api/bookings", admin, booking(a, 12, 14)));
        assertEquals("PAST_BOOKING", code("/api/bookings", staff, booking(a, -3, 1)));
        assertEquals("FORBIDDEN", code("/api/bookings/" + id + "/cancel", viewer, "{}"));
        assertEquals("OK", code("/api/bookings/" + id + "/cancel", staff, "{}"));
    }

    @Test
    void futureMaintenanceDoesNotBlockBeforeItsStartAndCountersResetOnlyOnCompletion() throws Exception {
        long a = newAsset();
        MvcResult m = call("POST", "/api/maintenance", engineer, "{\"assetId\":%d,\"startAt\":\"%s\",\"endAt\":\"%s\"}".formatted(a, in(48), in(52)));
        assertEquals(201, m.getResponse().getStatus());
        long mid = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.id")).longValue();
        assertEquals("AVAILABLE", JsonPath.read(call("GET", "/api/assets/" + a + "/availability", viewer, null).getResponse().getContentAsString(), "$.availability"));
        assertEquals("OK", code("/api/bookings", staff, booking(a, 1, 3)));
        assertEquals("MAINTENANCE_LOCKOUT", code("/api/bookings", staff, booking(a, 49, 50)));
        assertEquals("INVALID_STATE", code("/api/maintenance/" + mid + "/complete", engineer, "{\"workPerformed\":\"x\"}"));   // must be started first
        assertEquals("OK", code("/api/bookings", staff, booking(a, 60, 61)));                     // after the window is fine
    }

    @Test
    void unsafeBreakdownBlocksBookingUntilVerified() throws Exception {
        long a = newAsset();
        assertEquals("OK", code("/api/bookings", staff, booking(a, 5, 6)));
        MvcResult r = call("POST", "/api/work-orders", staff, "{\"assetId\":%d,\"title\":\"Alarm fails\",\"description\":\"No alarm\",\"severity\":\"CRITICAL\",\"unsafe\":true}".formatted(a));
        assertEquals(201, r.getResponse().getStatus());
        long id = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
        assertEquals("UNDER_REPAIR", code("/api/bookings", staff, booking(a, 20, 21)));
        assertEquals("OK", code("/api/work-orders/" + id + "/triage", engineer, "{}"));
        assertEquals("OK", code("/api/work-orders/" + id + "/assign", engineer, "{\"engineer\":\"engineer\"}"));
        assertEquals("OK", code("/api/work-orders/" + id + "/start", engineer, "{}"));
        assertEquals("OK", code("/api/work-orders/" + id + "/resolve", engineer, "{\"repairDetails\":\"Replaced buzzer\"}"));
        assertEquals("UNDER_REPAIR", code("/api/bookings", staff, booking(a, 20, 21)));      // unsafe: still blocked until verified
        assertEquals("FORBIDDEN", code("/api/work-orders/" + id + "/verify", engineer, "{}"));  // the engineer cannot verify own repair
        assertEquals("OK", code("/api/work-orders/" + id + "/verify", staff, "{}"));
        assertEquals("OK", code("/api/bookings", staff, booking(a, 20, 21)));
    }

    @Test
    void failedCalibrationRestrictsEquipmentUntilItPasses() throws Exception {
        long a = newAsset();
        long c = ((Number) JsonPath.read(call("POST", "/api/calibrations", engineer, "{\"assetId\":%d,\"scheduledFor\":\"%s\"}".formatted(a, in(1))).getResponse().getContentAsString(), "$.id")).longValue();
        assertEquals("OK", code("/api/calibrations/" + c + "/complete", engineer, "{\"result\":\"FAIL\",\"remarks\":\"Out of tolerance\"}"));
        assertEquals("CALIBRATION_FAILED", code("/api/bookings", staff, booking(a, 5, 6)));
        long c2 = ((Number) JsonPath.read(call("POST", "/api/calibrations", engineer, "{\"assetId\":%d,\"scheduledFor\":\"%s\"}".formatted(a, in(2))).getResponse().getContentAsString(), "$.id")).longValue();
        assertEquals("OK", code("/api/calibrations/" + c2 + "/complete", engineer, "{\"result\":\"PASS\",\"certificateNumber\":\"CERT-1\"}"));
        assertEquals("OK", code("/api/bookings", staff, booking(a, 5, 6)));
    }

    @Test
    void csvUploadKeepsValidRowsRejectsBadOnesAndNeverStoresPatientColumns() throws Exception {
        long a = newAsset();
        String tag = JsonPath.read(call("GET", "/api/assets/" + a, admin, null).getResponse().getContentAsString(), "$.assetTag");
        String csv = "Asset ID,Start Time,End Time,Patient Name,MRN\n"
                + tag + ",2030-01-01T08:00:00Z,2030-01-01T09:00:00Z,Jane Doe,12345\n"    // future-dated: rejected FUTURE_END
                + tag + ",2024-01-01T08:00:00Z,2024-01-01T09:00:00Z,Jane Doe,12345\n"    // ok
                + tag + ",2024-01-01T08:00:00Z,2024-01-01T09:00:00Z,John Roe,67890\n"    // exact duplicate
                + "NOPE-9,2024-01-02T08:00:00Z,2024-01-02T09:00:00Z,x,1\n"               // unknown asset
                + tag + ",garbage,2024-01-03T09:00:00Z,x,1\n";                           // bad timestamp
        MockMultipartFile f = new MockMultipartFile("file", "usage.csv", "text/csv", csv.getBytes());
        MvcResult r = mvc.perform(multipart("/api/usage/upload").file(f).with(staff)).andReturn();
        String body = r.getResponse().getContentAsString();
        assertEquals(200, r.getResponse().getStatus(), body);
        assertEquals(1, (int) JsonPath.read(body, "$.accepted"));
        assertEquals("PARTIAL", JsonPath.read(body, "$.status"));
        assertEquals(java.util.List.of("FUTURE_END", "DUPLICATE", "UNKNOWN_ASSET", "BAD_TIMESTAMP"), JsonPath.read(body, "$.rejected[*].reason"));
        assertTrue(!body.contains("Jane") && !body.contains("12345"));
        assertEquals(java.util.List.of("Patient Name", "MRN"), JsonPath.read(body, "$.ignoredColumns"));
        MvcResult again = mvc.perform(multipart("/api/usage/upload").file(f).with(staff)).andReturn();
        assertEquals(0, (int) JsonPath.read(again.getResponse().getContentAsString(), "$.accepted"));   // re-upload cannot double count
        assertEquals(true, JsonPath.read(again.getResponse().getContentAsString(), "$.duplicateFile"));
    }

    @Test
    void manyConcurrentBookingsOfOneSlotHaveExactlyOneWinner() throws Exception {
        long a = newAsset();
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        java.util.List<Future<?>> fs = new java.util.ArrayList<>();
        String body = booking(a, 30, 32);
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                if (code("/api/bookings", staff, body).equals("OK")) ok.incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, ok.get());
    }

    @Test
    void retiredEquipmentCannotBeBookedAndKeepsItsHistory() throws Exception {
        long a = newAsset();
        assertEquals("OK", code("/api/bookings", staff, booking(a, 5, 6)));
        assertEquals("OK", code("/api/assets/" + a + "/retire", admin, "{\"reason\":\"End of life\"}"));
        assertEquals("RETIRED", code("/api/bookings", staff, booking(a, 8, 9)));
        assertEquals(200, status("GET", "/api/assets/" + a + "/history", admin, null));
    }

    // ---- fixes in this change -------------------------------------------------------------

    @Test
    void onlyAdminAndEngineerCanTriageAWorkOrder() throws Exception {
        long a = newAsset();
        MvcResult r = call("POST", "/api/work-orders", staff, "{\"assetId\":%d,\"title\":\"Noise\",\"description\":\"Loud\"}".formatted(a));
        long id = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
        assertEquals("FORBIDDEN", code("/api/work-orders/" + id + "/triage", staff, "{}"));
        assertEquals("FORBIDDEN", code("/api/work-orders/" + id + "/triage", viewer, "{}"));
        assertEquals("OK", code("/api/work-orders/" + id + "/triage", engineer, "{}"));
    }

    @Test
    void workOrderDocumentsBelongToTheReporterNotToOtherStaff() throws Exception {
        long a = newAsset();
        MvcResult r = call("POST", "/api/work-orders", admin, "{\"assetId\":%d,\"title\":\"Leak\",\"description\":\"Drip\"}".formatted(a));
        long adminsOrder = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
        MockMultipartFile f = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3});
        // staff did not report this breakdown: cannot attach to it or list its documents
        assertEquals(403, mvc.perform(multipart("/api/documents").file(f).param("entityType", "WORK_ORDER").param("entityId", "" + adminsOrder).with(staff)).andReturn().getResponse().getStatus());
        assertEquals(403, status("GET", "/api/documents?entityType=WORK_ORDER&entityId=" + adminsOrder, staff, null));
        // staff reported this one: may attach and read
        MvcResult own = call("POST", "/api/work-orders", staff, "{\"assetId\":%d,\"title\":\"Crack\",\"description\":\"Casing\"}".formatted(a));
        long mine = ((Number) JsonPath.read(own.getResponse().getContentAsString(), "$.id")).longValue();
        assertEquals(201, mvc.perform(multipart("/api/documents").file(f).param("entityType", "WORK_ORDER").param("entityId", "" + mine).with(staff)).andReturn().getResponse().getStatus());
        assertEquals(200, status("GET", "/api/documents?entityType=WORK_ORDER&entityId=" + mine, staff, null));
        assertEquals(403, status("GET", "/api/documents?entityType=WORK_ORDER&entityId=" + mine, viewer, null));
    }

    @Test
    void partialUserPatchKeepsOmittedFields() throws Exception {
        long dept = ((Number) JsonPath.read(call("GET", "/api/departments", admin, null).getResponse().getContentAsString(), "$[0].id")).longValue();
        String create = "{\"username\":\"patch.user\",\"password\":\"password-1\",\"fullName\":\"Patch User\",\"email\":\"p@x.org\",\"role\":\"STAFF\",\"departmentId\":%d}".formatted(dept);
        MvcResult c = call("POST", "/api/users", admin, create);
        assertEquals(201, c.getResponse().getStatus(), c.getResponse().getContentAsString());
        long id = ((Number) JsonPath.read(c.getResponse().getContentAsString(), "$.id")).longValue();
        MvcResult p = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/users/" + id).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andReturn();
        String body = p.getResponse().getContentAsString();
        assertEquals(200, p.getResponse().getStatus(), body);
        assertEquals(false, JsonPath.read(body, "$.active"));
        assertEquals("p@x.org", JsonPath.read(body, "$.email"));
        assertEquals("Patch User", JsonPath.read(body, "$.fullName"));
        assertEquals("STAFF", JsonPath.read(body, "$.role"));
        assertEquals(dept, ((Number) JsonPath.read(body, "$.departmentId")).longValue());
    }

    @Test
    void unauthenticatedResponsesAreJsonWithoutABrowserLoginChallenge() throws Exception {
        var res = mvc.perform(get("/api/assets")).andReturn().getResponse();
        assertEquals(401, res.getStatus());
        assertEquals(null, res.getHeader("WWW-Authenticate"));      // no challenge: no browser login box, no cached credentials, no CSRF window
        assertEquals("UNAUTHORIZED", JsonPath.read(res.getContentAsString(), "$.code"));
        assertEquals("FORBIDDEN", JsonPath.read(mvc.perform(get("/api/audit").with(staff)).andReturn().getResponse().getContentAsString(), "$.code"));
    }
}
