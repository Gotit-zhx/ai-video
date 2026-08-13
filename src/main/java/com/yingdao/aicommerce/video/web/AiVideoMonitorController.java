package com.yingdao.aicommerce.video.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.video.application.AiVideoMonitorService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai-video-monitor")
public class AiVideoMonitorController {
    private final AiVideoMonitorService service;

    public AiVideoMonitorController(AiVideoMonitorService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AiVideoMonitorService.MonitorSnapshot> snapshot(
            @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.snapshot(principal));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<AiVideoMonitorService.TaskMonitor> detail(
            @PathVariable String taskId, @AuthenticationPrincipal PlatformPrincipal principal) {
        return ApiResponse.ok(service.detail(taskId, principal));
    }
}
