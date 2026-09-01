package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.domain.VideoScript;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class DayanAudioTranscriptionService {
    private static final Logger log = LoggerFactory.getLogger(DayanAudioTranscriptionService.class);
    private static final String API_BASE = "https://api.dayan.design";
    private static final Duration POLL_TIMEOUT = Duration.ofMinutes(8);
    private static final long[] READ_RETRY_DELAYS_MILLIS = {500L, 1_500L, 3_000L};
    private static final String TRANSCRIPTION_AUDIO_FILE = "transcription-audio.mp3";
    private static final String TRANSCRIPTION_RESULT_FILE = "transcription-result.json";
    private final ModelConfigService modelConfigs;
    private final OssStorageService ossStorage;
    private final VideoReferenceLinkService referenceLinks;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public DayanAudioTranscriptionService(ModelConfigService modelConfigs,
                                          ObjectProvider<OssStorageService> ossStorage,
                                          VideoReferenceLinkService referenceLinks,
                                          ObjectMapper objectMapper) {
        this.modelConfigs = modelConfigs;
        this.ossStorage = ossStorage.getIfAvailable();
        this.referenceLinks = referenceLinks;
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        this.client = RestClient.builder().baseUrl(API_BASE).requestFactory(requestFactory).build();
    }

    public Transcript transcribe(VideoScript script) throws Exception {
        if (!script.isExtractAudio()) return Transcript.empty();
        Path cachedResult = transcriptionResult(script);
        if (Files.isRegularFile(cachedResult) && Files.size(cachedResult) > 2L) {
            try {
                return parseTranscript(json(Files.readString(cachedResult)));
            } catch (Exception exception) {
                log.warn("脚本 {} 的本地转写缓存无效，将重新转写：{}", script.getId(), exception.getMessage());
                Files.deleteIfExists(cachedResult);
            }
        }
        String sourceUrl = sourceUrl(script);
        ModelConfigService.RuntimeModelConnection connection = modelConfigs
                .dayanConnection(script.getOrganizationId())
                .orElseThrow(() -> new IllegalStateException("请先为当前公司配置并启用大衍视频模型 Key"));
        Map<String, Object> body = Map.of(
                "model", "qwen-audio-3.0-asr-flash-filetrans",
                "input", Map.of("file_urls", List.of(sourceUrl)),
                "parameters", Map.of("channel_id", List.of(0), "language_hints", List.of("zh", "en"))
        );
        Map<String, Object> submitted = json(client.post()
                .uri("/api/v1/services/audio/asr/transcription")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + connection.apiKey())
                .header("X-DashScope-Async", "enable")
                .body(body).retrieve().body(String.class));
        String taskId = firstText(submitted, "task_id", "id");
        if (taskId == null) taskId = firstText(map(submitted.get("output")), "task_id", "id");
        if (taskId == null) throw new IllegalStateException("大衍转写提交成功，但未返回 task_id");

        Instant deadline = Instant.now().plus(POLL_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            Map<String, Object> task = json(readWithRetry(
                    "/api/v1/tasks/" + taskId, connection.apiKey(), "轮询转写任务 " + taskId));
            Map<String, Object> output = map(task.get("output"));
            String status = firstText(output, "task_status", "status");
            if (status == null) status = firstText(task, "task_status", "status");
            String normalized = status == null ? "" : status.toUpperCase();
            if (List.of("SUCCEEDED", "SUCCESS", "COMPLETED").contains(normalized)) {
                String resultUrl = transcriptionUrl(output);
                if (resultUrl == null) throw new IllegalStateException("大衍转写已完成，但未返回结果文档地址");
                String resultDocument = readWithRetry(
                        resultUrl, connection.apiKey(), "下载转写结果文档");
                cacheTranscriptionResult(cachedResult, resultDocument);
                return parseTranscript(json(resultDocument));
            }
            if (List.of("FAILED", "CANCELED", "CANCELLED").contains(normalized)) {
                String message = firstText(output, "message", "error_message", "error", "code");
                if (message == null) message = firstText(task, "message", "error_message", "error", "code");
                throw new IllegalStateException("大衍转写失败" + (message == null ? "" : "：" + message));
            }
            Thread.sleep(2_000L);
        }
        throw new IllegalStateException("大衍转写超时，请稍后重试脚本拆解");
    }

    private String readWithRetry(String uri, String apiKey, String operation) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                RestClient.RequestHeadersSpec<?> request = client.get().uri(uri);
                if (apiKey != null && !apiKey.isBlank()) {
                    request = request.header("Authorization", "Bearer " + apiKey);
                }
                return request.retrieve().body(String.class);
            } catch (RestClientException exception) {
                if (!retryableReadFailure(exception) || attempt >= READ_RETRY_DELAYS_MILLIS.length) {
                    throw exception;
                }
                log.warn("大衍{}发生瞬时网络异常，保持同一 task_id 重试 {}/{}：{}",
                        operation, attempt + 2, READ_RETRY_DELAYS_MILLIS.length + 1,
                        exception.getMessage());
                Thread.sleep(READ_RETRY_DELAYS_MILLIS[attempt]);
            }
        }
    }

    static boolean retryableReadFailure(RestClientException exception) {
        if (exception instanceof ResourceAccessException) return true;
        if (exception instanceof RestClientResponseException response) {
            return retryableReadStatus(response.getStatusCode().value());
        }
        return false;
    }

    static boolean retryableReadStatus(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private String sourceUrl(VideoScript script) throws Exception {
        Path audio = extractAudio(script);
        if (ossStorage != null) {
            return ossStorage.publish(audio, "audio/mpeg",
                    "script-transcription-inputs");
        }
        if (!referenceLinks.isPubliclyReachable()) {
            throw new IllegalStateException("本地语音转写需要先启动公网隧道");
        }
        return referenceLinks.scriptAudio(script.getId()) + "?attempt=" + Instant.now().toEpochMilli();
    }

    private Path transcriptionResult(VideoScript script) {
        Path source = Path.of(script.getSourceFilePath()).toAbsolutePath().normalize();
        return source.getParent().resolve(TRANSCRIPTION_RESULT_FILE).normalize();
    }

    private void cacheTranscriptionResult(Path target, String document) throws Exception {
        Path temporary = target.resolveSibling(TRANSCRIPTION_RESULT_FILE + ".part");
        Files.writeString(temporary, document == null ? "{}" : document);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path extractAudio(VideoScript script) throws Exception {
        Path source = Path.of(script.getSourceFilePath()).toAbsolutePath().normalize();
        Path output = source.getParent().resolve(TRANSCRIPTION_AUDIO_FILE).normalize();
        if (Files.isRegularFile(output) && Files.size(output) > 256L) return output;

        Path temporary = source.getParent().resolve("transcription-audio.part.mp3").normalize();
        Files.deleteIfExists(temporary);
        Process process = new ProcessBuilder(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-i", source.toString(), "-vn", "-ac", "1", "-ar", "16000",
                "-b:a", "64k", "-f", "mp3", temporary.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            Files.deleteIfExists(temporary);
            throw new IllegalStateException("提取原视频音轨超时");
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(temporary) || Files.size(temporary) <= 256L) {
            Files.deleteIfExists(temporary);
            throw new NoAudioTrackException("原视频没有可用音轨");
        }
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        return output;
    }

    public static final class NoAudioTrackException extends IllegalStateException {
        public NoAudioTrackException(String message) {
            super(message);
        }
    }

    private Map<String, Object> json(String value) throws Exception {
        if (value == null || value.isBlank()) return Map.of();
        return objectMapper.readValue(value, Map.class);
    }

    private String transcriptionUrl(Map<String, Object> output) {
        for (Object item : list(output.get("results"))) {
            String url = firstText(map(item), "transcription_url", "url");
            if (url != null) return url;
        }
        return firstText(output, "transcription_url", "url");
    }

    static Transcript parseTranscript(Map<String, Object> document) {
        List<Segment> segments = new ArrayList<>();
        collectSegments(document, segments);
        segments.sort((left, right) -> left.startSecond().compareTo(right.startSecond()));
        String fullText = segments.stream().map(Segment::text).filter(value -> !value.isBlank())
                .reduce((left, right) -> left + right).orElse("");
        if (fullText.isBlank()) {
            String fallback = firstText(document, "text", "transcript", "transcription");
            fullText = fallback == null ? "" : fallback;
        }
        return new Transcript(fullText, List.copyOf(segments));
    }

    private static void collectSegments(Object value, List<Segment> segments) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = map(raw);
            String text = firstText(map, "text", "sentence", "transcript");
            String beginKey = firstNumberKey(map, "begin_time", "start_time", "start", "begin");
            String endKey = firstNumberKey(map, "end_time", "end", "finish_time");
            Number begin = beginKey == null ? null : number(map.get(beginKey));
            Number end = endKey == null ? null : number(map.get(endKey));
            if (text != null && begin != null && end != null) {
                segments.add(new Segment(seconds(begin, isMillisecondKey(beginKey)),
                        seconds(end, isMillisecondKey(endKey)),
                        firstText(map, "speaker_id", "speaker"), text.trim()));
                return;
            }
            for (Object child : map.values()) collectSegments(child, segments);
        } else if (value instanceof List<?> list) {
            for (Object child : list) collectSegments(child, segments);
        }
    }

    private static BigDecimal seconds(Number value, boolean milliseconds) {
        BigDecimal number = new BigDecimal(value.toString());
        if (milliseconds) number = number.divide(BigDecimal.valueOf(1_000));
        return number.setScale(3, RoundingMode.HALF_UP);
    }

    private static boolean isMillisecondKey(String key) {
        return "begin_time".equals(key) || "end_time".equals(key) || "finish_time".equals(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }

    private static String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return null;
    }

    private static String firstNumberKey(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (number(map.get(key)) != null) return key;
        }
        return null;
    }

    private static Number number(Object value) {
        if (value instanceof Number number) return number;
        if (value != null) try { return new BigDecimal(String.valueOf(value)); } catch (NumberFormatException ignored) {}
        return null;
    }

    public record Transcript(String fullText, List<Segment> segments) {
        public static Transcript empty() { return new Transcript("", List.of()); }
        public boolean available() { return !fullText.isBlank() || !segments.isEmpty(); }
    }
    public record Segment(BigDecimal startSecond, BigDecimal endSecond, String speaker, String text) {}
}
