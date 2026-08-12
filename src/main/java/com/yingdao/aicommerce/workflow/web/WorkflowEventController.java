package com.yingdao.aicommerce.workflow.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.workflow.application.WorkflowEventService;
import com.yingdao.aicommerce.workflow.application.WorkflowJobService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/workflow-jobs")
public class WorkflowEventController {

    private final WorkflowJobService jobService;
    private final WorkflowEventService eventService;

    public WorkflowEventController(
            WorkflowJobService jobService,
            WorkflowEventService eventService
    ) {
        this.jobService = jobService;
        this.eventService = eventService;
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") long after,
            @AuthenticationPrincipal PlatformPrincipal principal
    ) {
        jobService.get(id, principal.organizationId());
        return eventService.subscribe(id, principal.organizationId(), Math.max(0, after));
    }
}
