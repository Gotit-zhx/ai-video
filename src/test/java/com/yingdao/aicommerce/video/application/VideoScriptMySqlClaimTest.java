package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptShotMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Opt-in only, against a fresh disposable MySQL container, never the application database. */
@EnabledIfEnvironmentVariable(named = "HEYI_VIDEO_CLAIM_TEST_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_video_claim_test(?:\\?.*)?")
class VideoScriptMySqlClaimTest {
    @TempDir Path storage;

    @Test
    void migrationsTimestampPrecisionAndConcurrentClaimsUseRealMySql() throws Exception {
        var dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                System.getenv("HEYI_VIDEO_CLAIM_TEST_URL"), "root", "");
        try (var connection = dataSource.getConnection()) {
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                assertThat(tables.next()).as("A fresh empty test database is required").isFalse();
            }
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE organizations (id VARCHAR(36) PRIMARY KEY)");
                statement.execute("CREATE TABLE app_users (id VARCHAR(36) PRIMARY KEY)");
                statement.execute("INSERT INTO organizations VALUES ('org')");
                statement.execute("INSERT INTO app_users VALUES ('user')");
            }
            for (String migration : List.of("V15__add_video_script_management.sql",
                    "V16__guard_single_active_video_script.sql", "V41__add_video_script_presentation_mode.sql",
                    "V42__add_video_script_audio_extraction.sql", "V43__allow_parallel_video_script_analysis.sql", "V46__add_video_script_clip_window.sql",
                    "V101__video_script_analysis_output.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + migration));
            }
        }
        var configuration = new MybatisConfiguration(new Environment("claim-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(VideoScriptMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        VideoScriptAnalysisProcessor dispatch = mock(VideoScriptAnalysisProcessor.class);

        try (var session = factory.openSession(true)) {
            var mapper = session.getMapper(VideoScriptMapper.class);
            var service = service(mapper, dispatch);
            VideoScript first = service.createAnalysis(video(), "first", null, null, "MODEL_WEAR", false, principal());
            VideoScript second = service.createAnalysis(video(), "second", null, null, "MODEL_WEAR", false, principal());
            assertThat(first.getId()).isNotEqualTo(second.getId());
            assertThat(mapper.selectById(first.getId()).getStatus()).isEqualTo("QUEUED");
            assertThat(mapper.selectById(second.getId()).getStatus()).isEqualTo("QUEUED");
            verify(dispatch).analyze(first.getId());
            verify(dispatch).analyze(second.getId());

            VideoScript ready = script("precision");
            ready.complete("summary", "tone", "{}");
            ReflectionTestUtils.setField(ready, "updatedAt", Instant.parse("2026-09-15T01:02:03.123456789Z"));
            mapper.insert(ready);
            session.clearCache();
            Instant persisted = mapper.selectById("precision").getUpdatedAt();
            assertThat(persisted.getNano() % 1_000).isZero();
            assertThat(persisted.getNano()).isNotZero();
            assertThat(service.reanalyze("precision", principal()).getStatus()).isEqualTo("QUEUED");
            session.clearCache();
            VideoScript queued = mapper.selectById("precision");
            assertThat(queued.getStatus()).isEqualTo("QUEUED");
            assertThat(queued.getVersion()).isEqualTo(2);
            assertThat(queued.getCompletedAt()).isNull();
            assertThat(queued.getErrorMessage()).isNull();

            VideoScript failed = script("retry-race");
            failed.fail("upstream error");
            mapper.insert(failed);
            mapper.insert(script("worker-race"));
        }

        CyclicBarrier retryReaders = new CyclicBarrier(2);
        Callable<Integer> retry = () -> {
            try (var session = factory.openSession(true)) {
                var mapper = synchronizeFirstRead(session.getMapper(VideoScriptMapper.class), retryReaders);
                try {
                    service(mapper, dispatch).retry("retry-race", principal());
                    return 1;
                } catch (BusinessException conflict) {
                    assertThat(conflict.getStatus().value()).isEqualTo(409);
                    return 0;
                }
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(retry);
            var second = executor.submit(retry);
            assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        verify(dispatch, times(1)).analyze("retry-race");

        VideoFrameExtractor frames = mock(VideoFrameExtractor.class);
        when(frames.probeDuration(any())).thenThrow(new IllegalStateException("test stops before any model request"));
        ModelConfigService models = mock(ModelConfigService.class);
        CyclicBarrier workerReaders = new CyclicBarrier(2);
        Callable<Void> worker = () -> {
            try (var session = factory.openSession(true)) {
                var mapper = synchronizeFirstRead(session.getMapper(VideoScriptMapper.class), workerReaders);
                var processor = new VideoScriptAnalysisProcessor(mapper, mock(VideoScriptShotMapper.class), models,
                        frames, mock(VideoCameraMotionAnalyzer.class), mock(DayanAudioTranscriptionService.class), mock(ObjectMapper.class),
                        mock(TransactionTemplate.class), "http://127.0.0.1:1", "test");
                processor.analyze("worker-race");
                return null;
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(worker);
            var second = executor.submit(worker);
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        verify(frames, times(1)).probeDuration(any());
        verifyNoInteractions(models);
        try (var session = factory.openSession(true)) {
            assertThat(session.getMapper(VideoScriptMapper.class).selectById("worker-race").getStatus()).isEqualTo("FAILED");
        }
    }

    private VideoScriptMapper synchronizeFirstRead(VideoScriptMapper delegate, CyclicBarrier barrier) {
        AtomicBoolean firstRead = new AtomicBoolean(true);
        return (VideoScriptMapper) Proxy.newProxyInstance(VideoScriptMapper.class.getClassLoader(),
                new Class<?>[]{VideoScriptMapper.class}, (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (method.getName().equals("selectById") && firstRead.getAndSet(false)) {
                            barrier.await(10, TimeUnit.SECONDS);
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private VideoFrameExtractor probe() {
        VideoFrameExtractor probe = mock(VideoFrameExtractor.class);
        org.mockito.Mockito.when(probe.probeDuration(org.mockito.ArgumentMatchers.any())).thenReturn(10.0);
        return probe;
    }

    private VideoScriptService service(VideoScriptMapper mapper, VideoScriptAnalysisProcessor processor) {
        return new VideoScriptService(storage.toString(), mapper, mock(VideoScriptShotMapper.class), processor, probe(), mock(ObjectMapper.class));
    }

    private VideoScript script(String id) {
        return new VideoScript(id, "org", id, "VIDEO_ANALYSIS", "MODEL_WEAR", "video.mp4",
                storage.resolve(id).resolve("video.mp4").toString(), "video/mp4", 3L, null, null, "user");
    }

    private MockMultipartFile video() {
        return new MockMultipartFile("video", "video.mp4", "video/mp4", new byte[]{1, 2, 3});
    }

    private PlatformPrincipal principal() {
        return new PlatformPrincipal("user", "org", "org", "13800000000", "test", "password", true,
                List.of("ADMIN"), List.of("video:script:view", "video:script:manage"));
    }
}
