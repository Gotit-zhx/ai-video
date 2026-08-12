package com.yingdao.aicommerce.workflow.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.task.application.ActiveUserTaskService;
import com.yingdao.aicommerce.workflow.domain.WorkflowJob;
import com.yingdao.aicommerce.workflow.domain.WorkflowJobStatus;
import com.yingdao.aicommerce.workflow.domain.WorkflowJobType;
import com.yingdao.aicommerce.workflow.mapper.WorkflowJobMapper;
import com.yingdao.aicommerce.workflow.web.CreateWorkflowJobRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowJobServiceTest {

    private final WorkflowJobMapper mapper = mock(WorkflowJobMapper.class);
    private final ActiveUserTaskService activeTaskService =
            mock(ActiveUserTaskService.class);
    private final WorkflowQueuePublisher queuePublisher =
            mock(WorkflowQueuePublisher.class);
    private final WorkflowEventService eventService =
            mock(WorkflowEventService.class);
    private final WorkflowJobService service = new WorkflowJobService(
            mapper,
            activeTaskService,
            queuePublisher,
            eventService
    );
    private final PlatformPrincipal principal = new PlatformPrincipal(
            "user-1",
            "organization-1",
            "heyi",
            "13800000000",
            "平台管理员",
            "password",
            true,
            java.util.List.of("ADMIN"),
            java.util.List.of("workflow:manage")
    );

    @Test
    void createsDraftWorkflowJob() {
        when(mapper.insert(any(WorkflowJob.class))).thenReturn(1);

        WorkflowJob job = service.create(new CreateWorkflowJobRequest(
                WorkflowJobType.IMAGE_TO_VIDEO,
                "夏季商品宣传视频",
                Map.of("script", "展示商品的三个核心卖点"),
                new BigDecimal("50.00")
        ), principal);

        assertThat(job.getId()).isNotNull();
        assertThat(job.getJobNo()).startsWith("JOB-");
        assertThat(job.getStatus()).isEqualTo(WorkflowJobStatus.DRAFT);
        assertThat(job.getInputPayload()).containsEntry("script", "展示商品的三个核心卖点");
    }

    @Test
    void rejectsIllegalStatusTransition() {
        String id = "4d60d203-fc4b-4037-8094-30de7d80916e";
        WorkflowJob job = new WorkflowJob(
                "JOB-TEST",
                principal.organizationId(),
                WorkflowJobType.IMAGE_TO_VIDEO,
                "测试任务",
                Map.of(),
                BigDecimal.TEN,
                principal.userId()
        );
        when(mapper.selectOne(any())).thenReturn(job);

        assertThatThrownBy(() -> service.updateStatus(
                id,
                WorkflowJobStatus.COMPLETED,
                principal
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Illegal workflow transition");
    }
}
