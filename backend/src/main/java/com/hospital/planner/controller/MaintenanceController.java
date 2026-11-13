package com.hospital.planner.controller;

import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.MaintenanceDto;
import com.hospital.planner.service.MaintenanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {
    public record ScheduleRequest(@NotNull Long assetId, @Size(max = 40) String kind, @NotNull Instant startAt, @NotNull Instant endAt, String priority,
                                  String assignedEngineer, List<String> checklist, @Size(max = 1000) String notes) {}

    public record AssignRequest(@NotBlank String engineer) {}

    public record ProgressRequest(List<MaintenanceService.ChecklistItem> checklist, @Size(max = 4000) String workPerformed,
                                  @Size(max = 2000) String remarks, @Size(max = 1000) String partsReplaced) {}

    public record CompleteRequest(List<MaintenanceService.ChecklistItem> checklist, @Size(max = 4000) String workPerformed,
                                  @Size(max = 2000) String remarks, @Size(max = 1000) String partsReplaced, Instant nextDueDate) {}

    public record CancelRequest(@Size(max = 300) String reason) {}

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<MaintenanceDto> list(@RequestParam(required = false) Long assetId, @RequestParam(required = false) Long departmentId,
                                           @RequestParam(required = false) String status, @RequestParam(required = false) String engineer,
                                           @RequestParam(defaultValue = "false") boolean mine, @RequestParam(required = false) String priority,
                                           @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.list(assetId, departmentId, status, engineer, mine, priority, from, to, page, size);
    }

    @GetMapping("/{id}")
    public MaintenanceDto get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceDto schedule(@Valid @RequestBody ScheduleRequest r) {
        return service.schedule(r.assetId(), r.kind(), r.startAt(), r.endAt(), r.priority(), r.assignedEngineer(), r.checklist(), r.notes());
    }

    @PostMapping("/{id}/assign")
    public MaintenanceDto assign(@PathVariable long id, @Valid @RequestBody AssignRequest r) {
        return service.assign(id, r.engineer());
    }

    @PostMapping("/{id}/start")
    public MaintenanceDto start(@PathVariable long id) {
        return service.start(id);
    }

    @PostMapping("/{id}/complete")
    public MaintenanceDto complete(@PathVariable long id, @Valid @RequestBody CompleteRequest r) {
        return service.complete(id, r.checklist(), r.workPerformed(), r.remarks(), r.partsReplaced(), r.nextDueDate());
    }

    @PostMapping("/{id}/cancel")
    public MaintenanceDto cancel(@PathVariable long id, @Valid @RequestBody(required = false) CancelRequest r) {
        return service.cancel(id, r == null ? null : r.reason());
    }

    @PatchMapping("/{id}")
    public MaintenanceDto progress(@PathVariable long id, @Valid @RequestBody ProgressRequest r) {
        return service.updateProgress(id, r.checklist(), r.workPerformed(), r.remarks(), r.partsReplaced());
    }
}
