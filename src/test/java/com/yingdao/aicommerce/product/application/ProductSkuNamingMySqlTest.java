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
        db.execute("DROP TABLE IF EXISTS product_sku_pattern_codes");
        db.execute(Files.readString(Path.of("src/main/resources/db/migration/V146__captured_pattern_shared_sku_codes.sql")));
    }
    List<Map<String,Object>> input() {
        return List.of(Map.of("externalSkuId","sku","attributes",Map.of("Color","Black","Size","M")));
    }
    String style(List<Map<String,Object>> rows) { return rows.getFirst().get("sellerSku").toString().split("-")[1]; }
    @Test void codesPersistAcrossServiceInstancesAndCompanyScopesStayIndependent() {
        var first=new ProductSkuNamingService(db,transactions,()->1058);
        var original=first.assign("one","product",input(),List.of());
        assertEquals("K58",style(original));
        var restarted=new ProductSkuNamingService(db,transactions,()->192);
        assertEquals(original,restarted.assign("one","product",input(),List.of()));
        assertEquals("B92",style(restarted.assign("two","product",input(),List.of())));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM product_sku_pattern_codes",Integer.class));
    }
    @Test void randomCodeCollisionRetriesWithoutChangingTheOriginalPattern() {
        var random=new AtomicInteger(1);
        var naming=new ProductSkuNamingService(db,transactions,random::get);
        assertEquals("A01",style(naming.assign("org","one",input(),List.of())));
        var collision=new ProductSkuNamingService(db,transactions,random::getAndIncrement);
        assertEquals("A02",style(collision.assign("org","two",input(),List.of())));
        assertEquals("A01",style(naming.assign("org","one",input(),List.of())));
    }
    @Test void concurrentCollectionAllocatesExactlyOneSharedStyle() throws Exception {
        var start=new CountDownLatch(1);var ready=new CountDownLatch(8);
        var futures=new ArrayList<Future<String>>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<8;i++) futures.add(pool.submit(()->{
                var service=new ProductSkuNamingService(db,transactions);ready.countDown();start.await();
                return service.assign("org","same-product",input(),List.of()).getFirst().get("sellerSku").toString();
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            var codes=new HashSet<String>();for(var future:futures)codes.add(future.get(20,TimeUnit.SECONDS));
            assertEquals(1,codes.size());
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM product_sku_pattern_codes",Integer.class));
    }

    @Test void fullShortCodeNamespaceFailsClearlyInsteadOfReusingAnotherPatternsCode() {
        var rows=new ArrayList<Object[]>();
        for(int i=0;i<2600;i++)rows.add(new Object[]{"org",String.format("%064d",i),
                (char)('A'+i/100)+String.format("%02d",i%100),String.format("%08X",i)});
        db.update("INSERT INTO product_sku_pattern_codes(organization_id,pattern_key,style_code,random_suffix) VALUES"
                +String.join(",",Collections.nCopies(rows.size(),"(?,?,?,?)")),
                rows.stream().flatMap(Arrays::stream).toArray());
        var naming=new ProductSkuNamingService(db,transactions);
        var error=assertThrows(IllegalStateException.class,()->naming.assign("org","new-product",input(),List.of()));
        assertTrue(error.getMessage().contains("已用满"));
        assertEquals(2600,db.queryForObject("SELECT COUNT(*) FROM product_sku_pattern_codes",Integer.class));
    }
}
