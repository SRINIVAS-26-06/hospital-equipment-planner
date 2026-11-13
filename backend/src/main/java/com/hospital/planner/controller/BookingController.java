package com.hospital.planner.controller;

import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.BookingDto;
import com.hospital.planner.service.BookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    /** The booker is always the signed-in user. `purpose` must never contain patient identifiers. */
    public record BookingRequest(@NotNull Long assetId, @NotNull Instant startAt, @NotNull Instant endAt, @Size(max = 200) String purpose) {}

    public record CancelRequest(@Size(max = 300) String reason) {}

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingDto book(@Valid @RequestBody BookingRequest r) {
        return service.book(r.assetId(), r.startAt(), r.endAt(), r.purpose());
    }

    @PostMapping("/{id}/cancel")
    public BookingDto cancel(@PathVariable long id, @Valid @RequestBody(required = false) CancelRequest r) {
        return service.cancel(id, r == null ? null : r.reason());
    }

    @GetMapping("/{id}")
    public BookingDto get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping
    public PageResult<BookingDto> list(@RequestParam(required = false) Long assetId, @RequestParam(required = false) Long departmentId,
                                       @RequestParam(required = false) String status, @RequestParam(required = false) String user,
                                       @RequestParam(defaultValue = "false") boolean mine, @RequestParam(required = false) Instant from,
                                       @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "false") boolean upcoming,
                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.list(assetId, departmentId, status, user, mine, from, to, upcoming, page, size);
    }
}
