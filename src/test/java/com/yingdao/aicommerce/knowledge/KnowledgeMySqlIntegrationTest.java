package com.yingdao.aicommerce.knowledge;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: only a fresh, disposable local MySQL schema. Runs the real migrations and Spring transactions. */
@EnabledIfEnvironmentVariable(named="HEYI_KNOWLEDGE_TEST_URL",
        matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_knowledge_test(?:\\?.*)?")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeMySqlIntegrationTest {
    private JdbcTemplate db;
    private KnowledgeService service;

    @BeforeAll void initializeDisposableDatabase() throws Exception {
        var dataSource=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",System.getenv("HEYI_KNOWLEDGE_TEST_URL"),System.getenv().getOrDefault("HEYI_TEST_DB_USER","root"),System.getenv().getOrDefault("HEYI_TEST_DB_PASSWORD",""));
        try(var connection=dataSource.getConnection()) {
            try(var tables=connection.getMetaData().getTables(connection.getCatalog(),null,"%",new String[]{"TABLE"})) {
                assertFalse(tables.next(),"Refusing to use a populated database; create a fresh heyi_knowledge_test schema");
            }
            for(String migration:List.of("V1__initialize_platform_schema.sql","V2__add_saas_identity_and_organization.sql","V3__complete_rbac_model.sql"))
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+migration));
            try(var statement=connection.createStatement()) {
                statement.execute("INSERT INTO organizations(id,code,name) VALUES('migration-org','MIGRATION','Migration tenant')");
                for(String role:List.of("ADMIN","PLATFORM_ADMIN","OPERATOR","CUSTOM_READ_ONLY"))
                    statement.execute("INSERT INTO roles(id,organization_id,code,name) VALUES('"+role+"','migration-org','"+role+"','"+role+"')");
            }
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V104__knowledge_library.sql"));
        }
        db=new JdbcTemplate(dataSource);
        var interceptor=new TransactionInterceptor();
        interceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy=new ProxyFactory(new KnowledgeService(db,new ObjectMapper()));proxy.addAdvice(interceptor);
        service=(KnowledgeService)proxy.getProxy();
    }

    @Test void migrationGrantsReadersViewAndOnlyAdminsEditAndPublish() {
        for(String role:List.of("ADMIN","PLATFORM_ADMIN","OPERATOR","CUSTOM_READ_ONLY")) {
            var actual=db.queryForList("SELECT p.code FROM role_permissions rp JOIN permissions p ON p.id=rp.permission_id WHERE rp.role_id=? AND p.module='KNOWLEDGE' ORDER BY p.code",String.class,role);
            assertEquals(role.contains("ADMIN")?List.of("knowledge:edit","knowledge:publish","knowledge:view"):List.of("knowledge:view"),actual);
        }
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM permissions WHERE module='KNOWLEDGE'",Integer.class));
    }

    @Test void savesDraftThenPublishesImmutableVersionsAndSearchableCurrentChunks() {
        var admin=tenant();var reader=as(admin,"knowledge:view");
        var first=service.save(null,body("DRAFT","Original draft content"),admin);String id=(String)first.get("id");
        assertEquals(1,first.get("version"));assertEquals(0,chunkCount(id));
        assertEquals(1L,service.list("","","","",1,reader).get("total"));
        assertEquals("DRAFT",service.get(id,reader).get("status"));
        var publish=edit(first,"PUBLISHED","New published content searchtoken");
        var second=service.save(id,publish,admin);
        assertEquals(2,second.get("version"));assertEquals(1,chunkCount(id));
        assertEquals("New published content searchtoken",service.get(id,reader).get("body"));
        var history=service.versions(id,admin);assertEquals(2,history.size());
        assertEquals("Original draft content",((Map<?,?>)history.get(1).get("article")).get("body"));
        var found=items(service.retrieve("searchtoken","en",reader));assertEquals(1,found.size());
        assertEquals("knowledge:"+id+":v2:0",found.getFirst().get("citation"));
        assertEquals(false,service.retrieve("searchtoken","en",reader).get("vectorIndexConnected"));
        assertEquals(false,service.list("shop","","","",1,admin).get("libraryEmpty"),"An empty category is not an empty library");
    }

    @Test void everyReadWriteHistoryAndRetrievalPathIsTenantScoped() {
        var firstTenant=tenant();var other=tenant();
        var article=service.save(null,body("PUBLISHED","Private tenant marker"),firstTenant);String id=(String)article.get("id");
        assertEquals(0L,service.list("","","","",1,other).get("total"));
        assertTrue(items(service.retrieve("Private tenant marker","",other)).isEmpty());
        status(HttpStatus.NOT_FOUND,()->service.get(id,other));
        status(HttpStatus.NOT_FOUND,()->service.versions(id,other));
        status(HttpStatus.NOT_FOUND,()->service.save(id,edit(article,"PUBLISHED","Injected content"),other));
        assertEquals("Private tenant marker",service.get(id,firstTenant).get("body"));
    }

    @Test void grantedMembersCreateSharedArticlesAndEditWithoutHiddenMetadataOrStateControls() {
        var admin=tenant();var member=asWriter(admin);
        var input=new HashMap<String,Object>(Map.of("category","system","title","New shared guide","body","searchable newmembermarker"));
        var created=service.save(null,input,member);String id=(String)created.get("id");
        assertEquals("PUBLISHED",created.get("status"));assertEquals("zh",created.get("language"));assertEquals("ALL",created.get("platform"));
        assertEquals(true,service.list("","","","",1,member).get("canEdit"));assertEquals(false,service.list("","","","",1,member).get("canAudit"));
        assertEquals(1,items(service.retrieve("newmembermarker","",member)).size());
        status(HttpStatus.FORBIDDEN,()->service.versions(id,member));
        status(HttpStatus.FORBIDDEN,()->service.save(id,edit(created,"DRAFT","Status bypass attempt"),member));
        var edited=service.save(id,Map.of("version",1,"title","Edited guide","body","Member edits published content"),member);
        assertEquals(2,edited.get("version"));assertEquals("PUBLISHED",edited.get("status"));assertEquals("zh",edited.get("language"));
        assertEquals(2,service.versions(id,admin).size());assertFalse(edited.containsKey("actor"));assertFalse(edited.containsKey("_audit"));
    }
    @Test void defaultMembersCanReadButOnlyAdministratorCanGrantAndRevokeWriting() {
        var admin=tenant();var member=as(admin);var other=tenant();
        var article=service.save(null,body("PUBLISHED","Readable content"),admin);String id=(String)article.get("id");
        assertEquals("Readable content",service.get(id,member).get("body"));
        assertEquals(false,service.list("","","","",1,member).get("canEdit"));
        var created=service.save(null,Map.of("category","system","title","New member article"),member);
        assertEquals("PUBLISHED",created.get("status"));
        status(HttpStatus.FORBIDDEN,()->service.save(id,Map.of("version",1,"title","No edit"),member));
        status(HttpStatus.FORBIDDEN,()->service.delete(id,Map.of("version",1),member));
        status(HttpStatus.FORBIDDEN,()->service.setWriterAccess(member.userId(),Map.of("canWrite",true),member));
        status(HttpStatus.NOT_FOUND,()->service.setWriterAccess(other.userId(),Map.of("canWrite",true),admin));
        assertEquals(false,service.writerAccess(admin).get(member.userId()));
        service.setWriterAccess(member.userId(),Map.of("canWrite",true),admin);
        assertEquals(true,service.list("","","","",1,member).get("canEdit"));
        assertEquals(true,service.writerAccess(admin).get(member.userId()));
        service.setWriterAccess(member.userId(),Map.of("canWrite",false),admin);
        assertEquals(false,service.list("","","","",1,member).get("canEdit"));
        status(HttpStatus.FORBIDDEN,()->service.delete(id,Map.of("version",1),member));
        status(HttpStatus.CONFLICT,()->service.setWriterAccess(admin.userId(),Map.of("canWrite",false),admin));
        assertEquals("PUBLISHED",service.get(id,member).get("status"));
    }

    @Test void staleVersionConflictsWithoutChangingArticleHistoryOrChunks() {
        var admin=tenant();var original=service.save(null,body("PUBLISHED","Stable searchtoken"),admin);String id=(String)original.get("id");
        service.save(id,edit(original,"PUBLISHED","Latest searchtoken"),admin);
        var chunksBefore=db.queryForList("SELECT * FROM knowledge_article_chunks WHERE article_id=?",id);
        status(HttpStatus.CONFLICT,()->service.save(id,edit(original,"ARCHIVED","Stale update"),admin));
        assertEquals("Latest searchtoken",service.get(id,admin).get("body"));
        assertEquals(2,service.versions(id,admin).size());
        assertEquals(chunksBefore,db.queryForList("SELECT * FROM knowledge_article_chunks WHERE article_id=?",id));
    }

    @Test void failureAfterArticleWriteRollsBackArticleHistoryAndChunks() {
        var admin=tenant();var original=service.save(null,body("PUBLISHED","Kept content"),admin);String id=(String)original.get("id");
        // Reserve version 2 to force the real immutable-snapshot INSERT to fail after the UPDATE.
        db.update("INSERT INTO knowledge_article_versions(article_id,organization_id,version,snapshot_json,content_sha256,author_id) VALUES(?,?,2,'{}',?,?)",id,admin.organizationId(),"0".repeat(64),admin.userId());
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->service.save(id,edit(original,"PUBLISHED","Must roll back"),admin));
        var result=service.get(id,admin);assertEquals(1,result.get("version"));assertEquals("Kept content",result.get("body"));
        assertEquals("Kept content",db.queryForObject("SELECT content FROM knowledge_article_chunks WHERE article_id=?",String.class,id));
        assertEquals(2,service.versions(id,admin).size());
    }

    @Test void concurrentEditorsUsingSameVersionCommitExactlyOnce() throws Exception {
        var admin=tenant();var original=service.save(null,body("PUBLISHED","Concurrent original"),admin);String id=(String)original.get("id");
        var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> update=()->{barrier.await(10,TimeUnit.SECONDS);try{service.save(id,edit(original,"PUBLISHED","Concurrent winner"),admin);return true;}catch(BusinessException e){assertEquals(HttpStatus.CONFLICT,e.getStatus());return false;}};
            var a=executor.submit(update);var b=executor.submit(update);
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(2,service.get(id,admin).get("version"));assertEquals(2,service.versions(id,admin).size());assertEquals(1,chunkCount(id));
    }

    @Test void disablingRetrievalAndArchivingRemoveChunksAndSearchResults() {
        var admin=tenant();var reader=as(admin,"knowledge:view");
        var article=service.save(null,body("PUBLISHED","retrievalmarker"),admin);String id=(String)article.get("id");
        var disabled=edit(article,"PUBLISHED","retrievalmarker");disabled.put("retrievalEnabled",false);
        article=service.save(id,disabled,admin);assertEquals(0,chunkCount(id));assertTrue(items(service.retrieve("retrievalmarker","",reader)).isEmpty());
        var enabled=edit(article,"PUBLISHED","retrievalmarker");enabled.put("retrievalEnabled",true);
        article=service.save(id,enabled,admin);assertEquals(1,chunkCount(id));
        service.save(id,edit(article,"ARCHIVED","retrievalmarker"),admin);
        assertEquals(0,chunkCount(id));assertTrue(items(service.retrieve("retrievalmarker","",reader)).isEmpty());
        assertEquals("ARCHIVED",service.get(id,reader).get("status"));assertEquals(1L,service.list("","","ARCHIVED","",1,reader).get("total"));
        assertEquals(4,service.versions(id,admin).size());
    }

    @Test void deletingArticleHidesItAndRetainsAuditedVersions() {
        var admin=tenant();var member=asWriter(admin);
        var article=service.save(null,body("PUBLISHED","Delete marker searchable"),member);
        String id=(String)article.get("id");
        assertEquals(1,chunkCount(id));
        status(HttpStatus.NOT_FOUND,()->service.delete(id,Map.of("version",1),tenant()));
        status(HttpStatus.BAD_REQUEST,()->service.delete(id,Map.of("version",1,"body","Injected"),member));
        status(HttpStatus.CONFLICT,()->service.delete(id,Map.of("version",2),member));
        assertEquals("PUBLISHED",service.get(id,admin).get("status"));
        var deleted=service.delete(id,Map.of("version",1),member);
        assertEquals("ARCHIVED",deleted.get("status"));
        assertEquals(2,deleted.get("version"));
        assertEquals(0,chunkCount(id));
        assertTrue(items(service.retrieve("Delete marker searchable","",member)).isEmpty());
        assertTrue(items(service.list("","","","",1,member)).stream().noneMatch(item->id.equals(item.get("id"))));
        var history=service.versions(id,admin);
        assertEquals(List.of("DELETE","CREATE"),history.stream().map(row->row.get("action")).toList());
        assertEquals(member.userId(),((Map<?,?>)history.getFirst().get("actor")).get("userId"));
        status(HttpStatus.CONFLICT,()->service.delete(id,Map.of("version",1),member));
    }

    @Test void publisherWithoutEditCanPublishArchiveAndWithdrawWithoutChangingContent() {
        var admin=tenant();var publisher=as(admin,"knowledge:publish");var reader=as(admin,"knowledge:view");
        var original=service.save(null,body("DRAFT","Publisher must preserve this body"),admin);String id=(String)original.get("id");
        var published=service.changeStatus(id,Map.of("status","PUBLISHED","version",1),publisher);
        assertEquals(2,published.get("version"));assertEquals(original.get("body"),published.get("body"));assertEquals(1,chunkCount(id));
        assertEquals(original.get("body"),service.get(id,reader).get("body"));
        var archived=service.changeStatus(id,Map.of("status","ARCHIVED","version",2),publisher);
        assertEquals(3,archived.get("version"));assertEquals(0,chunkCount(id));
        var draft=service.changeStatus(id,Map.of("status","DRAFT","version",3),publisher);
        assertEquals(4,draft.get("version"));assertEquals(original.get("body"),draft.get("body"));
        assertEquals(4,service.versions(id,admin).size());status(HttpStatus.FORBIDDEN,()->service.versions(id,publisher));assertEquals("DRAFT",service.get(id,reader).get("status"));
    }

    @Test void statusEndpointRejectsContentInjectionWrongTenantAndStaleVersions() {
        var admin=tenant();var publisher=as(admin,"knowledge:publish");var original=service.save(null,body("DRAFT","Unchanged"),admin);String id=(String)original.get("id");
        status(HttpStatus.FORBIDDEN,()->service.changeStatus(id,Map.of("status","PUBLISHED","version",1),as(admin,"knowledge:edit")));
        status(HttpStatus.BAD_REQUEST,()->service.changeStatus(id,Map.of("status","PUBLISHED","version",1,"body","Injected"),publisher));
        status(HttpStatus.NOT_FOUND,()->service.changeStatus(id,Map.of("status","PUBLISHED","version",1),tenant()));
        status(HttpStatus.CONFLICT,()->service.changeStatus(id,Map.of("status","PUBLISHED","version",9),publisher));
        assertEquals(1,service.get(id,admin).get("version"));assertEquals(1,service.versions(id,admin).size());assertEquals(0,chunkCount(id));
    }

    @Test void auditCapturesDatabaseIdentityVersionsFieldDiffAndNeverLeaksToNormalViews() {
        var admin=tenant();var member=asWriter(admin);
        var article=service.save(null,body("PUBLISHED","First body"),member);String id=(String)article.get("id");
        db.update("UPDATE app_users SET display_name='Renamed Member',username='new-handle' WHERE id=?",member.userId());
        var update=edit(article,"PUBLISHED","Second body");update.put("title","Updated title");update.put("_audit",Map.of("userId",admin.userId(),"displayName","Spoofed"));
        var changed=service.save(id,update,member);
        service.changeStatus(id,Map.of("status","ARCHIVED","version",changed.get("version")),admin);
        var history=service.versions(id,admin);assertEquals(List.of("STATUS_CHANGE","EDIT","CREATE"),history.stream().map(r->r.get("action")).toList());
        assertEquals(1,history.get(1).get("fromVersion"));assertEquals(2,history.get(1).get("toVersion"));
        var actor=(Map<?,?>)history.get(1).get("actor");assertEquals(member.userId(),actor.get("userId"));assertEquals("Renamed Member",actor.get("displayName"));assertEquals("new-handle",actor.get("username"));assertEquals("SNAPSHOT",actor.get("identitySource"));
        assertEquals("Team Member",((Map<?,?>)history.get(2).get("actor")).get("displayName"));
        var diff=(List<Map<String,Object>>)history.get(1).get("changes");
        assertEquals(Set.of("title","body"),new HashSet<>(diff.stream().map(c->c.get("field").toString()).toList()));
        assertEquals("First body",diff.stream().filter(c->c.get("field").equals("body")).findFirst().orElseThrow().get("before"));
        assertTrue(history.getFirst().get("createdAt").toString().endsWith("Z"));
        assertFalse(service.get(id,member).containsKey("actor"));assertFalse(items(service.list("","","ARCHIVED","",1,member)).getFirst().containsKey("_audit"));
        db.update("DELETE FROM app_users WHERE id=?",member.userId());
        var deleted=service.versions(id,admin);assertEquals("Renamed Member",((Map<?,?>)deleted.get(1).get("actor")).get("displayName"));assertEquals(member.userId(),((Map<?,?>)deleted.get(1).get("actor")).get("userId"));
    }

    @Test void legacyAuditIdentityHasExplicitFallbackAndNeverJoinsAnotherTenantName() {
        var admin=tenant();var member=as(admin);var other=tenant();
        service.setWriterAccess(member.userId(),Map.of("canWrite",true),admin);
        var article=service.save(null,body("PUBLISHED","Legacy content"),member);String id=(String)article.get("id");
        db.update("UPDATE knowledge_article_versions SET snapshot_json=JSON_REMOVE(snapshot_json,'$._audit') WHERE article_id=?",id);
        var actor=(Map<?,?>)service.versions(id,admin).getFirst().get("actor");assertEquals("CURRENT_PROFILE",actor.get("identitySource"));assertEquals("Team Member",actor.get("displayName"));
        db.update("DELETE FROM app_users WHERE id=?",member.userId());
        actor=(Map<?,?>)service.versions(id,admin).getFirst().get("actor");assertEquals("ID_ONLY",actor.get("identitySource"));assertEquals(member.userId(),actor.get("userId"));
        db.update("UPDATE knowledge_article_versions SET author_id=? WHERE article_id=?",other.userId(),id);
        actor=(Map<?,?>)service.versions(id,admin).getFirst().get("actor");assertEquals("",actor.get("displayName"));assertEquals("",actor.get("username"));assertEquals("ID_ONLY",actor.get("identitySource"));
    }

    @Test void currentMembershipAndAdminMappingsOverrideStaleSessionFlags() {
        var admin=tenant();var member=asWriter(admin);var article=service.save(null,body("PUBLISHED","Protected history"),member);String id=(String)article.get("id");
        db.update("UPDATE app_users SET status='DISABLED' WHERE id=?",member.userId());
        status(HttpStatus.FORBIDDEN,()->service.get(id,member));status(HttpStatus.FORBIDDEN,()->service.save(id,edit(article,"DRAFT","Disabled write"),member));
        assertEquals(1,service.versions(id,admin).size(),"Disabled historical author must remain visible");
        db.update("DELETE FROM user_roles WHERE user_id=?",admin.userId());
        status(HttpStatus.FORBIDDEN,()->service.versions(id,admin));assertEquals(false,service.list("","","","",1,admin).get("canAudit"));
        db.update("UPDATE organizations SET status='DISABLED' WHERE id=?",admin.organizationId());
        status(HttpStatus.FORBIDDEN,()->service.list("","","","",1,admin));
    }

    @Test void archiveEditsKeepStateAndLogExactlyTheChangedFields() {
        var admin=tenant();var member=asWriter(admin);var article=service.save(null,body("ARCHIVED","Archived body"),admin);String id=(String)article.get("id");
        var body=edit(article,"ARCHIVED","Member correction");body.remove("status");var edited=service.save(id,body,member);
        assertEquals("ARCHIVED",edited.get("status"));assertEquals(0,chunkCount(id));
        var log=service.versions(id,admin).getFirst();assertEquals("EDIT",log.get("action"));assertEquals(List.of("body"),((List<Map<String,Object>>)log.get("changes")).stream().map(c->c.get("field")).toList());
        var statusOnly=edit(edited,"DRAFT","Member correction");service.save(id,statusOnly,admin);
        assertEquals("STATUS_CHANGE",service.versions(id,admin).getFirst().get("action"));
    }

    @Test void hiddenMetadataIsPreservedAndEmptyBodySupportsRealFileArticles() {
        var admin=tenant();var member=asWriter(admin);var input=body("PUBLISHED","");input.put("language","ms");input.put("platform","SHOPEE");input.put("market","MY");input.put("summary","Preserved legacy summary");
        var article=service.save(null,input,member);String id=(String)article.get("id");
        var saved=service.save(id,Map.of("version",1,"title","Real uploaded document","tags",List.of("document")),member);
        assertEquals("",saved.get("body"));assertEquals("ms",saved.get("language"));assertEquals("SHOPEE",saved.get("platform"));assertEquals("MY",saved.get("market"));assertEquals("Preserved legacy summary",saved.get("summary"));
        service.changeStatus(id,Map.of("status","ARCHIVED","version",2),admin);
        assertEquals(0L,service.list("","","","",1,member).get("total"));assertEquals(1L,service.list("","","ARCHIVED","",1,member).get("total"));
    }

    @Test void revokedPublishPermissionCannotBeRecoveredFromCachedPrincipal() {
        var admin=tenant();var publisher=as(admin,"knowledge:publish");var article=service.save(null,body("PUBLISHED","Shared body"),admin);String id=(String)article.get("id");
        db.update("DELETE FROM user_roles WHERE user_id=?",publisher.userId());
        status(HttpStatus.FORBIDDEN,()->service.changeStatus(id,Map.of("status","ARCHIVED","version",1),publisher));
        assertEquals("PUBLISHED",service.get(id,publisher).get("status"));
    }

    private int chunkCount(String id){return db.queryForObject("SELECT COUNT(*) FROM knowledge_article_chunks WHERE article_id=?",Integer.class,id);}
    private PlatformPrincipal tenant(){
        String id=UUID.randomUUID().toString(),userId=UUID.randomUUID().toString(),roleId=UUID.randomUUID().toString();
        db.update("INSERT INTO organizations(id,code,name) VALUES(?,?,?)",id,id,"Knowledge test tenant");
        db.update("INSERT INTO app_users(id,organization_id,username,display_name) VALUES(?,?,?,?)",userId,id,"admin","Admin");
        db.update("INSERT INTO roles(id,organization_id,code,name) VALUES(?,?,'ADMIN','Admin')",roleId,id);
        db.update("INSERT INTO user_roles(user_id,role_id) VALUES(?,?)",userId,roleId);
        return new PlatformPrincipal(userId,id,id,"admin","Admin","",true,List.of("ADMIN"),List.of());
    }
    private PlatformPrincipal as(PlatformPrincipal base,String... permissions){
        String userId=UUID.randomUUID().toString(),username="member-"+userId;
        db.update("INSERT INTO app_users(id,organization_id,username,display_name) VALUES(?,?,?,?)",userId,base.organizationId(),username,"Team Member");
        if(permissions.length>0) {
            String roleId=UUID.randomUUID().toString();db.update("INSERT INTO roles(id,organization_id,code,name) VALUES(?,?,?,'Custom member')",roleId,base.organizationId(),"custom-"+roleId);
            db.update("INSERT INTO user_roles(user_id,role_id) VALUES(?,?)",userId,roleId);
            for(String permission:permissions)db.update("INSERT INTO role_permissions(role_id,permission_id) SELECT ?,id FROM permissions WHERE code=?",roleId,permission);
        }
        return new PlatformPrincipal(userId,base.organizationId(),base.organizationCode(),username,"Team Member","",true,List.of(),List.of(permissions));
    }
    private PlatformPrincipal asWriter(PlatformPrincipal admin) {
        var member=as(admin);
        service.setWriterAccess(member.userId(),Map.of("canWrite",true),admin);
        return member;
    }
    private Map<String,Object> body(String status,String text){var result=new HashMap<String,Object>();result.putAll(Map.of("category","system","title","Test article","body",text,"language","en","platform","ALL","market","ALL","status",status,"tags",List.of("guide"),"retrievalEnabled",true));return result;}
    private Map<String,Object> edit(Map<String,Object> previous,String status,String text){var result=new HashMap<>(previous);result.put("status",status);result.put("body",text);return result;}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(Map<String,Object> result){return (List<Map<String,Object>>)result.get("items");}
    private void status(HttpStatus status,org.junit.jupiter.api.function.Executable action){assertEquals(status,assertThrows(BusinessException.class,action).getStatus());}
}
