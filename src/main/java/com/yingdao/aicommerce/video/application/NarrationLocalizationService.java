package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NarrationLocalizationService {
    private static final Pattern SPEECH_PREFIX = Pattern.compile("^(对白|旁白|口播)[：:]\\s*");

    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;
    private final RestClient piClient;

    public NarrationLocalizationService(ModelConfigService modelConfigService,
                                        ObjectMapper objectMapper,
                                        @Value("${app.pi-runtime-url}") String piRuntimeUrl,
                                        @Value("${app.pi-runtime-token}") String piRuntimeToken) {
        this.modelConfigService = modelConfigService;
        this.objectMapper = objectMapper;
        this.piClient = RestClient.builder().baseUrl(piRuntimeUrl)
                .defaultHeader("X-Internal-Token", piRuntimeToken).build();
    }

    public boolean hasNarration(String scriptJson) {
        return narrationShots(scriptJson).size() > 0;
    }

    public LocalizedScript localize(String scriptJson, String targetMarket, String organizationId) {
        String market = targetMarket == null ? "" : targetMarket.trim();
        if (market.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "当前脚本包含旁白，请先选择目标国家");
        }
        List<NarrationShot> sourceShots = narrationShots(scriptJson);
        if (sourceShots.isEmpty()) return new LocalizedScript(scriptJson, "", false);

        ModelConfigService.RuntimeModelConnection model = modelConfigService.driverConnection(organizationId)
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                        "脚本包含旁白，请先在模型接入中配置并启用驱动模型以转换目标语言"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model.driverRequestModel());
        request.put("context", Map.of(
                "targetMarket", market,
                "shots", sourceShots.stream().map(shot -> Map.of(
                        "sequence", shot.sequence(), "narration", shot.narration())).toList()));
        try {
            PiResponse response = piClient.post().uri("/v1/video-script/localize-narration")
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(PiResponse.class);
            if (response == null || response.result() == null) {
                throw new IllegalStateException("模型没有返回本地化旁白");
            }
            return merge(scriptJson, sourceShots, response.result());
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "旁白目标语言转换失败：" + readable(exception));
        }
    }

    @SuppressWarnings("unchecked")
    private List<NarrationShot> narrationShots(String scriptJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(scriptJson, Map.class);
            Object rawShots = root.get("shots");
            if (!(rawShots instanceof List<?> shots)) return List.of();
            List<NarrationShot> result = new ArrayList<>();
            for (Object raw : shots) {
                if (!(raw instanceof Map<?, ?> shot)) continue;
                int sequence = number(shot.get("sequence"));
                String narration = text(shot.get("narration"));
                if (sequence > 0 && !narration.isBlank()) result.add(new NarrationShot(sequence, narration));
            }
            return result;
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "脚本快照无法读取旁白内容");
        }
    }

    @SuppressWarnings("unchecked")
    private LocalizedScript merge(String scriptJson, List<NarrationShot> sourceShots, Map<String, Object> result) {
        Object rawLocalized = result.get("shots");
        if (!(rawLocalized instanceof List<?> localized) || localized.size() != sourceShots.size()) {
            throw new IllegalStateException("本地化旁白分镜数量不完整");
        }
        Map<Integer, String> translated = new LinkedHashMap<>();
        for (Object raw : localized) {
            if (!(raw instanceof Map<?, ?> shot)) throw new IllegalStateException("本地化旁白格式不正确");
            int sequence = number(shot.get("sequence"));
            String narration = text(shot.get("narration"));
            if (sequence <= 0 || narration.isBlank() || translated.put(sequence, narration) != null) {
                throw new IllegalStateException("本地化旁白包含缺失或重复分镜");
            }
        }
        for (NarrationShot source : sourceShots) {
            if (!translated.containsKey(source.sequence())) {
                throw new IllegalStateException("本地化旁白缺少第 " + source.sequence() + " 镜");
            }
            translated.put(source.sequence(), preservePrefix(source.narration(), translated.get(source.sequence())));
        }
        try {
            Map<String, Object> root = objectMapper.readValue(scriptJson, Map.class);
            List<?> rawShots = (List<?>) root.get("shots");
            List<Map<String, Object>> mergedShots = new ArrayList<>(rawShots.size());
            for (Object raw : rawShots) {
                if (!(raw instanceof Map<?, ?> source)) continue;
                Map<String, Object> shot = new LinkedHashMap<>();
                source.forEach((key, value) -> shot.put(String.valueOf(key), value));
                int sequence = number(source.get("sequence"));
                if (translated.containsKey(sequence)) shot.put("narration", translated.get(sequence));
                mergedShots.add(shot);
            }
            root.put("shots", mergedShots);
            String language = targetText(result.get("targetLanguage"), 80);
            if (language.isBlank()) throw new IllegalStateException("本地化结果没有返回目标语言");
            root.put("narrationLanguage", language);
            return new LocalizedScript(objectMapper.writeValueAsString(root), language, true);
        } catch (Exception exception) {
            throw new IllegalStateException("本地化旁白无法写入任务脚本快照", exception);
        }
    }

    static String preservePrefix(String original, String localized) {
        Matcher originalMatcher = SPEECH_PREFIX.matcher(original == null ? "" : original.trim());
        Matcher localizedMatcher = SPEECH_PREFIX.matcher(localized == null ? "" : localized.trim());
        String body = localizedMatcher.find() ? localized.substring(localizedMatcher.end()).trim() : localized.trim();
        return originalMatcher.find() ? originalMatcher.group(1) + "：" + body : body;
    }

    private int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return 0; }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String targetText(Object value, int max) {
        String text = text(value);
        return text.substring(0, Math.min(text.length(), max));
    }

    private String readable(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record NarrationShot(int sequence, String narration) {}
    private record PiResponse(Map<String, Object> result, String modelId, long latencyMs) {}
    public record LocalizedScript(String scriptJson, String language, boolean localized) {}
}
