package com.yingdao.aicommerce.task.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.task.domain.ActiveUserTask;
import com.yingdao.aicommerce.task.mapper.ActiveUserTaskMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class ActiveUserTaskService {

    private final ActiveUserTaskMapper mapper;

    public ActiveUserTaskService(ActiveUserTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public ActiveUserTask acquire(
            String organizationId,
            String userId,
            String taskId,
            String taskType,
            Duration lease
    ) {
        LambdaQueryWrapper<ActiveUserTask> owner = owner(organizationId, userId);
        ActiveUserTask existing = mapper.selectOne(owner.last("FOR UPDATE"));
        if (existing != null && existing.getLeaseExpiresAt().isAfter(Instant.now())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "你已有一条任务正在执行，请等待完成后再提交下一条"
            );
        }
        if (existing != null) {
            mapper.delete(owner(organizationId, userId));
        }
        ActiveUserTask task = new ActiveUserTask(
                organizationId,
                userId,
                taskId,
                taskType,
                Instant.now().plus(lease)
        );
        try {
            mapper.insert(task);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "你已有一条任务正在执行，请等待完成后再提交下一条"
            );
        }
        return task;
    }

    @Transactional
    public void release(String organizationId, String userId, String taskId) {
        mapper.delete(new LambdaQueryWrapper<ActiveUserTask>()
                .eq(ActiveUserTask::getOrganizationId, organizationId)
                .eq(ActiveUserTask::getUserId, userId)
                .eq(ActiveUserTask::getTaskId, taskId));
    }

    @Transactional
    public void extend(
            String organizationId,
            String userId,
            String taskId,
            Duration lease
    ) {
        LambdaQueryWrapper<ActiveUserTask> query = owner(organizationId, userId)
                .eq(ActiveUserTask::getTaskId, taskId);
        ActiveUserTask task = mapper.selectOne(query);
        if (task == null) return;
        task.extend(Instant.now().plus(lease));
        mapper.update(task, owner(organizationId, userId)
                .eq(ActiveUserTask::getTaskId, taskId));
    }

    @Transactional(readOnly = true)
    public ActiveUserTask current(String organizationId, String userId) {
        ActiveUserTask task = mapper.selectOne(owner(organizationId, userId));
        if (task != null && task.getLeaseExpiresAt().isBefore(Instant.now())) {
            return null;
        }
        return task;
    }

    private LambdaQueryWrapper<ActiveUserTask> owner(
            String organizationId,
            String userId
    ) {
        return new LambdaQueryWrapper<ActiveUserTask>()
                .eq(ActiveUserTask::getOrganizationId, organizationId)
                .eq(ActiveUserTask::getUserId, userId);
    }
}
