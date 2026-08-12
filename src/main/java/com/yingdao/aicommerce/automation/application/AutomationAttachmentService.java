package com.yingdao.aicommerce.automation.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;

@Service
public class AutomationAttachmentService {
    private static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    private final Path storageRoot;

    public AutomationAttachmentService(
            @Value("${app.automation-attachment-storage:/data/automation-attachments}") String storageRoot
    ) {
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public StoredAttachment store(MultipartFile file, PlatformPrincipal principal) {
        return store(file, principal.organizationId(), principal.userId());
    }

    public StoredAttachment storeOutput(MultipartFile file, String organizationId, String userId) {
        return store(file, organizationId, userId);
    }

    public StoredAttachment storeGenerated(
            byte[] content,
            String originalName,
            String contentType,
            String organizationId,
            String userId
    ) {
        if (content == null || content.length == 0) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "图片模型没有返回有效图片");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "生成图片不能超过 100MB");
        }
        return storeBytes(content, originalName, contentType, organizationId, userId);
    }

    private StoredAttachment store(MultipartFile file, String organizationId, String userId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请选择需要上传的本机文件");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "单个任务文件不能超过 100MB");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        String originalName = safeOriginalName(file.getOriginalFilename());
        Path directory = storageRoot.resolve(id).normalize();
        if (!directory.startsWith(storageRoot)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "任务附件标识不安全");
        }
        try {
            Files.createDirectories(directory);
            file.transferTo(directory.resolve("content"));
            Files.writeString(directory.resolve("name.txt"), originalName, StandardCharsets.UTF_8);
            Files.writeString(
                    directory.resolve("content-type.txt"),
                    safeContentType(file.getContentType(), originalName),
                    StandardCharsets.UTF_8
            );
            Files.writeString(
                    directory.resolve("owner.txt"),
                    organizationId + "\n" + userId,
                    StandardCharsets.UTF_8
            );
            return new StoredAttachment(
                    id,
                    originalName,
                    file.getSize(),
                    "heyi-attachment:" + id + ":" + originalName
            );
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "任务文件保存失败");
        }
    }

    private StoredAttachment storeBytes(
            byte[] content,
            String originalName,
            String contentType,
            String organizationId,
            String userId
    ) {
        String id = UUID.randomUUID().toString().replace("-", "");
        String safeName = safeOriginalName(originalName);
        Path directory = storageRoot.resolve(id).normalize();
        if (!directory.startsWith(storageRoot)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "任务附件标识不安全");
        }
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve("content"), content, StandardOpenOption.CREATE_NEW);
            Files.writeString(directory.resolve("name.txt"), safeName, StandardCharsets.UTF_8);
            Files.writeString(
                    directory.resolve("content-type.txt"),
                    safeContentType(contentType, safeName),
                    StandardCharsets.UTF_8
            );
            Files.writeString(
                    directory.resolve("owner.txt"),
                    organizationId + "\n" + userId,
                    StandardCharsets.UTF_8
            );
            return new StoredAttachment(
                    id,
                    safeName,
                    content.length,
                    "heyi-attachment:" + id + ":" + safeName
            );
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "生成图片保存失败");
        }
    }

    public AttachmentFile load(String attachmentId) {
        if (attachmentId == null || !attachmentId.matches("[a-f0-9]{32}")) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "任务附件不存在");
        }
        Path directory = storageRoot.resolve(attachmentId).normalize();
        Path content = directory.resolve("content");
        Path name = directory.resolve("name.txt");
        Path contentType = directory.resolve("content-type.txt");
        if (!directory.startsWith(storageRoot) || !Files.isRegularFile(content) || !Files.isRegularFile(name)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "任务附件不存在或已过期");
        }
        try {
            return new AttachmentFile(
                    content,
                    safeOriginalName(Files.readString(name, StandardCharsets.UTF_8)),
                    Files.size(content),
                    Files.isRegularFile(contentType)
                            ? safeContentType(Files.readString(contentType, StandardCharsets.UTF_8), name.getFileName().toString())
                            : safeContentType(null, Files.readString(name, StandardCharsets.UTF_8))
            );
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "任务附件读取失败");
        }
    }

    public AttachmentFile loadForUser(String attachmentId, PlatformPrincipal principal) {
        Path ownerPath = directory(attachmentId).resolve("owner.txt");
        try {
            String expected = principal.organizationId() + "\n" + principal.userId();
            if (!Files.isRegularFile(ownerPath)
                    || !expected.equals(Files.readString(ownerPath, StandardCharsets.UTF_8))) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "任务附件不存在或已过期");
            }
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "任务附件不存在或已过期");
        }
        return load(attachmentId);
    }

    @Scheduled(cron = "0 17 * * * *")
    public void deleteExpiredAttachments() {
        if (!Files.isDirectory(storageRoot)) return;
        Instant cutoff = Instant.now().minus(Duration.ofDays(3));
        try (var directories = Files.list(storageRoot)) {
            directories.filter(Files::isDirectory).forEach(directory -> {
                try {
                    if (Files.getLastModifiedTime(directory).toInstant().isBefore(cutoff)) {
                        try (var paths = Files.walk(directory)) {
                            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // Retry on the next hourly cleanup.
                                }
                            });
                        }
                    }
                } catch (IOException ignored) {
                    // One locked attachment must not stop cleanup of the others.
                }
            });
        } catch (IOException ignored) {
            // Storage may be temporarily unavailable; the next run will retry.
        }
    }

    private Path directory(String attachmentId) {
        if (attachmentId == null || !attachmentId.matches("[a-f0-9]{32}")) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "任务附件不存在");
        }
        Path directory = storageRoot.resolve(attachmentId).normalize();
        if (!directory.startsWith(storageRoot)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "任务附件不存在");
        }
        return directory;
    }

    private String safeOriginalName(String value) {
        String name = value == null ? "task-file" : Path.of(value).getFileName().toString().trim();
        name = name.replaceAll("[\\r\\n\\u0000]", "_");
        return name.isBlank() ? "task-file" : name.substring(0, Math.min(name.length(), 180));
    }

    private String safeContentType(String value, String fileName) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (normalized.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")) return normalized;
        String lowerName = fileName == null ? "" : fileName.toLowerCase();
        if (lowerName.endsWith(".png")) return "image/png";
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) return "image/jpeg";
        if (lowerName.endsWith(".webp")) return "image/webp";
        if (lowerName.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

    public record StoredAttachment(String id, String name, long size, String reference) {
    }

    public record AttachmentFile(Path path, String name, long size, String contentType) {
    }
}
