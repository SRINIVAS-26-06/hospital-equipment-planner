package com.hospital.planner.controller;

import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.WorkOrderDto;
import com.hospital.planner.service.WorkOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {
    /** Describe the fault only. Never put patient names or MRNs in these fields. */
    public record ReportRequest(@NotNull Long assetId, @NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 2000) String description,
                                String severity, String priority, Boolean unsafe) {}

    public record TriageRequest(String priority, String severity, Boolean unsafe, @Size(max = 500) String note) {}

    public record AssignRequest(@NotBlank String engineer, @Size(max = 500) String note) {}

    public record ResolveRequest(@NotBlank @Size(max = 4000) String repairDetails, @Size(max = 1000) String partsReplaced, @Size(max = 2000) String resolutionRemarks) {}

    public record NoteRequest(@Size(max = 500) String note) {}

    private final WorkOrderService service;

    public WorkOrderController(WorkOrderService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkOrderDto report(@Valid @RequestBody ReportRequest r) {
        return service.report(r.assetId(), r.title(), r.description(), r.severity(), r.priority(), r.unsafe());
    }

    @GetMapping
    public PageResult<WorkOrderDto> list(@RequestParam(required = false) Long assetId, @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "false") boolean openOnly, @RequestParam(defaultValue = "false") boolean mine,
                                         @RequestParam(defaultValue = "false") boolean reportedByMe, @RequestParam(required = false) String priority,
                                         @RequestParam(required = false) String severity,
                                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.list(assetId, status, openOnly, mine, reportedByMe, priority, severity, page, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/triage")
    public WorkOrderDto triage(@PathVariable long id, @Valid @RequestBody TriageRequest r) {
        return service.triage(id, r.priority(), r.severity(), r.unsafe(), r.note());
    }

    @PostMapping("/{id}/assign")
    public WorkOrderDto assign(@PathVariable long id, @Valid @RequestBody AssignRequest r) {
        return service.assign(id, r.engineer(), r.note());
    }

    @PostMapping("/{id}/start")
    public WorkOrderDto start(@PathVariable long id) {
        return service.start(id);
    }

    @PostMapping("/{id}/resolve")
    public WorkOrderDto resolve(@PathVariable long id, @Valid @RequestBody ResolveRequest r) {
        return service.resolve(id, r.repairDetails(), r.partsReplaced(), r.resolutionRemarks());
    }

    @PostMapping("/{id}/verify")
    public WorkOrderDto verify(@PathVariable long id, @Valid @RequestBody(required = false) NoteRequest r) {
        return service.verify(id, r == null ? null : r.note());
    }

    @PostMapping("/{id}/reopen")
    public WorkOrderDto reopen(@PathVariable long id, @Valid @RequestBody(required = false) NoteRequest r) {
        return service.reopen(id, r == null ? null : r.note());
    }

    @PostMapping("/{id}/close")
    public WorkOrderDto close(@PathVariable long id, @Valid @RequestBody(required = false) NoteRequest r) {
        return service.close(id, r == null ? null : r.note());
    }
}
