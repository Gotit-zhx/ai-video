package com.yingdao.aicommerce.automation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.automation.domain.AutomationAgent;
import com.yingdao.aicommerce.automation.domain.AutomationCapability;
import com.yingdao.aicommerce.automation.domain.AutomationCommand;
import com.yingdao.aicommerce.automation.mapper.AutomationAgentMapper;
import com.yingdao.aicommerce.automation.mapper.AutomationCapabilityMapper;
import com.yingdao.aicommerce.automation.mapper.AutomationCommandMapper;
import com.yingdao.aicommerce.automation.mapper.UserAutomationGrantMapper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.domain.PlatformUser;
import com.yingdao.aicommerce.saas.mapper.PlatformUserMapper;
import com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy;
import com.yingdao.aicommerce.saas.application.TenantContext;
import com.yingdao.aicommerce.task.application.ActiveUserTaskService;
import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class AutomationSkillService {
    private static final long MAX_PACKAGE_BYTES = 100L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 500L * 1024 * 1024;
    private static final int MAX_UPLOAD_FILES = 500;

    private final AutomationCapabilityMapper capabilityMapper;
    private final UserAutomationGrantMapper grantMapper;
    private final AutomationAgentMapper agentMapper;
    private final AutomationCommandMapper commandMapper;
    private final PlatformUserMapper userMapper;
    private final AutomationAgentService agentService;
    private final AutomationModelService modelService;
    private final ActiveUserTaskService activeTaskService;
    private final TenantContext tenantContext;
    private final OrganizationAccessPolicy organizationAccessPolicy;
    private final ObjectMapper objectMapper;
    private final Path storageRoot;

    public AutomationSkillService(
            AutomationCapabilityMapper capabilityMapper,
            UserAutomationGrantMapper grantMapper,
            AutomationAgentMapper agentMapper,
            AutomationCommandMapper commandMapper,
            PlatformUserMapper userMapper,
            AutomationAgentService agentService,
            AutomationModelService modelService,
            ActiveUserTaskService activeTaskService,
            TenantContext tenantContext,
            OrganizationAccessPolicy organizationAccessPolicy,
            ObjectMapper objectMapper,
            @Value("${app.automation-skill-storage:/data/automation-skills}") String storageRoot
    ) {
        this.capabilityMapper = capabilityMapper;
        this.grantMapper = grantMapper;
        this.agentMapper = agentMapper;
        this.commandMapper = commandMapper;
        this.userMapper = userMapper;
        this.agentService = agentService;
        this.modelService = modelService;
        this.activeTaskService = activeTaskService;
        this.tenantContext = tenantContext;
        this.organizationAccessPolicy = organizationAccessPolicy;
        this.objectMapper = objectMapper;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public List<AutomationCapability> granted(PlatformPrincipal principal) {
        return capabilityMapper.selectGranted(
                principal.organizationId(),
                principal.userId()
        );
    }

    public List<AutomationCapability> all() {
        return capabilityMapper.selectList(
                new LambdaQueryWrapper<AutomationCapability>()
                        .orderByAsc(AutomationCapability::getSortOrder)
                        .orderByAsc(AutomationCapability::getCode)
        );
    }

    @Transactional
    public AutomationCapability upload(MultipartFile file) {
        return upload(file == null ? List.of() : List.of(file), null);
    }

    @Transactional
    public AutomationCapability upload(List<MultipartFile> files) {
        return upload(files, null);
    }

    @Transactional
    public AutomationCapability upload(List<MultipartFile> files, String displayName) {
        if (files == null || files.isEmpty() || files.size() > MAX_UPLOAD_FILES) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请选择 Skill 压缩包或文件夹");
        }
        try {
            byte[] sourceBytes = normalizeUpload(files);
            if (sourceBytes.length == 0 || sourceBytes.length > MAX_PACKAGE_BYTES) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "Skill 安装包为空或超过 100 MB");
            }
            SkillManifest manifest = readManifest(sourceBytes);
            String requestedName = displayName == null ? "" : displayName.trim();
            if (requestedName.length() > 120) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "Skill name cannot exceed 120 characters");
            }
            if (!requestedName.isEmpty()) {
                manifest = manifest.withName(requestedName);
            }
            byte[] bytes = buildAdaptedPackage(sourceBytes, manifest);
            String checksum = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
            );
            Path directory = storageRoot
                    .resolve(manifest.code())
                    .resolve(manifest.version())
                    .normalize();
            if (!directory.startsWith(storageRoot)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "Skill 标识不安全");
            }
            Files.createDirectories(directory);
            Files.write(directory.resolve("source.zip"), sourceBytes);
            Path packagePath = directory.resolve("package.heyi-skill");
            Files.write(packagePath, bytes);
            AutomationCapability capability = new AutomationCapability(
                    manifest.code(),
                    manifest.name(),
                    manifest.description(),
                    manifest.version(),
                    manifest.commandType(),
                    String.join(",", manifest.supportedOs()),
                    manifest.entryPoint(),
                    objectMapper.writeValueAsString(Map.of("fields", manifest.inputs())),
                    packagePath.toString(),
                    checksum,
                    manifest.runnerType()
            );
            AutomationCapability existing = capabilityMapper.selectById(manifest.code());
            if (existing == null) capabilityMapper.insert(capability);
            else capabilityMapper.updateById(capability);
            return capability;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Skill 安装包校验失败："
                    + String.valueOf(exception.getMessage()).replaceAll("[\r\n]", " "));
        }
    }

    /**
     * Normalize ZIP, RAR, a single SKILL.md, or a browser-uploaded folder
     * into the ZIP stream used by the existing Skill converter.
     */
    private byte[] normalizeUpload(List<MultipartFile> files) throws IOException {
        List<MultipartFile> nonEmpty = files.stream()
                .filter(file -> file != null && !file.isEmpty())
                .toList();
        if (nonEmpty.isEmpty()) return new byte[0];
        long total = 0;
        for (MultipartFile file : nonEmpty) {
            total += file.getSize();
            if (file.getSize() > MAX_PACKAGE_BYTES || total > MAX_PACKAGE_BYTES) {
                throw new IOException("上传内容超过 100 MB");
            }
        }
        if (nonEmpty.size() == 1) {
            MultipartFile file = nonEmpty.get(0);
            String name = safeUploadName(file.getOriginalFilename());
            byte[] bytes = file.getBytes();
            String lower = name.toLowerCase();
            if (lower.endsWith(".rar") || looksLikeRar(bytes)) {
                return rarToZip(bytes);
            }
            if (lower.endsWith(".zip") || looksLikeZip(bytes)) return bytes;
            if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
                return filesToZip(List.of(new UploadPart(name, bytes)));
            }
            throw new IOException("单文件入口只支持 ZIP、RAR 或 SKILL.md");
        }
        List<UploadPart> parts = new ArrayList<>();
        for (MultipartFile file : nonEmpty) {
            String name = safeUploadName(file.getOriginalFilename());
            parts.add(new UploadPart(name, file.getBytes()));
        }
        return filesToZip(parts);
    }

    private byte[] rarToZip(byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile("heyi-skill-", ".rar");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Set<String> names = new HashSet<>();
            long expanded = 0;
            try (Archive archive = new Archive(temporary.toFile());
                 ZipOutputStream target = new ZipOutputStream(output)) {
                int count = 0;
                for (FileHeader header : archive.getFileHeaders()) {
                    if (header.isDirectory()) continue;
                    if (++count > MAX_UPLOAD_FILES) throw new IOException("RAR 文件数量超过限制");
                    String name = safeArchivePath(header.getFileNameString());
                    if (!names.add(name)) throw new IOException("RAR 包含重复文件：" + name);
                    ByteArrayOutputStream entryBytes = new ByteArrayOutputStream();
                    archive.extractFile(header, entryBytes);
                    expanded += entryBytes.size();
                    if (expanded > MAX_EXPANDED_BYTES) {
                        throw new IOException("RAR 解压后体积超过 500 MB");
                    }
                    target.putNextEntry(new ZipEntry(name));
                    entryBytes.writeTo(target);
                    target.closeEntry();
                }
            } catch (Exception exception) {
                if (exception instanceof IOException ioException) throw ioException;
                throw new IOException("RAR 解压失败：" + exception.getMessage(), exception);
            }
            return output.toByteArray();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private byte[] filesToZip(List<UploadPart> parts) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Set<String> names = new HashSet<>();
        long expanded = 0;
        try (ZipOutputStream target = new ZipOutputStream(output)) {
            for (UploadPart part : parts) {
                String name = safeArchivePath(part.name());
                if (!names.add(name)) throw new IOException("上传文件重复：" + name);
                expanded += part.bytes().length;
                if (expanded > MAX_EXPANDED_BYTES) throw new IOException("文件夹展开后体积超过 500 MB");
                target.putNextEntry(new ZipEntry(name));
                target.write(part.bytes());
                target.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private String safeUploadName(String value) throws IOException {
        String name = String.valueOf(value == null ? "" : value)
                .replace('\\', '/')
                .replaceAll("^/+", "")
                .replaceAll("^\\./+", "");
        if (name.isBlank() || name.length() > 1000 || name.contains("../")
                || name.contains(":") || name.startsWith("/")) {
            throw new IOException("上传文件路径不安全");
        }
        return name;
    }

    private String safeArchivePath(String value) throws IOException {
        String name = safeUploadName(value);
        if (name.endsWith("/") || name.contains("//")) {
            throw new IOException("压缩包包含不安全目录路径");
        }
        return name;
    }

    private boolean looksLikeZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && (bytes[2] == 3 || bytes[2] == 5 || bytes[2] == 7)
                && (bytes[3] == 4 || bytes[3] == 6 || bytes[3] == 8);
    }

    private boolean looksLikeRar(byte[] bytes) {
        if (bytes.length < 8) return false;
        byte[] signature = {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07};
        for (int index = 0; index < signature.length; index++) {
            if (bytes[index] != signature[index]) return false;
        }
        return true;
    }

    private record UploadPart(String name, byte[] bytes) {}

    @Transactional
    public AutomationCapability activate(String code) {
        AutomationCapability capability = requireCapability(code);
        capability.activate();
        capabilityMapper.updateById(capability);
        return capability;
    }

    @Transactional
    public void deleteImported(String code) {
        AutomationCapability capability = requireCapability(code);
        if (!"UPLOADED".equals(capability.getSourceType())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "系统内置 Skill 不能删除");
        }
        long activeCommands = commandMapper.selectCount(
                new LambdaQueryWrapper<AutomationCommand>()
                        .eq(AutomationCommand::getAutomationCode, code)
                        .in(AutomationCommand::getStatus, List.of(
                                "QUEUED", "RUNNING", "PAUSE_REQUESTED",
                                "INTERRUPT_REQUESTED", "CANCEL_REQUESTED"
                        ))
        );
        if (activeCommands > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "该 Skill 还有运行中或排队中的任务");
        }
        grantMapper.deleteForAutomation(code);
        commandMapper.update(
                null,
                new LambdaUpdateWrapper<AutomationCommand>()
                        .eq(AutomationCommand::getAutomationCode, code)
                        .set(AutomationCommand::getAutomationCode, null)
        );
        capabilityMapper.deleteById(code);
        deletePlatformPackage(code);
    }

    private void deletePlatformPackage(String code) {
        Path directory = storageRoot.resolve(code).normalize();
        if (!directory.startsWith(storageRoot) || directory.equals(storageRoot)
                || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Database deletion is authoritative; orphaned files can be cleaned later.
                }
            });
        } catch (IOException ignored) {
            // A locked package must not restore a deleted catalog entry.
        }
    }

    @Transactional
    public AutomationCapability updateInputSchema(
            String code,
            Map<String, Object> schema
    ) {
        AutomationCapability capability = requireCapability(code);
        Map<String, Object> value = schema == null ? Map.of() : schema;
        Object rawFields = value.getOrDefault("fields", List.of());
        Object rawPrerequisites = value.getOrDefault("prerequisites", List.of());
        if (!(rawFields instanceof List<?> fields)
                || !(rawPrerequisites instanceof List<?> prerequisites)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "表单结构格式错误");
        }
        if (fields.size() > 40 || prerequisites.size() > 20) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "表单字段或前置条件数量过多");
        }
        Set<String> allowedTypes = Set.of(
                "text", "textarea", "number", "date", "select",
                "checkbox", "localFile", "localDirectory"
        );
        Set<String> names = new HashSet<>();
        List<Map<String, Object>> normalizedFields = new ArrayList<>();
        int fieldIndex = 0;
        for (Object item : fields) {
            if (!(item instanceof Map<?, ?> field)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "表单字段格式错误");
            }
            fieldIndex++;
            String name = field.get("name") == null
                    ? "" : String.valueOf(field.get("name")).trim();
            String type = field.get("type") == null
                    ? "" : String.valueOf(field.get("type"));
            String label = field.get("label") == null
                    ? "" : String.valueOf(field.get("label")).trim();
            if (!allowedTypes.contains(type)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "表单控件类型不受支持");
            }
            if (name.isBlank() || names.contains(name)) {
                int suffix = fieldIndex;
                do {
                    name = "field" + suffix++;
                } while (names.contains(name));
            }
            if (name.length() > 64) name = name.substring(0, 64);
            while (names.contains(name)) name = "field" + (++fieldIndex);
            names.add(name);
            if (label.isBlank()) label = defaultFieldLabel(type);
            if (label.length() > 80) label = label.substring(0, 80);
            Map<String, Object> normalized = new LinkedHashMap<>();
            field.forEach((key, fieldValue) -> normalized.put(String.valueOf(key), fieldValue));
            normalized.put("name", name);
            normalized.put("label", label);
            normalized.put("type", type);
            normalizedFields.add(normalized);
        }
        List<Map<String, Object>> normalizedPrerequisites = new ArrayList<>();
        int prerequisiteIndex = 0;
        for (Object item : prerequisites) {
            if (!(item instanceof Map<?, ?> prerequisite)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "前置条件格式错误");
            }
            prerequisiteIndex++;
            String label = prerequisite.get("label") == null
                    ? "" : String.valueOf(prerequisite.get("label")).trim();
            if (label.isBlank()) label = "确认事项 " + prerequisiteIndex;
            if (label.length() > 120) label = label.substring(0, 120);
            Map<String, Object> normalized = new LinkedHashMap<>();
            prerequisite.forEach((key, prerequisiteValue) ->
                    normalized.put(String.valueOf(key), prerequisiteValue));
            normalized.put("label", label);
            normalizedPrerequisites.add(normalized);
        }
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "version", 1,
                    "fields", normalizedFields,
                    "prerequisites", normalizedPrerequisites
            ));
            if (json.length() > 32_000) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "表单配置超过大小限制");
            }
            capability.updateInputSchema(json);
            capabilityMapper.updateById(capability);
            return capability;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "无法保存表单配置");
        }
    }

    @Transactional
    public AutomationCapability updateRuntimeConfig(
            String code,
            Map<String, Object> runtimeConfig
    ) {
        AutomationCapability capability = requireCapability(code);
        Map<String, Object> source = runtimeConfig == null ? Map.of() : runtimeConfig;
        String engine = String.valueOf(source.getOrDefault("browserEngine", "FEIKUA"))
                .trim().toUpperCase();
        if (!Set.of("FEIKUA", "BROWSER_ACT").contains(engine)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "浏览器执行器类型不受支持");
        }
        String browserType = String.valueOf(source.getOrDefault("browserType", "chrome"))
                .trim().toLowerCase();
        if (!Set.of("chrome", "chrome-direct", "stealth").contains(browserType)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Browser Act 浏览器类型不受支持");
        }
        String browserId = String.valueOf(source.getOrDefault("browserId", "")).trim();
        if (!browserId.isBlank() && !browserId.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "浏览器实例 ID 格式不正确");
        }
        String proxyPolicy = String.valueOf(source.getOrDefault("proxyPolicy", "DIRECT"))
                .trim().toUpperCase();
        if (!Set.of("DIRECT", "PRECONFIGURED").contains(proxyPolicy)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "代理策略不受支持");
        }
        String requiredRegion = String.valueOf(source.getOrDefault("requiredRegion", ""))
                .trim().toUpperCase();
        if (requiredRegion.length() > 20 || !requiredRegion.matches("[A-Z0-9_-]*")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "国家或地区代码格式不正确");
        }
        if (proxyPolicy.equals("DIRECT") && !requiredRegion.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "指定国家线路时请选择预配置代理");
        }
        if (proxyPolicy.equals("PRECONFIGURED")
                && engine.equals("BROWSER_ACT")
                && !browserType.equals("stealth")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Browser Act 国家代理需要使用 Stealth 隔离浏览器");
        }
        Object rawDomains = source.getOrDefault("allowedDomains", List.of());
        if (!(rawDomains instanceof List<?> domains) || domains.size() > 50) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "允许访问的域名配置不正确");
        }
        List<String> allowedDomains = domains.stream()
                .map(value -> String.valueOf(value).trim().toLowerCase())
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (allowedDomains.stream().anyMatch(value ->
                value.length() > 253 || !value.matches("[a-z0-9.-]+")
        )) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "允许访问的域名格式不正确");
        }
        try {
            capability.updateRuntimeConfig(objectMapper.writeValueAsString(Map.of(
                    "browserEngine", engine,
                    "browserType", browserType,
                    "browserId", browserId,
                    "proxyPolicy", proxyPolicy,
                    "requiredRegion", requiredRegion,
                    "allowedDomains", allowedDomains
            )));
            capabilityMapper.updateById(capability);
            return capability;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "无法保存浏览器执行配置");
        }
    }

    private String defaultFieldLabel(String type) {
        return switch (type) {
            case "textarea" -> "多行文本";
            case "number" -> "数字";
            case "date" -> "日期";
            case "select" -> "下拉选择";
            case "checkbox" -> "确认开关";
            case "localFile" -> "文件上传";
            case "localDirectory" -> "文件夹";
            default -> "单行文本";
        };
    }

    public UserAccess access(String userId) {
        PlatformUser user = requireUser(userId);
        return new UserAccess(
                grantMapper.selectCodes(user.getOrganizationId(), userId)
        );
    }

    @Transactional
    public UserAccess updateAccess(
            String userId,
            List<String> automationCodes,
            String grantedBy
    ) {
        PlatformUser user = requireUser(userId);
        List<String> codes = automationCodes == null
                ? List.of()
                : automationCodes.stream().distinct().toList();
        for (String code : codes) requireCapability(code);
        grantMapper.deleteForUser(user.getOrganizationId(), userId);
        codes.forEach(code -> grantMapper.insertGrant(
                user.getOrganizationId(), userId, code, grantedBy
        ));
        return access(userId);
    }

    public List<AutomationAgent> agents() {
        return agentMapper.selectList(
                new LambdaQueryWrapper<AutomationAgent>()
                        .eq(AutomationAgent::getOrganizationId, tenantContext.organizationId())
                        .orderByDesc(AutomationAgent::getLastSeenAt)
        );
    }

    @Transactional
    public AutomationCommand execute(
            String code,
            Map<String, Object> input,
            PlatformPrincipal principal,
            String localAgentId
    ) {
        AutomationCapability capability = requireCapability(code);
        if (!"ACTIVE".equals(capability.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "该 Skill 尚未启用");
        }
        if (!grantMapper.selectCodes(principal.organizationId(), principal.userId()).contains(code)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "你没有执行该自动化的权限");
        }
        Map<String, Object> validated = validateInput(code, input);
        String requirement = executionRequirement(input);
        AutomationModelService.PlanningResult plan = modelService.plan(
                principal.organizationId(),
                capability.getCode(),
                capability.getVersion(),
                requirement,
                validated,
                planningContext(capability)
        );
        String sessionId = "automation_" + UUID.randomUUID().toString().replace("-", "");
        activeTaskService.acquire(
                principal.organizationId(),
                principal.userId(),
                sessionId,
                "AUTOMATION_SKILL",
                Duration.ofHours(24)
        );
        try {
            AutomationCommand command = agentService.enqueuePersonal(
                    principal.organizationId(),
                    principal.userId(),
                    sessionId,
                    code,
                    capability.getCommandType(),
                    Map.of(
                            "skillCode", capability.getCode(),
                            "skillVersion", capability.getVersion(),
                            "entryPoint", capability.getEntryPoint(),
                            "sourceType", capability.getSourceType(),
                            "checksumSha256", capability.getChecksumSha256() == null
                                    ? "" : capability.getChecksumSha256(),
                            "parameters", plan.parameters(),
                            "executionRequirement", requirement,
                            "runtime", parseRuntimeConfig(capability)
                    ),
                    localAgentId
            );
            agentService.recordModelPlan(command, plan, requirement);
            return command;
        } catch (RuntimeException exception) {
            activeTaskService.release(
                    principal.organizationId(), principal.userId(), sessionId
            );
            throw exception;
        }
    }

    public AutomationModelService.PlanningResult plan(
            String code,
            Map<String, Object> input,
            PlatformPrincipal principal
    ) {
        AutomationCapability capability = requireCapability(code);
        if (!"ACTIVE".equals(capability.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "该自动化尚未启用");
        }
        if (!grantMapper.selectCodes(principal.organizationId(), principal.userId()).contains(code)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "你没有执行该自动化的权限");
        }
        Map<String, Object> validated = validateInput(code, input);
        return modelService.plan(
                principal.organizationId(),
                capability.getCode(),
                capability.getVersion(),
                executionRequirement(input),
                validated,
                planningContext(capability)
        );
    }

    private Map<String, Object> planningContext(AutomationCapability capability) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("name", capability.getName());
        context.put("description", capability.getDescription());
        context.put("runtime", parseRuntimeConfig(capability));
        try {
            context.put(
                    "inputSchema",
                    objectMapper.readValue(capability.getInputSchema(), Map.class)
            );
        } catch (Exception ignored) {
            context.put("inputSchema", Map.of());
        }
        if ("YUE_BILL_EXPORT".equals(capability.getCode())) {
            context.put("executionParameters", Map.of(
                    "storeLimit", "最多处理的店铺任务数；0 表示处理所有符合条件的店铺",
                    "maxConcurrency", "同时并行处理的店铺数，范围 1-5",
                    "onlyPlatform", "平台范围：all、tiktok、tiktok-global、tiktok-all、shopee、lazada",
                    "onlyStore", "完整且明确的单个店铺名称",
                    "officialWarehouseOnly", "是否仅处理工作簿备注或标记中属于官方仓的店铺"
            ));
        } else {
            context.put("executionParameters", Map.of());
        }
        return Map.copyOf(context);
    }

    private Map<String, Object> parseRuntimeConfig(AutomationCapability capability) {
        try {
            return objectMapper.readValue(capability.getRuntimeConfig(), Map.class);
        } catch (Exception ignored) {
            return Map.of("browserEngine", "FEIKUA", "proxyPolicy", "DIRECT");
        }
    }

    private String executionRequirement(Map<String, Object> input) {
        if (input == null) return "";
        String requirement = String.valueOf(
                input.getOrDefault("executionRequirement", "")
        ).trim();
        if (requirement.length() > 1000) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "本轮执行要求不能超过 1000 个字符");
        }
        return requirement;
    }

    private Map<String, Object> validateInput(String code, Map<String, Object> input) {
        if (!"YUE_BILL_EXPORT".equals(code)) return input == null ? Map.of() : input;
        Map<String, Object> value = input == null ? Map.of() : input;
        String workbook = requiredAny(value, "workbookPath", "file");
        LocalDate start = LocalDate.parse(requiredAny(value, "startDate", "startTime", "starTime"));
        LocalDate end = LocalDate.parse(requiredAny(value, "endDate", "endTime"));
        if (end.isBefore(start)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "结束日期不能早于开始日期");
        }
        int concurrency = Integer.parseInt(String.valueOf(value.getOrDefault("maxConcurrency", 3)));
        if (concurrency < 1 || concurrency > 5) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "并发店铺数必须为 1 到 5");
        }
        return Map.of(
                "workbookPath", workbook,
                "outputRoot", "__TASK_OUTPUT__",
                "startDate", start.toString(),
                "endDate", end.toString(),
                "maxConcurrency", concurrency,
                "maxLiveStoreSessions", Math.min(concurrency, 5)
        );
    }

    private String required(Map<String, Object> value, String key) {
        String result = String.valueOf(value.getOrDefault(key, "")).trim();
        if (result.isEmpty() || result.length() > 1000) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "缺少或无效的参数：" + key);
        }
        return result;
    }

    private String requiredAny(Map<String, Object> value, String... keys) {
        for (String key : keys) {
            String result = String.valueOf(value.getOrDefault(key, "")).trim();
            if (!result.isEmpty() && result.length() <= 1000) return result;
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, "缺少或无效的参数：" + keys[0]);
    }

    private SkillManifest readManifest(byte[] bytes) throws IOException {
        long expanded = 0;
        byte[] manifestBytes = null;
        String skillMarkdown = null;
        String skillRoot = "";
        String processEntry = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) {
                    throw new IOException("安装包包含不安全路径");
                }
                expanded += Math.max(0, entry.getSize());
                if (expanded > MAX_EXPANDED_BYTES) throw new IOException("解压后体积超过限制");
                if (name.endsWith("/automation-skill.json")
                        || "automation-skill.json".equals(name)) {
                    manifestBytes = zip.readAllBytes();
                } else if (name.endsWith("/SKILL.md") || "SKILL.md".equals(name)) {
                    byte[] markdownBytes = zip.readNBytes(1024 * 1024);
                    skillMarkdown = new String(markdownBytes, StandardCharsets.UTF_8);
                    int separator = name.lastIndexOf('/');
                    skillRoot = separator < 0 ? "" : name.substring(0, separator + 1);
                } else if (name.endsWith("/run.ps1") || "run.ps1".equals(name)) {
                    processEntry = name;
                }
            }
        }
        SkillManifest manifest;
        if (manifestBytes != null) {
            manifest = objectMapper.readValue(manifestBytes, SkillManifest.class);
            if (manifest.runnerType() == null || manifest.runnerType().isBlank()) {
                manifest = new SkillManifest(
                        manifest.code(), manifest.name(), manifest.version(),
                        manifest.description(), manifest.commandType(),
                        manifest.supportedOs(), manifest.entryPoint(),
                        manifest.inputs(),
                        manifest.entryPoint() != null
                                && manifest.entryPoint().toLowerCase().endsWith(".md")
                                ? "AGENT_LOCAL" : "PROCESS_LOCAL"
                );
            }
        } else {
            if (skillMarkdown == null) throw new IOException("缺少 SKILL.md");
            String rawName = frontMatterValue(skillMarkdown, "name", "converted-skill");
            String description = frontMatterValue(
                    skillMarkdown, "description", "由 Codex Skill 转换"
            );
            boolean isYueBillSkill = "yue-bill-parallel-10".equalsIgnoreCase(rawName);
            String code = isYueBillSkill
                    ? "YUE_BILL_EXPORT"
                    : rawName.toUpperCase()
                            .replaceAll("[^A-Z0-9]+", "_")
                            .replaceAll("^_+|_+$", "");
            if (isYueBillSkill) {
                description = "按日期范围并行导出飞跨店铺月账单，支持断点续跑与并发限制。";
            }
            String runner = processEntry == null ? "AGENT_LOCAL" : "PROCESS_LOCAL";
            String entryPoint = processEntry == null
                    ? skillRoot + "SKILL.md"
                    : processEntry;
            List<Map<String, Object>> inputs = "YUE_BILL_EXPORT".equals(code)
                    ? yueBillInputs()
                    : List.of();
            manifest = new SkillManifest(
                    code,
                    readableName(rawName),
                    "1.0.0",
                    description,
                    code,
                    processEntry == null ? List.of("WINDOWS", "MACOS") : List.of("WINDOWS"),
                    entryPoint,
                    inputs,
                    runner
            );
        }
        if (manifest.code() == null || !manifest.code().matches("[A-Z0-9_]{3,80}")) {
            throw new IOException("Skill code 格式错误");
        }
        if (manifest.version() == null || !manifest.version().matches("[0-9A-Za-z._-]{1,40}")) {
            throw new IOException("Skill version 格式错误");
        }
        if (manifest.entryPoint() == null
                || manifest.entryPoint().startsWith("/")
                || manifest.entryPoint().contains("..")) {
            throw new IOException("入口文件不安全");
        }
        return manifest;
    }

    private byte[] buildAdaptedPackage(
            byte[] sourceBytes,
            SkillManifest manifest
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream target = new ZipOutputStream(output);
             ZipInputStream source = new ZipInputStream(new ByteArrayInputStream(sourceBytes))) {
            ZipEntry entry;
            while ((entry = source.getNextEntry()) != null) {
                if ("heyi-manifest.json".equals(entry.getName())) continue;
                target.putNextEntry(new ZipEntry(entry.getName()));
                source.transferTo(target);
                target.closeEntry();
            }
            target.putNextEntry(new ZipEntry("heyi-manifest.json"));
            target.write(objectMapper.writeValueAsBytes(manifest));
            target.closeEntry();
        }
        return output.toByteArray();
    }

    private String frontMatterValue(
            String markdown,
            String key,
            String fallback
    ) {
        Matcher matcher = Pattern.compile(
                "(?m)^" + Pattern.quote(key) + ":\\s*[\"']?([^\\r\\n\"']+)"
        ).matcher(markdown);
        return matcher.find() ? matcher.group(1).trim() : fallback;
    }

    private String readableName(String value) {
        if ("yue-bill-parallel-10".equalsIgnoreCase(value)) return "月账单导出";
        return value.replace('-', ' ').replace('_', ' ');
    }

    private List<Map<String, Object>> yueBillInputs() {
        return List.of(
                Map.of("name", "workbookPath", "label", "店铺工作簿",
                        "type", "localFile", "required", true, "accept", ".xlsx"),
                Map.of("name", "startDate", "label", "开始日期",
                        "type", "date", "required", true),
                Map.of("name", "endDate", "label", "结束日期",
                        "type", "date", "required", true),
                Map.of("name", "outputRoot", "label", "输出目录",
                        "type", "localDirectory", "required", true),
                Map.of("name", "maxConcurrency", "label", "并发店铺数",
                        "type", "number", "required", true,
                        "default", 3, "min", 1, "max", 5)
        );
    }

    private AutomationCapability requireCapability(String code) {
        AutomationCapability capability = capabilityMapper.selectById(code);
        if (capability == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "自动化 Skill 不存在");
        }
        return capability;
    }

    private PlatformUser requireUser(String userId) {
        PlatformUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "人员不存在");
        }
        organizationAccessPolicy.resolveManagedOrganizationId(user.getOrganizationId());
        return user;
    }

    public record UserAccess(List<String> automationCodes) {}
    public record SkillManifest(
            String code,
            String name,
            String version,
            String description,
            String commandType,
            List<String> supportedOs,
            String entryPoint,
            List<Map<String, Object>> inputs,
            String runnerType
    ) {
        public SkillManifest withName(String displayName) {
            return new SkillManifest(
                    code, displayName, version, description, commandType,
                    supportedOs, entryPoint, inputs, runnerType
            );
        }
    }
}
