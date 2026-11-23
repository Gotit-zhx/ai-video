package com.yingdao.aicommerce.video.application;

import com.sun.net.httpserver.HttpServer;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.application.provider.VideoGenerationProvider;
import com.yingdao.aicommerce.video.application.provider.VideoProviderRegistry;
import com.yingdao.aicommerce.video.domain.AiVideoGenerationJob;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.mapper.AiVideoGenerationJobMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoDownloadRecoveryTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @SuppressWarnings("unchecked")
    void resumesOriginalTextOnlyVideoUnderTaskStorageWithoutSubmittingAgain(boolean reviewPassed) throws Exception {
        var tasks = mock(AiVideoTaskMapper.class);
        var items = mock(AiVideoTaskItemMapper.class);
        var jobs = mock(AiVideoGenerationJobMapper.class);
        var assets = mock(AiVideoTaskAssetMapper.class);
        var configs = mock(ModelConfigService.class);
        var provider = mock(VideoGenerationProvider.class);
        var providers = mock(VideoProviderRegistry.class);
        var review = mock(VideoFirstFrameAuditService.class);
        var finalizer = mock(VideoReviewFinalizer.class);
        ObjectProvider<OssStorageService> oss = mock(ObjectProvider.class);
        var service = new AiVideoGenerationService(tasks, items, assets, jobs, null, configs, providers,
                null, null, oss, new ObjectMapper(), Runnable::run, 1);
        ReflectionTestUtils.setField(service, "taskStorageRoot", root.toString());
        service.setFirstFrameAudit(review);
        service.setReviewFinalizer(finalizer);

        var task = AiVideoTask.general("org", "日出", "{}", -1, "adaptive", "720p", "{}", "{}", "user");
        var item = new AiVideoTaskItem("org", task.getId(), task.getName(), "REF-001", "", "", "", 0);
        var route = new RuntimeModelConnection("config", "https://example.test", "seedance", "OPENAI", "DAYAN", "test");
        var job = new AiVideoGenerationJob("run", "org", task.getId(), item.getId(), 1,
                route.id(), route.provider(), route.modelId(), -1, "adaptive", "720p");
        job.accepted("existing-provider-task", "RUNNING", Instant.now());
        job.retryDownload("厂商已完成，视频回传待重试：临时文件写入失败", Instant.now());
        when(jobs.selectById(job.getId())).thenReturn(job);
        when(tasks.selectById(task.getId())).thenReturn(task);
        when(items.selectById(item.getId())).thenReturn(item);
        when(configs.connectionById("org", route.id())).thenReturn(Optional.of(route));
        when(providers.require("DAYAN")).thenReturn(provider);

        AtomicReference<AiVideoTaskAsset> saved = new AtomicReference<>();
        when(assets.selectList(any())).thenAnswer(call -> saved.get() == null ? List.of() : List.of(saved.get()));
        when(assets.insert(any(AiVideoTaskAsset.class))).thenAnswer(call -> {
            saved.set(call.getArgument(0));
            return 1;
        });
        Path directory = root.resolve("org").resolve(task.getId()).resolve(item.getId());
        Path video = directory.resolve("video-" + job.getId() + ".mp4");
        when(review.audit(task, item, video.toString(), job.getId())).thenReturn(
                new VideoFirstFrameAuditService.AuditResult(reviewPassed ? "SINGLE_SCENE" : "GRID", 1.0, "test", null, 0));

        byte[] bytes = {0, 1, 2, 3, 4, 5};
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/original", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        http.start();
        try {
            String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/original";
            when(provider.query(route, "existing-provider-task")).thenReturn(new VideoGenerationProvider.QueryResult(
                    "SUCCEEDED", url, null, Map.of(), "","{}", "{}"));

            ReflectionTestUtils.invokeMethod(service, "poll", job.getId());

            assertThat(Files.readAllBytes(video)).isEqualTo(bytes);
            assertThat(directory.resolve("video-" + job.getId() + ".part")).doesNotExist();
            assertThat(saved.get().getFilePath()).isEqualTo(video.toString());
            assertThat(job.getProviderTaskId()).isEqualTo("existing-provider-task");
            assertThat(job.getStatus()).isEqualTo(reviewPassed ? "SUCCEEDED" : "FAILED");
            if (!reviewPassed) assertThat(job.getErrorMessage()).startsWith(AiVideoGenerationService.REVIEW_PAUSED);
            verify(finalizer).finish(job, item, saved.get(), reviewPassed);
            verify(provider).query(route, "existing-provider-task");
            verify(provider, never()).submit(any(), any());
            verify(jobs, never()).insert(any(AiVideoGenerationJob.class));
        } finally {
            http.stop(0);
        }
    }
}
