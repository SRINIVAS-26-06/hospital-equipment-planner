package com.hospital.planner.controller;

import com.hospital.planner.common.PageResult;
import com.hospital.planner.service.DowntimeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/downtime")
public class DowntimeController {
    public record DowntimeRequest(@NotNull Long assetId, @NotNull Instant startAt, @NotNull Instant endAt, @NotBlank String reason, @Size(max = 300) String note) {}

    private final DowntimeService service;

    public DowntimeController(DowntimeService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> record(@Valid @RequestBody DowntimeRequest r) {
        return service.record(r.assetId(), r.startAt(), r.endAt(), r.reason());
    }

    @GetMapping
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) Long assetId, @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "25") int size) {
        return service.list(assetId, page, size);
    }
}
