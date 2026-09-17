package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.security.MessageDigest;

/**
 * Creates compact, full-resolution delivery copies for external video providers.
 * Originals remain untouched. When OSS is enabled later, this service is the
 * single hand-off point that can be replaced by an object-storage uploader.
 */
@Service
public class VideoReferenceDeliveryService {
    private final Path storageRoot;
    private final Path cacheRoot;

    public VideoReferenceDeliveryService(
            @Value("${app.ai-video-task-storage:/data/ai-video-tasks}") String storageRoot) {
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.cacheRoot = this.storageRoot.resolve("_provider-reference-cache");
    }

    public Delivery prepare(AiVideoTaskAsset asset) {
        try {
            Path source = Path.of(asset.getFilePath()).toAbsolutePath().normalize();
            if (!source.startsWith(storageRoot) || !Files.isRegularFile(source)) {
                throw new IllegalStateException("视频参考素材不存在");
            }
            Files.createDirectories(cacheRoot);
            Path target = cacheRoot.resolve(asset.getId() + ".jpg").normalize();
            if (!target.startsWith(cacheRoot)) throw new IllegalStateException("视频参考素材路径无效");
            if (!Files.isRegularFile(target) || Files.size(target) == 0) {
                writeJpeg(source, target);
            }
            return new Delivery(target, Files.size(target), "image/jpeg");
        } catch (IOException exception) {
            throw new IllegalStateException("无法准备视频参考素材", exception);
        }
    }

    public synchronized List<Delivery> prepareStoryboardFrames(byte[] original) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
            Path directory = cacheRoot.resolve("storyboard-frames-v1").resolve(digest);
            Files.createDirectories(directory);
            List<Path> paths = new ArrayList<>(9);
            for (int index = 1; index <= 9; index++) paths.add(directory.resolve(String.format("%02d.jpg", index)));
            boolean cached = true;
            for (Path path : paths) if (!Files.isRegularFile(path) || Files.size(path) == 0) cached = false;
            if (!cached) {
                BufferedImage input = ImageIO.read(new ByteArrayInputStream(original));
                List<BufferedImage> frames = StoryboardFrameExtractor.split(input);
                for (int index = 0; index < frames.size(); index++) writeJpeg(frames.get(index), paths.get(index));
            }
            List<Delivery> result = new ArrayList<>(9);
            for (Path path : paths) result.add(new Delivery(path, Files.size(path), "image/jpeg"));
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalStateException("无法从九宫格准备九张独立视频参考图", exception);
        }
    }

    private void writeJpeg(Path source, Path target) throws IOException {
        BufferedImage input = ImageIO.read(source.toFile());
        if (input == null) throw new IOException("不支持的参考图片格式");
        writeJpeg(input, target);
    }

    private void writeJpeg(BufferedImage input, Path target) throws IOException {
        BufferedImage rgb = new BufferedImage(input.getWidth(), input.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(input, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        Path temporary = Files.createTempFile(cacheRoot, "reference-", ".jpg");
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JDK 缺少 JPEG 编码器");
        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(temporary.toFile())) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(0.90f);
            }
            writer.write(null, new IIOImage(rgb, null, null), params);
        } finally {
            writer.dispose();
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record Delivery(Path path, long size, String contentType) {}
}
