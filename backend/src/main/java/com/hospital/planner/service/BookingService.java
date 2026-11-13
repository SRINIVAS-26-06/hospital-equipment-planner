package com.hospital.planner.service;

import com.hospital.planner.common.CurrentUser;
import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.BookingDto;
import com.hospital.planner.entity.Booking;
import com.hospital.planner.entity.BookingStatus;
import com.hospital.planner.entity.Equipment;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.repository.BookingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {
    private final BookingRepository bookings;
    private final AvailabilityService availability;
    private final AuditService audit;
    private final NotificationService notify;
    private final EntityManager em;
    private final Clock clock;

    public BookingService(BookingRepository bookings, AvailabilityService availability, AuditService audit, NotificationService notify,
                          EntityManager em, Clock clock) {
        this.bookings = bookings;
        this.availability = availability;
        this.audit = audit;
        this.notify = notify;
        this.em = em;
        this.clock = clock;
    }

    /** The equipment row is locked first, so concurrent book / maintenance / transfer calls on it run one at a time. */
    @Transactional
    public BookingDto book(long assetId, Instant start, Instant end, String purpose) {
        if (start == null || end == null || !end.isAfter(start)) throw ApiException.bad("endAt must be after startAt");
        Instant now = clock.instant();
        if (start.isBefore(now.minus(5, ChronoUnit.MINUTES))) throw ApiException.unprocessable("PAST_BOOKING", "Bookings cannot start in the past");
        if (Duration.between(start, end).compareTo(Duration.ofDays(7)) > 0) throw ApiException.unprocessable("BOOKING_TOO_LONG", "A booking cannot exceed 7 days");
        Equipment a = availability.lock(assetId);
        availability.assertBookable(a, start, end);
        Booking b = new Booking();
        b.equipment = a;
        b.department = a.department;
        b.requestedBy = CurrentUser.username();
        b.startAt = start;
        b.endAt = end;
        b.purpose = purpose;
        try {
            bookings.saveAndFlush(b);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("BOOKING_CONFLICT", a.assetTag + " is already booked during that time");   // exclusion constraint backstop
        }
        em.refresh(b);   // reads database-generated columns (created_at)
        BookingDto dto = dto(b);
        audit.log("BOOKING_CREATED", "BOOKING", b.id, null, dto);
        return dto;
    }

    @Transactional
    public BookingDto cancel(long id, String reason) {
        Booking b = bookings.findById(id).orElseThrow(() -> ApiException.notFound("Booking " + id));
        availability.lock(b.equipment.id);
        em.refresh(b);
        if (!CurrentUser.ownerOrAdmin(b.requestedBy)) throw ApiException.forbidden("You can only cancel your own bookings");
        Instant now = clock.instant();
        if (b.status != BookingStatus.ACTIVE) throw ApiException.conflict("NOT_ACTIVE", "Booking is already " + b.status);
        if (!b.endAt.isAfter(now)) throw ApiException.conflict("ALREADY_ENDED", "Booking has already ended");
        b.status = BookingStatus.CANCELLED;
        b.cancelledBy = CurrentUser.username();
        b.cancelledAt = now;
        b.cancelReason = reason;
        bookings.saveAndFlush(b);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "CANCELLED");
        after.put("reason", String.valueOf(reason));
        audit.log("BOOKING_CANCELLED", "BOOKING", id, Map.of("status", "ACTIVE"), after);
        if (!CurrentUser.username().equals(b.requestedBy)) {
            notify.toUser(b.requestedBy, "BOOKING_CANCELLED", "WARNING", "Booking cancelled",
                    "Your booking #" + id + " was cancelled by " + CurrentUser.username(), "BOOKING", id, null);
        }
        return dto(b);
    }

    /**
     * Cancels active bookings because of a lifecycle/safety event (transfer, retirement, out of service, unsafe breakdown, failed
     * calibration) and notifies each owner. The caller holds the equipment lock.
     */
    @Transactional
    public List<Booking> cancelAffected(long assetId, BookingStatus newStatus, String reason, boolean includeOngoing) {
        Instant now = clock.instant();
        List<Booking> rows = em.createQuery("select b from Booking b where b.equipment.id = :a and b.status = :active and "
                + (includeOngoing ? "b.endAt > :now" : "b.startAt >= :now"), Booking.class)
                .setParameter("a", assetId).setParameter("active", BookingStatus.ACTIVE).setParameter("now", now).getResultList();
        for (Booking b : rows) {
            b.status = newStatus;
            b.cancelledBy = CurrentUser.username();
            b.cancelledAt = now;
            b.cancelReason = reason;
            notify.toUser(b.requestedBy, "BOOKING_CANCELLED", "WARNING", "Booking cancelled",
                    "Your booking #" + b.id + " was cancelled: " + reason, "BOOKING", b.id, null);
        }
        bookings.flush();
        return rows;
    }

    private BookingDto dto(Booking b) {
        boolean active = b.status == BookingStatus.ACTIVE && b.endAt.isAfter(clock.instant());
        return BookingDto.of(b, active && CurrentUser.ownerOrAdmin(b.requestedBy));
    }

    @Transactional(readOnly = true)
    public BookingDto get(long id) {
        return dto(bookings.findById(id).orElseThrow(() -> ApiException.notFound("Booking " + id)));
    }

    @Transactional(readOnly = true)
    public PageResult<BookingDto> list(Long assetId, Long departmentId, String status, String user, boolean mine, Instant from, Instant to,
                                       boolean upcoming, int page, int size) {
        StringBuilder w = new StringBuilder(" where 1=1");
        Map<String, Object> p = new LinkedHashMap<>();
        if (assetId != null) { w.append(" and b.equipment.id = :assetId"); p.put("assetId", assetId); }
        if (departmentId != null) { w.append(" and b.department.id = :dept"); p.put("dept", departmentId); }
        if (status != null && !status.isBlank()) {
            try {
                p.put("status", BookingStatus.valueOf(status));
            } catch (IllegalArgumentException e) {
                throw ApiException.bad("Unknown booking status " + status);
            }
            w.append(" and b.status = :status");
        }
        if (mine) { w.append(" and b.requestedBy = :me"); p.put("me", CurrentUser.username()); }
        else if (user != null && !user.isBlank()) { w.append(" and b.requestedBy = :user"); p.put("user", user); }
        if (from != null) { w.append(" and b.endAt > :from"); p.put("from", from); }
        if (to != null) { w.append(" and b.startAt < :to"); p.put("to", to); }
        if (upcoming) { w.append(" and b.status = :activeStatus and b.endAt > :nowTs"); p.put("activeStatus", BookingStatus.ACTIVE); p.put("nowTs", clock.instant()); }
        int sz = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        TypedQuery<Long> count = em.createQuery("select count(b) from Booking b" + w, Long.class);
        TypedQuery<Booking> data = em.createQuery("select b from Booking b join fetch b.equipment join fetch b.department" + w
                + (upcoming ? " order by b.startAt asc, b.id" : " order by b.startAt desc, b.id desc"), Booking.class);
        p.forEach((k, v) -> { count.setParameter(k, v); data.setParameter(k, v); });
        long total = count.getSingleResult();
        data.setFirstResult(pg * sz);
        data.setMaxResults(sz);
        List<BookingDto> items = data.getResultList().stream().map(this::dto).toList();
        return new PageResult<>(items, pg, sz, total, (int) ((total + sz - 1) / sz));
    }
}
