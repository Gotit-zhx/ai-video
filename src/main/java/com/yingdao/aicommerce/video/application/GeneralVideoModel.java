package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.video.application.provider.DayanVideoGenerationProvider;

import java.util.List;

public record GeneralVideoModel(String id, String label, List<String> resolutions) {
    static final List<GeneralVideoModel> SUPPORTED = List.of(
            new GeneralVideoModel(DayanVideoGenerationProvider.MODEL, "Seedance 2.0 Mini", List.of("480p", "720p")),
            new GeneralVideoModel("doubao-seedance-2-0-fast-260128", "Seedance 2.0 Fast", List.of("480p", "720p")),
            new GeneralVideoModel("doubao-seedance-2-0-260128", "Seedance 2.0", List.of("480p", "720p", "1080p", "4k"))
    );
    static final List<String> ASPECT_RATIOS = List.of("adaptive", "9:16", "16:9", "1:1", "4:3", "3:4", "21:9");
    static final int MIN_DURATION = 4;
    static final int MAX_DURATION = 15;

    public record Options(List<GeneralVideoModel> models, List<String> aspectRatios, int minDuration, int maxDuration) {}
}
