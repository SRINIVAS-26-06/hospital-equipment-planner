package com.hospital.planner.controller;

import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.UsageDto;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.service.UsageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/usage")
public class UsageController {
    /** Patient fields are deliberately absent; Jackson ignores unknown properties in the payload. */
    public record UsageItem(@NotBlank String assetRef, @NotNull Instant startAt, @NotNull Instant endAt, String source) {}

    public record StartRequest(@NotNull Long assetId) {}

    private final UsageService service;

    public UsageController(UsageService service) {
        this.service = service;
    }

    @PostMapping("/start")
    @ResponseStatus(HttpStatus.CREATED)
    public UsageDto start(@Valid @RequestBody StartRequest r) {
        return service.startSession(r.assetId());
    }

    @PostMapping("/{id}/end")
    public UsageDto end(@PathVariable long id) {
        return service.endSession(id);
    }

    /** JSON batch (kept from the previous release). Each row is accepted or rejected on its own. */
    @PostMapping
    public Map<String, Object> batch(@RequestBody List<@Valid UsageItem> items) {
        if (items.size() > UsageService.MAX_ROWS) throw ApiException.unprocessable("TOO_MANY_ROWS", "Too many rows");
        List<UsageService.RawRow> rows = new ArrayList<>();
        int n = 1;
        for (UsageItem i : items) rows.add(new UsageService.RawRow(n++, i.assetRef(), String.valueOf(i.startAt()), String.valueOf(i.endAt())));
        String source = items.isEmpty() || items.get(0).source() == null ? "api" : items.get(0).source();
        return service.ingestRows(source, null, null, rows, List.of());
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file, @RequestParam(required = false) String source) throws IOException {
        if (file.isEmpty()) throw ApiException.bad("The uploaded file is empty");
        return service.ingestCsv(file.getOriginalFilename(), file.getBytes(), source);
    }

    @GetMapping
    public PageResult<UsageDto> history(@RequestParam(required = false) Long assetId, @RequestParam(required = false) Long departmentId,
                                        @RequestParam(required = false) String user, @RequestParam(defaultValue = "false") boolean mine,
                                        @RequestParam(defaultValue = "false") boolean activeOnly, @RequestParam(required = false) Instant from,
                                        @RequestParam(required = false) Instant to, @RequestParam(required = false) Long batchId,
                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.history(assetId, departmentId, user, mine, activeOnly, from, to, batchId, page, size);
    }

    @GetMapping("/batches")
    public PageResult<Map<String, Object>> batches(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.batches(page, size);
    }

    @GetMapping("/batches/{id}")
    public Map<String, Object> batch(@PathVariable long id, @RequestParam(required = false) String outcome,
                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.batchDetail(id, outcome, page, size);
    }
}
