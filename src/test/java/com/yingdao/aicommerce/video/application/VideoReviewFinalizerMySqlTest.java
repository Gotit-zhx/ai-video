package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.yingdao.aicommerce.video.domain.AiVideoGenerationJob;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.mapper.AiVideoGenerationJobMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Real Spring/MyBatis transactions, opt-in only against an empty disposable MySQL database. */
@EnabledIfEnvironmentVariable(named = "HEYI_VIDEO_REVIEW_TEST_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_video_review_test(?:\\?.*)?")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VideoReviewFinalizerMySqlTest {
    @TempDir Path storage;
    private UnpooledDataSource dataSource;
    private AiVideoGenerationJobMapper jobs;
    private AiVideoTaskAssetMapper assets;
    private AiVideoTaskItemMapper items;
    private VideoReviewFinalizer finalizer;

    @BeforeAll
    void initializeDisposableDatabase() throws Exception {
        dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                System.getenv("HEYI_VIDEO_REVIEW_TEST_URL"), "root", "");
        try (var connection = dataSource.getConnection()) {
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                assertThat(tables.next()).as("Only a fresh empty test database is allowed").isFalse();
            }
            try (var statement = connection.createStatement()) {
                for (String table : List.of("organizations", "app_users", "video_scripts")) {
                    statement.execute("CREATE TABLE " + table + " (id VARCHAR(36) PRIMARY KEY)");
                }
                statement.execute("CREATE TABLE agent_model_configs (id VARCHAR(36) PRIMARY KEY, organization_id VARCHAR(36), "
                        + "model_type VARCHAR(40), status VARCHAR(32), default_model BOOLEAN, updated_at DATETIME(6))");
                statement.execute("INSERT INTO organizations VALUES ('org')");
                statement.execute("INSERT INTO app_users VALUES ('user')");
                statement.execute("INSERT INTO agent_model_configs (id) VALUES ('model-config')");
            }
            for (String migration : List.of("V17__add_ai_video_image_tasks.sql", "V18__add_ai_video_item_prompts.sql",
                    "V22__add_video_provider_failover.sql", "V34__add_product_function_analysis.sql",
                    "V36__store_video_provider_json.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + migration));
            }
            String referenceFields = new ClassPathResource("db/migration/V31__add_direct_video_reference_images.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(referenceFields
                    .substring(referenceFields.indexOf("ALTER TABLE ai_video_task_assets")).getBytes(StandardCharsets.UTF_8)));
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO ai_video_tasks (id,organization_id,name,script_version,script_name,script_json,"
                        + "status,created_by,created_at,updated_at) VALUES ('task','org','test',1,'test','{}',"
                        + "'VIDEO_GENERATING','user',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            }
        }
        var configuration = new MybatisConfiguration(new Environment("review-test", new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AiVideoGenerationJobMapper.class);
        configuration.addMapper(AiVideoTaskAssetMapper.class);
        configuration.addMapper(AiVideoTaskItemMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        jobs = session.getMapper(AiVideoGenerationJobMapper.class);
        assets = session.getMapper(AiVideoTaskAssetMapper.class);
        items = session.getMapper(AiVideoTaskItemMapper.class);
        finalizer = transactional(items);
    }

    @Test
    void publicationArchivesPreviousWithoutRemovingBytesAndRepeatedCallbackDoesNothing() throws Exception {
        Fixture fixture = fixture();
        fixture.job.succeeded("https://provider.example/video", BigDecimal.ONE, "{}");
        assertThat(finalizer.finish(fixture.job, fixture.item, fixture.candidate, true)).isTrue();
        assertThat(jobs.selectById(fixture.job.getId()).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(items.selectById(fixture.item.getId()).getStatus()).isEqualTo("VIDEO_READY");
        assertThat(assets.selectById(fixture.candidate.getId()).getAssetType()).isEqualTo("VIDEO_RESULT");
        AiVideoTaskAsset archived = assets.selectById(fixture.previous.getId());
        assertThat(archived.getAssetType()).isEqualTo("VIDEO_PREVIOUS_RESULT");
        assertThat(archived.getShotSequence()).isNull();
        assertThat(archived.getFilePath()).isEqualTo(fixture.previous.getFilePath());
        assertThat(archived.getSizeBytes()).isEqualTo(fixture.previous.getSizeBytes());
        assertThat(Files.readString(Path.of(archived.getFilePath()))).isEqualTo("previous video bytes");
        assertThat(finalizer.finish(fixture.job, fixture.item, fixture.candidate, true)).isFalse();
        assertThat(assets.selectById(fixture.candidate.getId()).getAssetType()).isEqualTo("VIDEO_RESULT");
    }

    @Test
    void wrongCandidateRollsBackClaimAndPreviousArchive() throws Exception {
        Fixture fixture = fixture();
        Fixture unrelated = fixture();
        fixture.job.succeeded("output", BigDecimal.ONE, "{}");
        assertThatThrownBy(() -> finalizer.finish(fixture.job, fixture.item, unrelated.candidate, true))
                .isInstanceOf(IllegalStateException.class);
        assertUnpublished(fixture);
        assertThat(assets.selectById(unrelated.candidate.getId()).getAssetType()).isEqualTo("VIDEO_REVIEW_CANDIDATE");
    }

    @Test
    void failedAuditKeepsOldResultAndCandidate() throws Exception {
        Fixture fixture = fixture();
        fixture.job.failed("ACCEPTED", "first frame rejected");
        assertThat(finalizer.finish(fixture.job, fixture.item, fixture.candidate, false)).isTrue();
        assertThat(jobs.selectById(fixture.job.getId()).getStatus()).isEqualTo("FAILED");
        assertThat(items.selectById(fixture.item.getId()).getStatus()).isEqualTo("VIDEO_FAILED");
        assertThat(assets.selectById(fixture.previous.getId()).getAssetType()).isEqualTo("VIDEO_RESULT");
        assertThat(assets.selectById(fixture.candidate.getId()).getAssetType()).isEqualTo("VIDEO_REVIEW_CANDIDATE");
    }

    @Test
    void zeroItemUpdateRollsBackSuccessfulCandidatePromotion() throws Exception {
        Fixture fixture = fixture();
        fixture.job.succeeded("output", BigDecimal.ONE, "{}");
        VideoReviewFinalizer missingItem = transactional(mock(AiVideoTaskItemMapper.class));
        assertThatThrownBy(() -> missingItem.finish(fixture.job, fixture.item, fixture.candidate, true))
                .isInstanceOf(IllegalStateException.class);
        assertUnpublished(fixture);
    }

    @Test
    void concurrentCallbacksCommitExactlyOnce() throws Exception {
        Fixture fixture = fixture();
        fixture.job.succeeded("output", BigDecimal.ONE, "{}");
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> finish = () -> {
                start.await(10, TimeUnit.SECONDS);
                return finalizer.finish(fixture.job, fixture.item, fixture.candidate, true);
            };
            var first = executor.submit(finish);
            var second = executor.submit(finish);
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jobs.selectById(fixture.job.getId()).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(assets.selectById(fixture.candidate.getId()).getAssetType()).isEqualTo("VIDEO_RESULT");
    }

    private VideoReviewFinalizer transactional(AiVideoTaskItemMapper itemMapper) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(new VideoReviewFinalizer(jobs, assets, itemMapper));
        factory.addAdvice(interceptor);
        return (VideoReviewFinalizer) factory.getProxy();
    }

    private void assertUnpublished(Fixture fixture) {
        assertThat(jobs.selectById(fixture.job.getId()).getStatus()).isEqualTo("REVIEWING");
        assertThat(items.selectById(fixture.item.getId()).getStatus()).isEqualTo("VIDEO_GENERATING");
        assertThat(assets.selectById(fixture.previous.getId()).getAssetType()).isEqualTo("VIDEO_RESULT");
        assertThat(assets.selectById(fixture.candidate.getId()).getAssetType()).isEqualTo("VIDEO_REVIEW_CANDIDATE");
    }

    private Fixture fixture() throws Exception {
        String key = UUID.randomUUID().toString();
        AiVideoTaskItem item = new AiVideoTaskItem("org", "task", "item", key, "source.png", "/source.png", "image/png", 10);
        item.videoRunning();
        items.insert(item);
        AiVideoGenerationJob job = new AiVideoGenerationJob(key, "org", "task", item.getId(), 1,
                "model-config", "provider", "model", 10, "9:16", "720P");
        job.accepted("provider-job", "RUNNING", null);
        job.awaitingReview("output", BigDecimal.ONE, "{}");
        jobs.insert(job);
        Path oldFile = Files.writeString(storage.resolve(key + "-old.mp4"), "previous video bytes");
        Path candidateFile = Files.writeString(storage.resolve(key + "-candidate.mp4"), "candidate video bytes");
        AiVideoTaskAsset previous = new AiVideoTaskAsset("org", "task", item.getId(), "VIDEO_RESULT", 1,
                oldFile.toString(), "video/mp4", Files.size(oldFile), "previous metadata", "model");
        AiVideoTaskAsset candidate = new AiVideoTaskAsset("org", "task", item.getId(), "VIDEO_REVIEW_CANDIDATE", null,
                candidateFile.toString(), "video/mp4", Files.size(candidateFile), "{\"jobId\":\"" + job.getId() + "\"}", "model");
        assets.insert(previous);
        assets.insert(candidate);
        return new Fixture(job, item, previous, candidate);
    }

    private record Fixture(AiVideoGenerationJob job, AiVideoTaskItem item,
                           AiVideoTaskAsset previous, AiVideoTaskAsset candidate) {}
}
