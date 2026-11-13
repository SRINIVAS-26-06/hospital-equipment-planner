package com.hospital.planner.controller;

import com.hospital.planner.service.DashboardService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> summary() {
        return service.summary();
    }

    @GetMapping("/charts")
    public Map<String, Object> charts() {
        return service.charts();
    }
}
