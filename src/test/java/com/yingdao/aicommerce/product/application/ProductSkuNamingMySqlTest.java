package com.yingdao.aicommerce.product.application;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SKU_NAMING_TEST_DB",
        matches="jdbc:mysql://127\\.0\\.0\\.1:\\d+/sku_naming_test(\\?.*)?")
class ProductSkuNamingMySqlTest {
    JdbcTemplate db;
    DataSourceTransactionManager transactions;
    @BeforeEach void setup() throws Exception {
        var data=new DriverManagerDataSource(System.getenv("SKU_NAMING_TEST_DB"),
                System.getenv("SKU_NAMING_TEST_USER"),System.getenv("SKU_NAMING_TEST_PASSWORD"));
        db=new JdbcTemplate(data);transactions=new DataSourceTransactionManager(data);
        db.execute("DROP TABLE IF EXISTS product_sku_styles");
        db.execute(Files.readString(Path.of("src/main/resources/db/migration/V145__captured_merchant_sku_styles.sql")));
    }
    List<Map<String,Object>> input() {
        return List.of(Map.of("externalSkuId","sku","attributes",Map.of("Color","Black","Size","M")));
    }
    String style(List<Map<String,Object>> rows) { return rows.getFirst().get("sellerSku").toString().split("-")[1]; }
    @Test void codesPersistAcrossServiceInstancesAndCompanyScopesStayIndependent() {
        var first=new ProductSkuNamingService(db,transactions,()->10_583204);
        assertEquals("K583204",style(first.assign("one","product",input(),List.of())));
        var restarted=new ProductSkuNamingService(db,transactions,()->1_926715);
        assertEquals("K583204",style(restarted.assign("one","product",input(),List.of())));
        assertEquals("B926715",style(restarted.assign("two","product",input(),List.of())));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM product_sku_styles",Integer.class));
    }
    @Test void randomCodeCollisionRetriesWithoutChangingTheOriginalPattern() {
        var random=new AtomicInteger(123456);
        var naming=new ProductSkuNamingService(db,transactions,random::get);
        assertEquals("A123456",style(naming.assign("org","one",input(),List.of())));
        var collision=new ProductSkuNamingService(db,transactions,random::getAndIncrement);
        assertEquals("A123457",style(collision.assign("org","two",input(),List.of())));
        assertEquals("A123456",style(naming.assign("org","one",input(),List.of())));
    }
    @Test void concurrentCollectionAllocatesExactlyOneSharedStyle() throws Exception {
        var start=new CountDownLatch(1);var ready=new CountDownLatch(8);
        var futures=new ArrayList<Future<String>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<8;i++) futures.add(pool.submit(()->{
                var service=new ProductSkuNamingService(db,transactions);ready.countDown();start.await();
                return style(service.assign("org","same-product",input(),List.of()));
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            var codes=new HashSet<String>();for(var future:futures)codes.add(future.get(20,TimeUnit.SECONDS));
            assertEquals(1,codes.size());
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM product_sku_styles",Integer.class));
    }
}
