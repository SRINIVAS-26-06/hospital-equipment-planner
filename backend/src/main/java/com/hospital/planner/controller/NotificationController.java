package com.hospital.planner.controller;

import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.NotificationDto;
import com.hospital.planner.notify.AlertScanner;
import com.hospital.planner.service.NotificationService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    private final AlertScanner scanner;

    public NotificationController(NotificationService service, AlertScanner scanner) {
        this.service = service;
        this.scanner = scanner;
    }

    @GetMapping
    public PageResult<NotificationDto> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unread() {
        return service.summary();
    }

    @PostMapping("/{id}/read")
    public Map<String, Object> read(@PathVariable long id) {
        service.markRead(id);
        return service.summary();
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll() {
        service.markAllRead();
        return service.summary();
    }

    /** ADMIN: run the due/overdue/warranty scan now instead of waiting for the schedule. */
    @PostMapping("/scan")
    public Map<String, Object> scan() {
        return Map.of("created", scanner.scan());
    }
}
