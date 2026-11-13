package com.hospital.planner.controller;

import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.EquipmentDto;
import com.hospital.planner.dto.EquipmentRequest;
import com.hospital.planner.dto.HistoryDto;
import com.hospital.planner.service.EquipmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assets")
public class AssetController {
    public record TransferRequest(@NotNull Long toDepartmentId, @Size(max = 300) String reason) {}

    public record RetireRequest(@NotBlank @Size(max = 300) String reason) {}

    public record LifecycleRequest(@NotBlank String status, @Size(max = 300) String reason) {}

    private final EquipmentService service;

    public AssetController(EquipmentService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<EquipmentDto> search(@RequestParam(required = false) String q, @RequestParam(required = false) Long departmentId,
                                           @RequestParam(required = false) String category, @RequestParam(required = false) String lifecycle,
                                           @RequestParam(required = false) String availability, @RequestParam(required = false) Integer maintenanceDueDays,
                                           @RequestParam(required = false) Integer calibrationDueDays, @RequestParam(required = false) Integer warrantyExpiringDays,
                                           @RequestParam(defaultValue = "assetTag") String sort, @RequestParam(defaultValue = "false") boolean desc,
                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.search(q, departmentId, category, lifecycle, availability, maintenanceDueDays, calibrationDueDays, warrantyExpiringDays, sort, desc, page, size);
    }

    @GetMapping("/categories")
    public List<String> categories() {
        return service.categories();
    }

    @GetMapping("/{id}")
    public EquipmentDto get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping("/{id}/availability")
    public Map<String, Object> availability(@PathVariable long id, @RequestParam(required = false) Instant at) {
        return service.availabilityAt(id, at);
    }

    @GetMapping("/{id}/history")
    public List<HistoryDto> history(@PathVariable long id, @RequestParam(defaultValue = "200") int limit) {
        return service.history(id, limit);
    }

    @GetMapping("/{id}/department-history")
    public List<Map<String, Object>> departmentHistory(@PathVariable long id) {
        return service.departmentHistory(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EquipmentDto create(@Valid @RequestBody EquipmentRequest d) {
        return service.create(d);
    }

    @PutMapping("/{id}")
    public EquipmentDto update(@PathVariable long id, @Valid @RequestBody EquipmentRequest d) {
        return service.update(id, d);
    }

    @PostMapping("/{id}/transfer")
    public Map<String, Object> transfer(@PathVariable long id, @Valid @RequestBody TransferRequest r) {
        return service.transfer(id, r.toDepartmentId(), r.reason());
    }

    @PostMapping("/{id}/retire")
    public EquipmentDto retire(@PathVariable long id, @Valid @RequestBody RetireRequest r) {
        return service.retire(id, r.reason());
    }

    @PostMapping("/{id}/lifecycle")
    public EquipmentDto lifecycle(@PathVariable long id, @Valid @RequestBody LifecycleRequest r) {
        return service.setLifecycle(id, r.status(), r.reason());
    }
}
