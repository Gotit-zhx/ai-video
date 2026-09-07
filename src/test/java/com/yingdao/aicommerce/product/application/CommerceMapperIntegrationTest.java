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
                    "V59__record_batch_cancellation.sql","V60__add_batch_review_evidence.sql"}){
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+file));
            }
        }
        var config=new Configuration(new Environment("integration",new JdbcTransactionFactory(),ds));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(CommerceTemplateMapper.class);config.addMapper(CommerceBatchMapper.class);
        config.addMapper(CommerceListingQuotaMapper.class);config.addMapper(CommerceBatchReviewMapper.class);
        try(var session=new SqlSessionFactoryBuilder().build(config).openSession()){
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
            session.rollback();
        }
    }
}
