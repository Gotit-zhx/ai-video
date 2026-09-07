package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.*;
import com.yingdao.aicommerce.product.mapper.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: a fresh disposable database only; never the application database. */
@EnabledIfEnvironmentVariable(named="HEYI_MAPPER_TEST_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_mapper_test.*")
class CommerceMapperIntegrationTest {
    @Test void mysqlMigrationsAndRecordMappingsRoundTrip() throws Exception {
        var ds=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",System.getenv("HEYI_MAPPER_TEST_URL"),"root","");
        try(var connection=ds.getConnection()){
            try(var tables=connection.getMetaData().getTables("heyi_mapper_test",null,"%",new String[]{"TABLE"})){
                assertFalse(tables.next(),"Integration test requires a fresh empty database");
            }
            for(String file:new String[]{"V55__add_commerce_template_versions.sql","V56__add_commerce_batch_drafts.sql",
                    "V57__add_shop_operating_settings.sql","V58__add_commerce_listing_reservations.sql",
                    "V59__record_batch_cancellation.sql","V60__add_batch_review_evidence.sql",
                    "V61__add_commerce_design_drafts.sql","V62__add_design_generation_attempts.sql",
                    "V63__add_commerce_design_outputs.sql","V64__add_commerce_design_reviews.sql",
                    "V65__audit_design_generation_consent.sql"}){
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+file));
            }
        }
        var config=new Configuration(new Environment("integration",new JdbcTransactionFactory(),ds));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(CommerceTemplateMapper.class);config.addMapper(CommerceBatchMapper.class);
        config.addMapper(CommerceListingQuotaMapper.class);config.addMapper(CommerceBatchReviewMapper.class);
        config.addMapper(CommerceDesignMapper.class);config.addMapper(CommerceDesignAttemptMapper.class);
        config.addMapper(CommerceDesignOutputMapper.class);config.addMapper(CommerceDesignReviewMapper.class);config.addMapper(CommerceDesignConsentMapper.class);
        var factory=new SqlSessionFactoryBuilder().build(config);
        try(var session=factory.openSession()){
            var now=Instant.parse("2026-09-07T00:00:00Z");
            var template=session.getMapper(CommerceTemplateMapper.class);
            template.insert(new CommerceTemplateVersion("snap","org","template",1,"WORKFLOW","模板","{}","user",now));
            assertEquals("snap",template.snapshot("org","snap").id());
            assertNull(template.snapshot("other","snap"));
            var batches=session.getMapper(CommerceBatchMapper.class);
            batches.insert(new CommerceBatch("batch","org","批次","snap","key","hash","DRAFT","user",now));
            batches.insertItem(new CommerceBatchItem("item","org","batch","capture",4,"shop","店铺",1,"DRAFT",now));
            assertEquals(4,batches.items("org","batch").getFirst().captureVersion());
            assertEquals("snap",batches.lock("org","batch").templateSnapshotId());
            var quota=session.getMapper(CommerceListingQuotaMapper.class);var date=LocalDate.of(2026,9,8);
            quota.ensureDay("org","shop",date);assertEquals(1,quota.reserve("org","shop",date,1,1));
            assertEquals(0,quota.reserve("org","shop",date,1,1));
            quota.insert(new CommerceListingReservation("res","org","batch","item","shop",date,"Asia/Manila","user",now));
            var reservation=quota.list("org","batch").getFirst();
            assertEquals(date,reservation.listingDate());assertEquals("RESERVED",reservation.status());assertNull(reservation.releasedAt());
            assertEquals(1,quota.reservedCount("org","shop",date));
            var reviews=session.getMapper(CommerceBatchReviewMapper.class);
            reviews.insert(new CommerceBatchReview("review","org","batch","snap","[{\"itemId\":\"item\"}]","user",now));
            assertEquals(now,reviews.get("org","batch").reviewedAt());assertNull(reviews.get("other","batch"));
            assertEquals(1,quota.releaseDay("org","shop",date,1));assertEquals(0,quota.releaseDay("org","shop",date,1));
            assertEquals(1,quota.releaseBatch("org","batch","user"));assertTrue(quota.list("org","batch").isEmpty());
            var designs=session.getMapper(CommerceDesignMapper.class);
            designs.insert(new CommerceDesignDraft("design","org","capture",4,"design_request","hash",1,"STOCK_MARKETING","保留斜肩","授权记录","[]","DRAFT","user",now));
            assertEquals(4,designs.lock("org","design").captureVersion());assertEquals(1,designs.list("org","user").size());assertTrue(designs.list("org","other-user").isEmpty());assertNull(designs.get("foreign","design"));
            assertEquals(1,designs.byRequest("org","design_request").size());
            var attempts=session.getMapper(CommerceDesignAttemptMapper.class);
            attempts.insert(new CommerceDesignAttempt("attempt","org","design","PREPARED","[]",null,null,now,now));
            assertEquals("PREPARED",attempts.byDesign("org","design").status());assertNull(attempts.lock("foreign","attempt"));
            assertEquals(1,attempts.transition("org","attempt","PREPARED","SUBMITTING",null,null));
            assertEquals(0,attempts.transition("org","attempt","PREPARED","SUBMITTING",null,null));
            String savedResult="{\"result\":{\"url\":\"https://example.com/image.png\"}}";
            assertEquals(1,attempts.transition("org","attempt","SUBMITTING","RESULT_RECEIVED",savedResult,null));
            assertEquals(savedResult,attempts.get("org","attempt").resultJson());
            var outputs=session.getMapper(CommerceDesignOutputMapper.class);
            outputs.insert(new CommerceDesignOutput("output","org","design","attempt","key","a".repeat(64),"image/png",50,"PENDING_REVIEW",now));
            assertEquals(50,outputs.byAttempt("org","attempt").byteLength());assertEquals("output",outputs.byDesign("org","design").id());
            assertEquals("PENDING_REVIEW",outputs.lock("org","output").status());
            var designReviews=session.getMapper(CommerceDesignReviewMapper.class);
            designReviews.insert(new CommerceDesignReview("dr","org","output","a".repeat(64),"APPROVE","{\"singleImage\":true}","人工复核","user",now));
            assertEquals(now,designReviews.get("org","output").reviewedAt());assertNull(designReviews.get("foreign","output"));
            assertEquals(1,outputs.reviewed("org","output","QUALITY_APPROVED"));assertEquals(0,outputs.reviewed("org","output","REJECTED"));
            var consents=session.getMapper(CommerceDesignConsentMapper.class);
            consents.insert(new CommerceDesignConsent("consent","org","design",4,"[\"image\"]","model-connection","image-model","user",now));
            assertEquals(4,consents.get("org","design").captureVersion());assertEquals("user",consents.get("org","design").confirmedBy());assertNull(consents.get("foreign","design"));
            assertThrows(org.apache.ibatis.exceptions.PersistenceException.class,()->consents.insert(new CommerceDesignConsent("duplicate","org","design",4,"[]","m","model","user",now)));
            session.rollback();
        }
        try(var session=factory.openSession(true)){
            session.getMapper(CommerceDesignAttemptMapper.class).insert(new CommerceDesignAttempt("race","org","race-design","PREPARED","[]",null,null,Instant.now(),Instant.now()));
        }
        var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<Integer> claim=()->{
                try(var session=factory.openSession()){
                    ready.countDown();if(!start.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("race start timed out");
                    int changed=session.getMapper(CommerceDesignAttemptMapper.class).transition("org","race","PREPARED","SUBMITTING",null,null);session.commit();return changed;
                }
            };
            var first=executor.submit(claim);var second=executor.submit(claim);assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));start.countDown();
            assertEquals(1,first.get(10,java.util.concurrent.TimeUnit.SECONDS)+second.get(10,java.util.concurrent.TimeUnit.SECONDS),"Exactly one database claimant may submit to the model");
        }
    }
}
