package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Keeps originals intact. Normalized media is content-addressed and reused across upload retries. */
class ShopeeVideoPreprocessor {
    static final long LIMIT = 30_000_000L; // Conservative decimal MB, below 30 MiB too.
    private final Path cache;
    ShopeeVideoPreprocessor(Path root) { cache = root.resolve("shopee-normalized-v1"); }

    synchronized byte[] prepare(byte[] original) {
        Path input = null, output = null;
        try {
            Files.createDirectories(cache);
            String key = ShopeeProductVideoUploader.digest("SHA-256", original);
            Path saved = cache.resolve(key + ".mp4"), checksum = cache.resolve(key + ".sha256");
            if (Files.isRegularFile(saved, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(checksum, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.size(saved) > LIMIT) throw failure("视频压缩缓存大小异常");
                byte[] bytes = Files.readAllBytes(saved);
                if (!ShopeeProductVideoUploader.digest("SHA-256", bytes).equals(Files.readString(checksum)))
                    throw failure("视频压缩缓存校验失败，请联系管理员修复");
                return bytes;
            }
            input = Files.createTempFile(cache, "source-", ".bin");
            Files.write(input, original);
            double duration = duration(input);
            if (duration < 9.5 || duration > 60.0)
                throw failure(String.format(Locale.ROOT, "Shopee 商品视频须为 10–60 秒，当前为 %.3f 秒；请更换视频（不会自动裁剪或循环）", duration));
            if (duration >= 10 && original.length <= LIMIT) return original;
            // Only sub-second shortfall is padded. Video keeps all original frames; audio is not looped.
            double target = duration < 10 ? 10.2 : duration;
            long bitrate = Math.min(6_000_000L, (long) (27_000_000L * 8 / target) - 128_000L);
            output = Files.createTempFile(cache, "encoded-", ".mp4");
            List<String> command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-v", "error", "-y", "-protocol_whitelist", "file,pipe", "-i", input.toString(),
                    "-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "-1", "-map_chapters", "-1", "-vf",
                    "scale=w='min(1280,iw)':h='min(1280,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1" +
                    (duration < 10 ? ",tpad=stop_mode=clone:stop_duration=1.2" : ""),
                    "-c:v", "libx264", "-preset", "fast", "-threads", "2", "-pix_fmt", "yuv420p", "-crf", "23",
                    "-maxrate", String.valueOf(bitrate), "-bufsize", String.valueOf(bitrate * 2), "-c:a", "aac", "-b:a", "128k",
                    "-t", String.format(Locale.ROOT, "%.3f", target), "-movflags", "+faststart", output.toString()));
            run(command, 180);
            double actual = duration(output);
            if (Files.size(output) < 12 || Files.size(output) > LIMIT || actual < 10 || actual > 60.0)
                throw failure("自动处理后的视频仍未满足 Shopee 10–60 秒、30 MB 限制，请更换视频");
            byte[] result = Files.readAllBytes(output);
            Files.move(output, saved, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(checksum, ShopeeProductVideoUploader.digest("SHA-256", result));
            return result;
        } catch (java.io.IOException e) {
            throw failure("视频预处理不可用，请检查 FFmpeg 和存储空间，商品尚未提交");
        } finally {
            for (Path p : new Path[]{input, output}) if (p != null) try { Files.deleteIfExists(p); } catch (java.io.IOException ignored) { }
        }
    }

    private double duration(Path file) throws java.io.IOException {
        String value = run(List.of("ffprobe", "-protocol_whitelist", "file,pipe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", file.toString()), 15).trim();
        if (value.isEmpty() || value.equals("N/A")) value = run(List.of("ffprobe", "-protocol_whitelist", "file,pipe", "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", file.toString()), 15).trim();
        try { double d = Double.parseDouble(value); if (Double.isFinite(d) && d > 0) return d; }
        catch (NumberFormatException ignored) { }
        throw failure("无法读取视频时长，商品尚未提交");
    }
    private String run(List<String> command, int timeout) throws java.io.IOException {
        Path log = Files.createTempFile(cache, "process-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) throw failure("视频预处理超时，商品尚未提交，请稍后重试");
            if (process.exitValue() != 0) throw failure("视频预处理失败，请确认视频可正常播放，商品尚未提交");
            return Files.readString(log);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new VideoUploadPendingException("视频预处理被中断，后台稍后重试", e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }
    private BusinessException failure(String text) { return new BusinessException(HttpStatus.CONFLICT, text); }
}
