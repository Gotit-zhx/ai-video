package com.yingdao.aicommerce.task.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.task.application.ActiveUserTaskService;
import com.yingdao.aicommerce.task.domain.ActiveUserTask;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/tasks")
public class ActiveUserTaskController {

    private final ActiveUserTaskService service;

    public ActiveUserTaskController(ActiveUserTaskService service) {
        this.service = service;
    }

    @GetMapping("/current")
    public ApiResponse<CurrentTaskResponse> current(
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        ActiveUserTask task = service.current(
                principal.organizationId(),
                principal.userId()
        );
        return ApiResponse.ok(task == null ? null : new CurrentTaskResponse(
                task.getTaskId(),
                task.getTaskType(),
                task.getStatus(),
                task.getCreatedAt(),
                task.getLeaseExpiresAt()
        ));
    }

    public record CurrentTaskResponse(
            String taskId,
            String taskType,
            String status,
            Instant createdAt,
            Instant leaseExpiresAt
    ) {
    }
}
