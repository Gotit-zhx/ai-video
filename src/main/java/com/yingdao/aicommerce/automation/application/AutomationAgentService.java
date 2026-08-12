package com.yingdao.aicommerce.automation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.databind.ObjectMapper;
import com.yingdao.aicommerce.automation.domain.AutomationAgent;
import com.yingdao.aicommerce.automation.domain.AutomationCommand;
import com.yingdao.aicommerce.automation.domain.AutomationCommandEvent;
import com.yingdao.aicommerce.automation.mapper.AutomationAgentMapper;
import com.yingdao.aicommerce.automation.mapper.AutomationCommandEventMapper;
import com.yingdao.aicommerce.automation.mapper.AutomationCommandMapper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.mapper.OrganizationMapper;
import com.yingdao.aicommerce.task.application.ActiveUserTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AutomationAgentService {

    private final AutomationAgentMapper agentMapper;
    private final AutomationCommandMapper commandMapper;
    private final AutomationCommandEventMapper eventMapper;
    private final OrganizationMapper organizationMapper;
    private final ObjectMapper objectMapper;
    private final ActiveUserTaskService activeTaskService;
    private final ApplicationEventPublisher eventPublisher;
    private final String agentToken;

    public AutomationAgentService(
            AutomationAgentMapper agentMapper,
            AutomationCommandMapper commandMapper,
            AutomationCommandEventMapper eventMapper,
            OrganizationMapper organizationMapper,
            ObjectMapper objectMapper,
            ActiveUserTaskService activeTaskService,
            ApplicationEventPublisher eventPublisher,
            @Value("${app.automation-agent-token}") String agentToken
    ) {
        this.agentMapper = agentMapper;
        this.commandMapper = commandMapper;
        this.eventMapper = eventMapper;
        this.organizationMapper = organizationMapper;
        this.objectMapper = objectMapper;
        this.activeTaskService = activeTaskService;
        this.eventPublisher = eventPublisher;
        this.agentToken = agentToken;
    }

    @Transactional
    public AutomationAgent heartbeat(
            String suppliedToken,
            String agentId,
            String organizationCode,
            String name,
            String osName,
            String version,
            boolean acceptingJobs
    ) {
        validateToken(suppliedToken);
        var organization = organizationMapper.selectOne(
                new LambdaQueryWrapper<com.yingdao.aicommerce.saas.domain.Organization>()
                        .eq(com.yingdao.aicommerce.saas.domain.Organization::getCode, organizationCode)
        );
        if (organization == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "企业代码不存在");
        }
        AutomationAgent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            agent = new AutomationAgent(
                    agentId,
                    organization.getId(),
                    name,
                    osName,
                    version,
                    acceptingJobs
            );
            agentMapper.insert(agent);
            return agent;
        }
        if (!organization.getId().equals(agent.getOrganizationId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "执行器已绑定到其他企业");
        }
        agent.heartbeat(name, osName, version, acceptingJobs);
        agentMapper.updateById(agent);
        return agent;
    }

    @Transactional
    public AutomationCommand claimNext(String suppliedToken, String agentId) {
        validateToken(suppliedToken);
        AutomationAgent agent = requireAgent(agentId);
        if (!agent.isAcceptingJobs()) {
            return null;
        }
        AutomationCommand command = commandMapper.selectOne(
                new LambdaQueryWrapper<AutomationCommand>()
                        .eq(AutomationCommand::getAgentId, agentId)
                        .eq(AutomationCommand::getStatus, "QUEUED")
                        .orderByAsc(AutomationCommand::getCreatedAt)
                        .last("LIMIT 1 FOR UPDATE")
        );
        if (command != null) {
            command.claim();
            commandMapper.updateById(command);
            appendEvent(command, "STARTED", "LOCAL_ASSISTANT",
                    command.getProgressPercent(), "本机助手已领取任务", null);
        }
        return command;
    }

    @Transactional
    public AutomationCommand complete(
            String suppliedToken,
            String agentId,
            String commandId,
            boolean success,
            Object output,
            String errorMessage
    ) {
        validateToken(suppliedToken);
        AutomationCommand command = commandMapper.selectById(commandId);
        if (command == null || !agentId.equals(command.getAgentId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "自动化命令不存在");
        }
        if (!"RUNNING".equals(command.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "命令当前不能完成");
        }
        command.complete(success, toJson(output == null ? Map.of() : output), errorMessage);
        commandMapper.updateById(command);
        appendEvent(
                command,
                success ? "RESULT" : "ERROR",
                "LOCAL_ASSISTANT",
                command.getProgressPercent(),
                success ? "自动化执行完成" : errorMessage,
                output
        );
        if (!success) {
            eventPublisher.publishEvent(new AutomationModelSignal(
                    command.getId(),
                    "FAILURE",
                    errorMessage == null ? "自动化执行失败" : errorMessage
            ));
        } else {
            eventPublisher.publishEvent(new AutomationModelSignal(
                    command.getId(),
                    "SUCCESS",
                    "自动化执行完成，请总结执行结果"
            ));
        }
        Long unfinished = commandMapper.selectCount(
                new LambdaQueryWrapper<AutomationCommand>()
                        .eq(AutomationCommand::getOrganizationId, command.getOrganizationId())
                        .eq(AutomationCommand::getCreatedBy, command.getCreatedBy())
                        .eq(AutomationCommand::getSessionId, command.getSessionId())
                        .in(AutomationCommand::getStatus, "QUEUED", "RUNNING")
        );
        if (unfinished == 0) {
            activeTaskService.release(
                    command.getOrganizationId(),
                    command.getCreatedBy(),
                    command.getSessionId()
            );
        }
        return command;
    }

    public AutomationAgent availableAgent(String organizationId) {
        Instant threshold = Instant.now().minus(20, ChronoUnit.SECONDS);
        return agentMapper.selectOne(
                new LambdaQueryWrapper<AutomationAgent>()
                        .eq(AutomationAgent::getOrganizationId, organizationId)
                        .eq(AutomationAgent::getStatus, "ONLINE")
                        .eq(AutomationAgent::isAcceptingJobs, true)
                        .ge(AutomationAgent::getLastSeenAt, threshold)
                        .orderByDesc(AutomationAgent::getLastSeenAt)
                        .last("LIMIT 1")
        );
    }

    public AutomationAgent availableAgentForUser(
            String organizationId,
            String userId,
            String localAgentId
    ) {
        if (localAgentId == null || localAgentId.isBlank()) {
            return null;
        }
        Instant threshold = Instant.now().minus(20, ChronoUnit.SECONDS);
        return agentMapper.selectOne(
                new LambdaQueryWrapper<AutomationAgent>()
                        .eq(AutomationAgent::getId, localAgentId)
                        .eq(AutomationAgent::getOrganizationId, organizationId)
                        .eq(AutomationAgent::getBoundUserId, userId)
                        .eq(AutomationAgent::getStatus, "ONLINE")
                        .eq(AutomationAgent::isAcceptingJobs, true)
                        .ge(AutomationAgent::getLastSeenAt, threshold)
                        .last("LIMIT 1")
        );
    }

    @Transactional
    public AgentConnection connectCurrentUser(
            String organizationId,
            String userId,
            String localAgentId
    ) {
        if (localAgentId == null || localAgentId.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "未检测到本机助手设备标识");
        }
        AutomationAgent agent = agentMapper.selectById(localAgentId);
        if (agent == null || !organizationId.equals(agent.getOrganizationId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "本机助手尚未连接当前企业");
        }
        Instant threshold = Instant.now().minus(20, ChronoUnit.SECONDS);
        if (!"ONLINE".equals(agent.getStatus()) || agent.getLastSeenAt().isBefore(threshold)) {
            throw new BusinessException(HttpStatus.CONFLICT, "本机助手心跳已中断");
        }
        if (!agent.isAcceptingJobs()) {
            throw new BusinessException(HttpStatus.CONFLICT, "本机助手已暂停接收任务");
        }
        AutomationAgent previous = agentMapper.selectOne(
                new LambdaQueryWrapper<AutomationAgent>()
                        .eq(AutomationAgent::getOrganizationId, organizationId)
                        .eq(AutomationAgent::getBoundUserId, userId)
                        .last("LIMIT 1")
        );
        if (previous != null && !previous.getId().equals(agent.getId())) {
            previous.bindToUser(null);
            agentMapper.updateById(previous);
        }
        if (!userId.equals(agent.getBoundUserId())) {
            agent.bindToUser(userId);
            agentMapper.updateById(agent);
        }
        return new AgentConnection(
                agent.getId(), agent.getName(), agent.getOsName(),
                true, agent.isAcceptingJobs(), agent.getLastSeenAt()
        );
    }

    @Transactional
    public AutomationCommand enqueue(
            String organizationId,
            String userId,
            String sessionId,
            String commandType,
            Object payload,
            String localAgentId
    ) {
        AutomationAgent agent = availableAgentForUser(
                organizationId, userId, localAgentId
        );
        if (agent == null) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "当前电脑的赫逸自动化助手未连接，请保持助手在线后重试"
            );
        }
        if (!List.of(
                "FEIKUA_INSPECT",
                "FEIKUA_OPEN_STORE",
                "GOOGLE_ACCOUNT_POOL_START",
                "GOOGLE_ACCOUNT_CLOSE",
                "GOOGLE_ACCOUNT_COMMAND",
                "GOOGLE_ACCOUNT_PAGE_TASK"
        ).contains(commandType)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "智能助手没有被授权执行这个指令");
        }
        AutomationCommand command = new AutomationCommand(
                organizationId,
                agent.getId(),
                sessionId,
                commandType,
                null,
                toJson(payload),
                userId
        );
        commandMapper.insert(command);
        appendEvent(command, "QUEUED", "PLATFORM", 0,
                "任务已发送到本机助手队列", null);
        return command;
    }

    @Transactional
    public AutomationCommand enqueuePersonal(
            String organizationId,
            String userId,
            String sessionId,
            String automationCode,
            String commandType,
            Object payload,
            String localAgentId
    ) {
        AutomationAgent agent = availableAgentForUser(
                organizationId, userId, localAgentId
        );
        if (agent == null) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "当前电脑的赫逸自动化助手未连接，请保持助手在线后重试"
            );
        }
        AutomationCommand command = new AutomationCommand(
                organizationId,
                agent.getId(),
                sessionId,
                commandType,
                automationCode,
                toJson(payload),
                userId
        );
        commandMapper.insert(command);
        appendEvent(command, "QUEUED", "PLATFORM", 0,
                "前置条件已锁定，任务已发送到专属本机助手", null);
        return command;
    }

    public record AgentConnection(
            String agentId,
            String name,
            String osName,
            boolean online,
            boolean acceptingJobs,
            Instant lastSeenAt
    ) {}

    @Transactional
    public AutomationCommand progress(
            String suppliedToken,
            String agentId,
            String commandId,
            int percent,
            String message,
            String eventType,
            String source,
            Object payload
    ) {
        validateToken(suppliedToken);
        AutomationCommand command = commandMapper.selectById(commandId);
        if (command == null || !agentId.equals(command.getAgentId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "自动化命令不存在");
        }
        if (!"RUNNING".equals(command.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "命令当前不能更新进度");
        }
        command.progress(percent, message);
        commandMapper.updateById(command);
        String safeEventType = normalizeEventType(eventType);
        appendEvent(
                command,
                safeEventType,
                normalizeSource(source),
                percent,
                message,
                payload
        );
        if ("EXECUTION_UNIT_RESULT".equals(safeEventType)) {
            eventPublisher.publishEvent(new AutomationModelSignal(
                    command.getId(),
                    "UNIT_RESULT_" + UUID.randomUUID().toString().replace("-", ""),
                    message
            ));
        } else if ("EXECUTION_UNIT_UPDATE".equals(safeEventType)) {
            eventPublisher.publishEvent(new AutomationModelSignal(
                    command.getId(),
                    "UNIT_UPDATE_" + UUID.randomUUID().toString().replace("-", ""),
                    message
            ));
        } else if (!"RECOVERY_REQUESTED".equals(safeEventType)
                && ("ANOMALY_SUSPECTED".equals(safeEventType)
                    || looksAbnormal(message))) {
            eventPublisher.publishEvent(new AutomationModelSignal(
                    command.getId(),
                    "ANOMALY_" + (Instant.now().getEpochSecond() / 30),
                    message
            ));
        } else {
            int checkpoint = checkpoint(percent);
            if (checkpoint > 0) {
                eventPublisher.publishEvent(new AutomationModelSignal(
                        command.getId(),
                        "CHECKPOINT_" + checkpoint,
                        "任务到达 " + checkpoint + "%，请复核当前执行是否符合计划"
                ));
            }
        }
        return command;
    }

    public AutomationModelService.RecoveryAdvice recover(
            String suppliedToken,
            String agentId,
            String commandId,
            int attempt,
            String errorMessage,
            Map<String, Object> toolResult,
            String contextKey,
            AutomationModelService modelService
    ) {
        validateToken(suppliedToken);
        AutomationCommand command = authorizeCommand(
                suppliedToken, agentId, commandId
        );
        return modelService.recover(
                command.getOrganizationId(),
                agentId,
                commandId,
                attempt,
                errorMessage,
                toolResult,
                contextKey
        );
    }

    @Transactional
    public void recordModelPlan(
            AutomationCommand command,
            AutomationModelService.PlanningResult plan,
            String requirement
    ) {
        boolean usedModel = plan.modelPlanned()
                && !"RULE_FALLBACK".equals(plan.modelId());
        appendEvent(
                command,
                usedModel ? "MODEL_PLAN" : "EXECUTION_PLAN",
                usedModel ? "MODEL" : "PLATFORM",
                0,
                plan.summary(),
                Map.of(
                        "requirement", requirement == null ? "" : requirement,
                        "parameters", plan.parameters(),
                        "assumptions", plan.assumptions(),
                        "warnings", plan.warnings(),
                        "modelId", plan.modelId() == null ? "" : plan.modelId(),
                        "latencyMs", plan.latencyMs(),
                        "cached", plan.cached()
                )
        );
    }

    public AutomationCommand userCommand(
            String organizationId,
            String userId,
            String commandId
    ) {
        AutomationCommand command = commandMapper.selectOne(
                new LambdaQueryWrapper<AutomationCommand>()
                        .eq(AutomationCommand::getId, commandId)
                        .eq(AutomationCommand::getOrganizationId, organizationId)
                        .eq(AutomationCommand::getCreatedBy, userId)
                        .last("LIMIT 1")
        );
        if (command == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "自动化任务不存在");
        }
        return command;
    }

    public List<AutomationCommandEvent> commandEvents(
            String organizationId,
            String userId,
            String commandId
    ) {
        userCommand(organizationId, userId, commandId);
        return eventMapper.selectList(
                new LambdaQueryWrapper<AutomationCommandEvent>()
                        .eq(AutomationCommandEvent::getCommandId, commandId)
                        .eq(AutomationCommandEvent::getOrganizationId, organizationId)
                        .eq(AutomationCommandEvent::getUserId, userId)
                        .orderByAsc(AutomationCommandEvent::getCreatedAt)
        );
    }

    @Transactional
    public AutomationCommand controlByUser(
            String organizationId,
            String userId,
            String commandId,
            String requestedAction
    ) {
        AutomationCommand command = userCommand(organizationId, userId, commandId);
        String action = normalizeControlAction(requestedAction);
        if ("RESUME".equals(action)) {
            if (!List.of("PAUSED", "INTERRUPTED").contains(command.getStatus())) {
                throw new BusinessException(HttpStatus.CONFLICT, "当前任务不能继续");
            }
            command.resume();
            commandMapper.updateById(command);
            activeTaskService.extend(
                    command.getOrganizationId(), command.getCreatedBy(),
                    command.getSessionId(), java.time.Duration.ofDays(30)
            );
            appendEvent(command, "CONTROL", "PLATFORM",
                    command.getProgressPercent(), "已请求从原断点继续执行", null);
            return command;
        }
        if ("QUEUED".equals(command.getStatus())
                || ("CANCEL".equals(action)
                    && List.of("PAUSED", "INTERRUPTED").contains(command.getStatus()))) {
            command.acknowledgeControl(action);
            commandMapper.updateById(command);
            appendEvent(command, "CONTROL", "PLATFORM",
                    command.getProgressPercent(), controlCompletedMessage(action), null);
            if ("CANCEL".equals(action)) {
                activeTaskService.release(
                        command.getOrganizationId(),
                        command.getCreatedBy(),
                        command.getSessionId()
                );
            } else {
                activeTaskService.extend(
                        command.getOrganizationId(), command.getCreatedBy(),
                        command.getSessionId(), java.time.Duration.ofDays(30)
                );
            }
            return command;
        }
        if (!"RUNNING".equals(command.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前任务不能执行该操作");
        }
        command.requestControl(action);
        commandMapper.updateById(command);
        appendEvent(command, "CONTROL_REQUESTED", "PLATFORM",
                command.getProgressPercent(), controlRequestedMessage(action), null);
        return command;
    }

    public AutomationCommand agentControl(
            String suppliedToken,
            String agentId,
            String commandId
    ) {
        validateToken(suppliedToken);
        AutomationCommand command = commandMapper.selectById(commandId);
        if (command == null || !agentId.equals(command.getAgentId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "自动化命令不存在");
        }
        return command;
    }

    @Transactional
    public AutomationCommand acknowledgeControl(
            String suppliedToken,
            String agentId,
            String commandId,
            String requestedAction
    ) {
        AutomationCommand command = agentControl(suppliedToken, agentId, commandId);
        String action = normalizeControlAction(requestedAction);
        String expected = action + "_REQUESTED";
        if (!expected.equals(command.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "控制请求状态已经变化");
        }
        command.acknowledgeControl(action);
        commandMapper.updateById(command);
        appendEvent(command, "CONTROL", "LOCAL_ASSISTANT",
                command.getProgressPercent(), controlCompletedMessage(action), null);
        if ("CANCEL".equals(action)) {
            activeTaskService.release(
                    command.getOrganizationId(),
                    command.getCreatedBy(),
                    command.getSessionId()
            );
        } else {
            activeTaskService.extend(
                    command.getOrganizationId(), command.getCreatedBy(),
                    command.getSessionId(), java.time.Duration.ofDays(30)
            );
        }
        return command;
    }

    public List<AutomationCommand> userCommands(
            String organizationId,
            String userId
    ) {
        return commandMapper.selectList(
                new LambdaQueryWrapper<AutomationCommand>()
                        .eq(AutomationCommand::getOrganizationId, organizationId)
                        .eq(AutomationCommand::getCreatedBy, userId)
                        .isNotNull(AutomationCommand::getAutomationCode)
                        .orderByDesc(AutomationCommand::getCreatedAt)
                        .last("LIMIT 30")
        );
    }

    public List<AutomationCommand> sessionCommands(String organizationId, String sessionId) {
        return commandMapper.selectList(
                new LambdaQueryWrapper<AutomationCommand>()
                        .eq(AutomationCommand::getOrganizationId, organizationId)
                        .eq(AutomationCommand::getSessionId, sessionId)
                        .orderByDesc(AutomationCommand::getCreatedAt)
                        .last("LIMIT 20")
        );
    }

    public Object parseJson(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception exception) {
            return value;
        }
    }

    private AutomationAgent requireAgent(String agentId) {
        AutomationAgent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "执行器尚未注册");
        }
        return agent;
    }

    public void authorizeAgent(String suppliedToken, String agentId) {
        validateToken(suppliedToken);
        requireAgent(agentId);
    }

    public AutomationCommand authorizeCommand(
            String suppliedToken,
            String agentId,
            String commandId
    ) {
        validateToken(suppliedToken);
        AutomationCommand command = commandMapper.selectById(commandId);
        if (command == null || !agentId.equals(command.getAgentId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "自动化命令不存在");
        }
        return command;
    }

    private void validateToken(String suppliedToken) {
        if (suppliedToken == null || !agentToken.equals(suppliedToken)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "执行器认证失败");
        }
    }

    private boolean looksAbnormal(String message) {
        if (message == null || message.isBlank()) return false;
        String value = message.toLowerCase();
        return List.of(
                "异常", "失败", "超时", "登录失效", "未登录",
                "找不到", "连接中断", "error", "exception", "timeout",
                "timed out", "failed"
        ).stream().anyMatch(value::contains);
    }

    private int checkpoint(int percent) {
        if (percent >= 75 && percent < 95) return 75;
        if (percent >= 50 && percent < 75) return 50;
        if (percent >= 25 && percent < 50) return 25;
        return 0;
    }

    private void appendEvent(
            AutomationCommand command,
            String eventType,
            String source,
            Integer percent,
            String message,
            Object payload
    ) {
        String safeMessage = message == null || message.isBlank()
                ? "任务状态已更新" : message;
        eventMapper.insert(new AutomationCommandEvent(
                command,
                eventType,
                source,
                percent,
                safeMessage,
                payload == null ? null : toJson(payload)
        ));
    }

    private String normalizeEventType(String value) {
        return value != null && value.matches("[A-Z_]{2,32}")
                ? value : "PROGRESS";
    }

    private String normalizeSource(String value) {
        return value != null && value.matches("[A-Z_]{2,32}")
                ? value : "LOCAL_ASSISTANT";
    }

    private String normalizeControlAction(String value) {
        String action = value == null ? "" : value.trim().toUpperCase();
        if (!List.of("PAUSE", "INTERRUPT", "CANCEL", "RESUME").contains(action)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的任务控制动作");
        }
        return action;
    }

    private String controlRequestedMessage(String action) {
        return switch (action) {
            case "PAUSE" -> "正在通知本机助手暂停任务";
            case "INTERRUPT" -> "正在通知本机助手立即中断任务";
            case "CANCEL" -> "正在通知本机助手取消任务";
            default -> "正在处理任务控制请求";
        };
    }

    private String controlCompletedMessage(String action) {
        return switch (action) {
            case "PAUSE" -> "任务已暂停，输入参数与断点均已保留";
            case "INTERRUPT" -> "任务已中断，输入参数与断点均已保留";
            case "CANCEL" -> "任务已取消，本轮不能继续";
            default -> "任务状态已更新";
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法保存自动化任务参数", exception);
        }
    }
}
