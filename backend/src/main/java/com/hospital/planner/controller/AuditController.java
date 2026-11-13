package com.hospital.planner.controller;

import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.AuditDto;
import com.hospital.planner.service.AuditService;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN only (enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<AuditDto> list(@RequestParam(required = false) String username, @RequestParam(required = false) String entityType,
                                     @RequestParam(required = false) String entityId, @RequestParam(required = false) String action,
                                     @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.search(username, entityType, entityId, action, from, to, page, size);
    }
}
