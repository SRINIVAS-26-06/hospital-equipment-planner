package com.hospital.planner.controller;

import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.CalibrationDto;
import com.hospital.planner.service.CalibrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/calibrations")
public class CalibrationController {
    public record ScheduleRequest(@NotNull Long assetId, @NotNull Instant scheduledFor, String assignedEngineer) {}

    public record AssignRequest(@NotBlank String engineer) {}

    public record CompleteRequest(@NotBlank String result, Instant performedAt, @Size(max = 100) String certificateNumber,
                                  @Size(max = 2000) String remarks, Instant nextDueDate) {}

    public record CancelRequest(@Size(max = 300) String reason) {}

    private final CalibrationService service;

    public CalibrationController(CalibrationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<CalibrationDto> list(@RequestParam(required = false) Long assetId, @RequestParam(required = false) Long departmentId,
                                           @RequestParam(required = false) String status, @RequestParam(required = false) String result,
                                           @RequestParam(defaultValue = "false") boolean mine,
                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.list(assetId, departmentId, status, result, mine, page, size);
    }

    @GetMapping("/{id}")
    public CalibrationDto get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CalibrationDto schedule(@Valid @RequestBody ScheduleRequest r) {
        return service.schedule(r.assetId(), r.scheduledFor(), r.assignedEngineer());
    }

    @PostMapping("/{id}/assign")
    public CalibrationDto assign(@PathVariable long id, @Valid @RequestBody AssignRequest r) {
        return service.assign(id, r.engineer());
    }

    @PostMapping("/{id}/complete")
    public CalibrationDto complete(@PathVariable long id, @Valid @RequestBody CompleteRequest r) {
        return service.complete(id, r.result(), r.performedAt(), r.certificateNumber(), r.remarks(), r.nextDueDate());
    }

    @PostMapping("/{id}/cancel")
    public CalibrationDto cancel(@PathVariable long id, @Valid @RequestBody(required = false) CancelRequest r) {
        return service.cancel(id, r == null ? null : r.reason());
    }
}
