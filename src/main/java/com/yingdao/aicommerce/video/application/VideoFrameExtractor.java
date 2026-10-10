package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class VideoFrameExtractor {

    private static final int MIN_FRAME_COUNT = 12;
    private static final int MAX_FRAME_COUNT = 48;
    private static final int MIN_MOTION_FRAME_COUNT = 24;
    private static final int MAX_MOTION_FRAME_COUNT = 300;
    private static final double MOTION_TARGET_FPS = 5.0;

    public ExtractedFrames extract(Path video, Path frameDirectory) {
        if (!Files.isRegularFile(video)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "上传的视频文件不存在");
        }
        try {
            resetDirectory(frameDirectory);
            double duration = probeDuration(video);
            int frameCount = Math.min(MAX_FRAME_COUNT, Math.max(MIN_FRAME_COUNT, (int) Math.ceil(duration * 3.0)));
            double fps = Math.max(0.05, frameCount / Math.max(duration, 1.0));
            String filter = String.format(
                    Locale.ROOT,
                    "fps=%.6f,scale=768:-2:force_original_aspect_ratio=decrease",
                    fps
            );
            ProcessResult result = run(
                    List.of(
                            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                            "-i", video.toString(),
                            "-vf", filter,
                            "-frames:v", String.valueOf(frameCount),
                            "-q:v", "4",
                            frameDirectory.resolve("frame-%03d.jpg").toString()
                    ),
                    Duration.ofMinutes(3)
            );
            if (result.exitCode() != 0) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "视频关键帧提取失败：" + concise(result.output())
                );
            }
            List<Path> frames;
            try (var stream = Files.list(frameDirectory)) {
                frames = stream
                        .filter(path -> path.getFileName().toString().endsWith(".jpg"))
                        .sorted()
                        .toList();
            }
            if (frames.isEmpty()) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "视频中没有提取到可分析画面");
            }
            return new ExtractedFrames(duration, fps, frames);
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "视频关键帧处理失败");
        }
    }

    public ExtractedFrames extractMotionFrames(Path video, Path frameDirectory, double durationSeconds) {
        if (!Files.isRegularFile(video)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "上传的视频文件不存在");
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "视频时长无效");
        }
        try {
            resetDirectory(frameDirectory);
            int frameCount = Math.min(
                    MAX_MOTION_FRAME_COUNT,
                    Math.max(MIN_MOTION_FRAME_COUNT, (int) Math.ceil(durationSeconds * MOTION_TARGET_FPS))
            );
            double fps = frameCount / durationSeconds;
            String filter = String.format(
                    Locale.ROOT,
                    "fps=%.6f,scale=240:-2:force_original_aspect_ratio=decrease",
                    fps
            );
            ProcessResult result = run(
                    List.of(
                            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                            "-i", video.toString(),
                            "-vf", filter,
                            "-frames:v", String.valueOf(frameCount),
                            "-q:v", "6",
                            frameDirectory.resolve("motion-%04d.jpg").toString()
                    ),
                    Duration.ofMinutes(3)
            );
            if (result.exitCode() != 0) {
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "视频运镜分析帧提取失败：" + concise(result.output())
                );
            }
            List<Path> frames;
            try (var stream = Files.list(frameDirectory)) {
                frames = stream
                        .filter(path -> path.getFileName().toString().endsWith(".jpg"))
                        .sorted()
                        .toList();
            }
            if (frames.size() < 2) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "视频中没有足够的运镜分析画面");
            }
            return new ExtractedFrames(durationSeconds, fps, frames);
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "视频运镜分析帧处理失败");
        }
    }

    public double probeDuration(Path video) {
        if (!Files.isRegularFile(video)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "上传的视频文件不存在");
        }
        ProcessResult result = run(
                List.of(
                        "ffprobe", "-v", "error",
                        "-show_entries", "format=duration",
                        "-of", "default=noprint_wrappers=1:nokey=1",
                        video.toString()
                ),
                Duration.ofSeconds(30)
        );
        if (result.exitCode() != 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "无法读取视频时长");
        }
        try {
            double duration = Double.parseDouble(result.output().trim());
            if (!Double.isFinite(duration) || duration <= 0 || duration > 600) {
                throw new NumberFormatException();
            }
            return duration;
        } catch (NumberFormatException exception) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "视频时长无效或超过 10 分钟");
        }
    }

    public void extractShotPreviews(Path video, Path previewDirectory, Map<Integer, Double> sourceTimestamps) {
        if (!Files.isRegularFile(video)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "上传的视频文件不存在");
        }
        Path staging = null;
        try {
            // A reanalysis may assign different evidence times to each shot.
            // An empty directory marks this generation even if rendering fails,
            // so readers cannot reuse previous exact or legacy thumbnails.
            resetDirectory(previewDirectory);
            staging = Files.createTempDirectory(previewDirectory.getParent(), "shot-preview-");
            for (var entry : sourceTimestamps.entrySet()) {
                double timestamp = entry.getValue();
                if (!Double.isFinite(timestamp) || timestamp < 0) {
                    throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "分镜预览时间无效");
                }
                Path image = staging.resolve(String.format(Locale.ROOT, "shot-%03d.jpg", entry.getKey()));
                ProcessResult result = run(List.of(
                        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                        "-ss", String.format(Locale.ROOT, "%.3f", timestamp), "-i", video.toString(),
                        "-frames:v", "1", "-vf", "scale=768:-2:force_original_aspect_ratio=decrease",
                        "-q:v", "4", image.toString()), Duration.ofSeconds(30));
                if (result.exitCode() != 0 || !Files.isRegularFile(image) || Files.size(image) == 0) {
                    throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "分镜预览生成失败：" + concise(result.output()));
                }
            }
            Files.createDirectories(previewDirectory);
            try (var images = Files.list(staging)) {
                for (Path image : images.toList()) {
                    Files.move(image, previewDirectory.resolve(image.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "分镜预览保存失败");
        } finally {
            if (staging != null) {
                try (var files = Files.list(staging)) {
                    for (Path file : files.toList()) Files.deleteIfExists(file);
                    Files.deleteIfExists(staging);
                } catch (IOException ignored) { /* Best-effort removal of incomplete preview staging files. */ }
            }
        }
    }

    public void trim(Path source, Path output, double startSeconds, double durationSeconds) {
        if (!Files.isRegularFile(source)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "上传的视频文件不存在");
        }
        if (!Double.isFinite(startSeconds) || startSeconds < 0
                || !Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "视频截取范围无效");
        }
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedOutput = output.toAbsolutePath().normalize();
        Path parent = normalizedOutput.getParent();
        if (parent == null || normalizedSource.equals(normalizedOutput)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "视频截取输出路径无效");
        }
        try {
            Files.createDirectories(parent);
            ProcessResult result = run(
                    List.of(
                            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                            "-ss", String.format(Locale.ROOT, "%.6f", startSeconds),
                            "-i", normalizedSource.toString(),
                            "-t", String.format(Locale.ROOT, "%.6f", durationSeconds),
                            "-map", "0:v:0", "-map", "0:a?", "-sn", "-dn",
                            "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
                            "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
                            "-movflags", "+faststart", "-avoid_negative_ts", "make_zero",
                            normalizedOutput.toString()
                    ),
                    Duration.ofMinutes(5)
            );
            if (result.exitCode() != 0 || !Files.isRegularFile(normalizedOutput)
                    || Files.size(normalizedOutput) == 0) {
                Files.deleteIfExists(normalizedOutput);
                throw new BusinessException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "视频截取失败：" + concise(result.output())
                );
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "视频截取文件保存失败");
        }
    }

    private ProcessResult run(List<String> command, Duration timeout) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(HttpStatus.GATEWAY_TIMEOUT, "视频处理超时");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new ProcessResult(process.exitValue(), output);
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "服务器缺少 FFmpeg 视频处理组件");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "视频处理任务已中断");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private void resetDirectory(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // A later create/write call will surface any persistent storage problem.
                    }
                });
            }
        }
        Files.createDirectories(directory);
    }

    private String concise(String value) {
        String normalized = value == null ? "未知错误" : value.replaceAll("\\s+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 500));
    }

    private record ProcessResult(int exitCode, String output) {
    }

    public record ExtractedFrames(double durationSeconds, double samplingFps, List<Path> files) {
        public double timestampSeconds(int zeroBasedIndex) {
            return Math.min(durationSeconds, zeroBasedIndex / samplingFps);
        }
    }
}
