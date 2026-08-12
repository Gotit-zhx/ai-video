package com.yingdao.aicommerce.video.application.provider;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class VideoProviderRegistry {
    private final Map<String, VideoGenerationProvider> providers;

    public VideoProviderRegistry(List<VideoGenerationProvider> providers) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(VideoGenerationProvider::code, Function.identity()));
    }

    public VideoGenerationProvider require(String code) {
        VideoGenerationProvider provider = providers.get(code);
        if (provider == null) throw new VideoProviderException("当前 API 地址无法识别厂商，请在模型接入中选择大衍或文高云", true);
        return provider;
    }
}
