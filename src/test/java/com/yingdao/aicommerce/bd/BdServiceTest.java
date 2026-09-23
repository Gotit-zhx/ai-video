package com.yingdao.aicommerce.bd;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BD_TEST_DB",matches="true")
class BdServiceTest {
 BdService s;JdbcTemplate db;TransactionTemplate tx;
 PlatformPrincipal a=p("a","one"),b=p("b","one"),outsider=p("c","two");
 static PlatformPrincipal p(String id,String org){return new PlatformPrincipal(id,org,org,id,id,"",true,List.of("MEMBER"),List.of());}
 @BeforeEach void setup() throws Exception {var ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/bd_test","root","");db=new JdbcTemplate(ds);db.execute("DROP TABLE IF EXISTS bd_followups");db.execute("DROP TABLE IF EXISTS bd_records");db.execute("DROP TABLE IF EXISTS app_users");db.execute("CREATE TABLE app_users(id VARCHAR(36),organization_id VARCHAR(36),display_name VARCHAR(120),status VARCHAR(20))");db.update("INSERT INTO app_users VALUES ('a','one','A','ACTIVE'),('b','one','B','ACTIVE'),('c','two','C','ACTIVE')");try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V103__bd_outreach.sql"));}s=new BdService(db,new ObjectMapper());tx=new TransactionTemplate(new DataSourceTransactionManager(ds));}
 Map<String,Object> create(){return tx.execute(x->s.save("influencers",null,Map.of("name","Creator","platform","TikTok","account","@alice"),a));}
 @Test void companyIsolationAndOwnerEnforced(){var r=create();assertEquals(1,s.list("influencers",false,b).size());assertFalse((boolean)s.get(r.get("id").toString(),b).get("editable"));assertTrue(s.list("influencers",false,outsider).isEmpty());assertThrows(BusinessException.class,()->s.get(r.get("id").toString(),outsider));assertThrows(BusinessException.class,()->s.deleted(r.get("id").toString(),true,b));}
 @Test void duplicatesAndStaleEditsRejected(){var r=create();assertThrows(BusinessException.class,()->s.save("influencers",null,Map.of("name","Duplicate","platform","tiktok","account","alice"),a));String id=r.get("id").toString();r.put("name","Updated");s.save("influencers",id,r,a);assertThrows(BusinessException.class,()->s.save("influencers",id,r,a));}
 @Test void followsLocksAndRecycleAreDurable(){var r=create();String id=r.get("id").toString();tx.executeWithoutResult(x->s.followup(id,Map.of("summary","Replied","channel","邮件","nextDate","2026-10-01"),a));assertEquals(1,s.followups(id,b).size());assertEquals("2026-10-01",s.get(id,a).get("nextDate"));tx.executeWithoutResult(x->s.lock(id,Map.of("until","2099-01-01","reason","Campaign"),a));assertThrows(BusinessException.class,()->s.lock(id,Map.of("until","2099-01-01"),b));s.deleted(id,true,a);assertTrue(s.list("influencers",false,a).isEmpty());s.deleted(id,false,a);assertEquals(1,s.list("influencers",false,a).size());}
 @Test void relationsAndAtomicImport(){var r=create();String id=r.get("id").toString();var c=s.save("collaborations",null,Map.of("name","Campaign","influencer_id",id,"currency","USD","gmv","150","fee","50"),a);assertNotEquals(id,c.get("id"));assertThrows(BusinessException.class,()->s.deleted(id,true,a));assertThrows(BusinessException.class,()->s.save("collaborations",null,Map.of("name","Wrong tenant","influencer_id",id),outsider));assertThrows(BusinessException.class,()->tx.execute(x->s.importRows("influencers",List.of(Map.of("name","New","platform","TikTok","account","new"),Map.of("name","Duplicate","platform","TikTok","account","alice")),a)));assertEquals(1,s.list("influencers",false,a).size());}
 @Test void unsafeValuesRejected(){assertThrows(BusinessException.class,()->s.save("influencers",null,Map.of("name","Bad","platform","TikTok","account","x","homepage","javascript:alert(1)"),a));assertThrows(BusinessException.class,()->s.save("influencers",null,Map.of("name","Bad","platform","TikTok","account","x","followers","-1"),a));}
}
