package com.yingdao.aicommerce.knowledge;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.storage.OssStorageService;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real MySQL transactions against a fresh, explicitly named disposable local schema; OSS is in-memory. */
@EnabledIfEnvironmentVariable(named="HEYI_KNOWLEDGE_FILE_TEST_URL",
        matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_knowledge_file_test(?:\\?.*)?")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeFileMySqlIntegrationTest {
    private JdbcTemplate db;
    private KnowledgeService knowledge;
    private KnowledgeFileService files;
    private final OssStorageService oss=mock(OssStorageService.class);
    private final Map<String,byte[]> objects=new ConcurrentHashMap<>();
    @BeforeAll void setup() throws Exception {
        var source=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",System.getenv("HEYI_KNOWLEDGE_FILE_TEST_URL"),System.getenv().getOrDefault("HEYI_TEST_DB_USER","root"),System.getenv().getOrDefault("HEYI_TEST_DB_PASSWORD",""));
        try(var connection=source.getConnection()) {
            try(var existing=connection.getMetaData().getTables(connection.getCatalog(),null,"%",new String[]{"TABLE"})) {
                assertFalse(existing.next(),"Refusing a populated schema: create fresh heyi_knowledge_file_test");
            }
            for(String migration:List.of("V1__initialize_platform_schema.sql","V2__add_saas_identity_and_organization.sql","V3__complete_rbac_model.sql","V104__knowledge_library.sql","V105__knowledge_file_attachments.sql"))
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+migration));
        }
        db=new JdbcTemplate(source);
        var advice=new TransactionInterceptor();advice.setTransactionManager(new DataSourceTransactionManager(source));advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var knowledgeProxy=new ProxyFactory(new KnowledgeService(db,new ObjectMapper()));knowledgeProxy.addAdvice(advice);knowledge=(KnowledgeService)knowledgeProxy.getProxy();
        @SuppressWarnings("unchecked") ObjectProvider<OssStorageService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(oss);
        var fileProxy=new ProxyFactory(new KnowledgeFileService(db,knowledge,new ObjectMapper(),provider,"dayan-test",mock(KnowledgeFilePreviewService.class)));fileProxy.addAdvice(advice);files=(KnowledgeFileService)fileProxy.getProxy();
    }
    @BeforeEach void storage() throws Exception {
        reset(oss);
        when(oss.store(any(byte[].class),anyString(),anyString(),anyString())).thenAnswer(call->{byte[] data=call.getArgument(0);String location="oss://dayan-test/heyi/"+call.getArgument(2)+"/"+KnowledgeFileService.digest(data)+"/"+call.getArgument(3);objects.put(location,data.clone());return new OssStorageService.StoredObject(location,data.length);});
        when(oss.isOssLocation(anyString())).thenAnswer(call->call.getArgument(0,String.class).startsWith("oss://dayan-test/"));
        when(oss.exists(anyString())).thenAnswer(call->objects.containsKey(call.getArgument(0,String.class)));
        when(oss.download(anyString())).thenAnswer(call->{byte[] data=objects.get(call.getArgument(0,String.class));if(data==null)throw new IllegalStateException("No such object");return new OssStorageService.Download(new ByteArrayResource(data),data.length,"application/octet-stream");});
    }
    @Test void standaloneUploadStoresOnlyOriginalInOssAndPersistsBoundedDerivedText() {
        var member=tenant(false);String id=upload(member,"manual.txt","团队真实操作指南");var listed=items(files.list(null,"system",1,"",member));
        assertEquals(1,listed.size());assertNull(listed.getFirst().get("articleId"));assertEquals("EXTRACTED",listed.getFirst().get("extractionStatus"));
        assertFalse(listed.getFirst().containsKey("actor"));assertFalse(listed.getFirst().containsKey("uploadedBy"));assertFalse(listed.getFirst().containsKey("oss_location"));
        assertFalse(listed.getFirst().containsKey("previewUrl"));
        assertEquals("团队真实操作指南",db.queryForObject("SELECT extracted_text FROM knowledge_files WHERE id=?",String.class,id));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_articles WHERE organization_id=?",Integer.class,member.organizationId()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM knowledge_file_events WHERE file_id=?",Integer.class,id));
        assertTrue(db.queryForObject("SELECT oss_location FROM knowledge_files WHERE id=?",String.class,id).startsWith("oss://dayan-test/"));
    }
    @Test void duplicateRequestIsReplayedWithoutReuploadAndDifferentPayloadConflicts() throws Exception {
        var member=tenant(true);String request=UUID.randomUUID().toString();var first=files.upload(part("guide.txt","original"),request,null,"system",member);
        var second=files.upload(part("guide.txt","original"),request,null,"system",member);assertEquals(first.get("file"),second.get("file"));assertEquals(true,second.get("replayed"));
        status(HttpStatus.CONFLICT,()->files.upload(part("guide.txt","changed"),request,null,"system",member));
        verify(oss,times(1)).store(any(byte[].class),anyString(),anyString(),anyString());
        String id=id(first);files.remove(id,1,member);
        status(HttpStatus.CONFLICT,()->files.upload(part("guide.txt","original"),request,null,"system",member));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM knowledge_file_events WHERE file_id=?",Integer.class,id));verify(oss,never()).delete(anyString());
    }
    @Test void concurrentSameRequestCommitsOneObjectOneFileOneUploadEvent() throws Exception {
        var member=tenant(false);String request=UUID.randomUUID().toString();var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Map<String,Object>> upload=()->{barrier.await(5,TimeUnit.SECONDS);return files.upload(part("same.txt","same body"),request,null,"system",member);};
            var a=executor.submit(upload);var b=executor.submit(upload);var first=a.get(15,TimeUnit.SECONDS);var second=b.get(15,TimeUnit.SECONDS);assertEquals(id(first),id(second));assertNotEquals(first.get("replayed"),second.get("replayed"));
        }
        verify(oss,times(1)).store(any(byte[].class),anyString(),anyString(),anyString());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM knowledge_files WHERE organization_id=?",Integer.class,member.organizationId()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM knowledge_file_events WHERE organization_id=?",Integer.class,member.organizationId()));
    }
    @Test void crossTenantCannotDownloadBindRemoveOrListAnotherArticlesAttachments() {
        var owner=tenant(true);var other=tenant(false);String article=article(owner),id=id(files.upload(part("a.txt","private text"),UUID.randomUUID().toString(),article,"",owner));
        status(HttpStatus.NOT_FOUND,()->files.download(id,other));status(HttpStatus.FORBIDDEN,()->files.remove(id,1,other));
        status(HttpStatus.NOT_FOUND,()->files.list(article,"",1,"",other));status(HttpStatus.NOT_FOUND,()->files.bind(id,article,1,other));
        assertTrue(items(files.list(null,"system",1,"",other)).isEmpty());
    }
    @Test void bindingAndSoftRemovalUseVersionChecksAndKeepOriginalAndAudit() {
        var admin=tenant(true);String id=upload(admin,"diagram.txt","true content"),article=article(admin);
        var bound=file(files.bind(id,article,1,admin));assertEquals(2,bound.get("version"));assertEquals(article,bound.get("articleId"));
        assertTrue(items(files.list(null,"system",1,"",admin)).isEmpty());assertEquals(1,items(files.list(article,"",1,"",admin)).size());
        status(HttpStatus.CONFLICT,()->files.remove(id,1,admin));files.remove(id,2,admin);
        assertTrue(items(files.list(article,"",1,"",admin)).isEmpty());status(HttpStatus.NOT_FOUND,()->files.download(id,admin));
        assertEquals(List.of("REMOVE","BIND","UPLOAD"),files.audit(article,"",admin).stream().map(r->r.get("action")).toList());
        assertTrue(objects.containsKey(db.queryForObject("SELECT oss_location FROM knowledge_files WHERE id=?",String.class,id)));verify(oss,never()).delete(anyString());
    }
    @Test void ossFailureRollsBackIntentAndFileMetadataThenExactRetrySucceeds() throws Exception {
        var member=tenant(false);String request=UUID.randomUUID().toString();
        when(oss.store(any(byte[].class),anyString(),anyString(),anyString())).thenThrow(new IOException("upload failed"));
        status(HttpStatus.SERVICE_UNAVAILABLE,()->files.upload(part("guide.txt","keep original"),request,null,"system",member));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_files WHERE organization_id=?",Integer.class,member.organizationId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_file_uploads WHERE organization_id=?",Integer.class,member.organizationId()));
        storage();assertNotNull(id(files.upload(part("guide.txt","keep original"),request,null,"system",member)));
    }
    @Test void databaseAuditFailureRollsBackSuccessAndExactRetryReusesSameOssOriginal() {
        var member=tenant(false);String request=UUID.randomUUID().toString();int objectCount=objects.size();
        db.execute("CREATE TRIGGER knowledge_file_event_test_fail BEFORE INSERT ON knowledge_file_events FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test audit failure'");
        try {assertThrows(org.springframework.dao.DataAccessException.class,()->files.upload(part("rollback.txt","database rollback"),request,null,"system",member));}
        finally {db.execute("DROP TRIGGER knowledge_file_event_test_fail");}
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_files WHERE organization_id=?",Integer.class,member.organizationId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_file_uploads WHERE organization_id=?",Integer.class,member.organizationId()));
        assertEquals(objectCount+1,objects.size());files.upload(part("rollback.txt","database rollback"),request,null,"system",member);assertEquals(objectCount+1,objects.size());
    }
    @Test void ordinaryUsersHaveNoAuditAndHistoricalIdentitySurvivesRenameAndDeletion() {
        var admin=tenant(true);var member=member(admin.organizationId(),false);String id=upload(member,"history.txt","account history");
        status(HttpStatus.FORBIDDEN,()->files.audit(null,"system",member));
        status(HttpStatus.FORBIDDEN,()->files.remove(id,1,member));
        knowledge.setWriterAccess(member.userId(),Map.of("canWrite",true),admin);
        db.update("UPDATE app_users SET display_name='Changed Name' WHERE id=?",member.userId());files.remove(id,1,member);
        db.update("DELETE FROM app_users WHERE id=?",member.userId());var audit=files.audit(null,"system",admin);
        assertEquals("Changed Name",((Map<?,?>)audit.getFirst().get("actor")).get("displayName"));
        assertEquals("Original Member",((Map<?,?>)audit.get(1).get("actor")).get("displayName"));
        assertEquals(member.userId(),((Map<?,?>)audit.get(1).get("actor")).get("userId"));assertTrue(audit.getFirst().get("createdAt").toString().endsWith("Z"));
    }
    @Test void disabledCurrentMemberCannotUseStaleEnabledSession() {
        var member=tenant(false);String id=upload(member,"active.txt","visible only while enabled");db.update("UPDATE app_users SET status='DISABLED' WHERE id=?",member.userId());
        status(HttpStatus.FORBIDDEN,()->files.download(id,member));status(HttpStatus.FORBIDDEN,()->files.remove(id,1,member));status(HttpStatus.FORBIDDEN,()->files.list(null,"system",1,"",member));
    }
    @Test void accountDisabledDuringRemoteUploadIsRecheckedFromCurrentDatabaseState() throws Exception {
        var member=tenant(false);var started=new CountDownLatch(1);var proceed=new CountDownLatch(1);String request=UUID.randomUUID().toString();
        when(oss.store(any(byte[].class),anyString(),anyString(),anyString())).thenAnswer(call->{
            started.countDown();assertTrue(proceed.await(10,TimeUnit.SECONDS));byte[] data=call.getArgument(0);
            String location="oss://dayan-test/heyi/"+call.getArgument(2)+"/"+KnowledgeFileService.digest(data)+"/"+call.getArgument(3);
            objects.put(location,data.clone());return new OssStorageService.StoredObject(location,data.length);
        });
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=executor.submit(()->files.upload(part("slow.txt","still uploading"),request,null,"system",member));
            try {assertTrue(started.await(10,TimeUnit.SECONDS));db.update("UPDATE app_users SET status='DISABLED' WHERE id=?",member.userId());}
            finally {proceed.countDown();}
            var failure=assertThrows(ExecutionException.class,()->pending.get(15,TimeUnit.SECONDS));
            assertEquals(HttpStatus.FORBIDDEN,((BusinessException)failure.getCause()).getStatus());
        }
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_files WHERE organization_id=?",Integer.class,member.organizationId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_file_events WHERE organization_id=?",Integer.class,member.organizationId()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_file_uploads WHERE organization_id=?",Integer.class,member.organizationId()));
    }
    @Test void pdfIsSavedWithoutPretendingItWasExtractedAndFileSearchEscapesWildcards() {
        var member=tenant(false);String id=upload(member,"policy%_.pdf","%PDF-1.7\nimage-only original");var list=files.list(null,"system",1,"%_",member);
        assertEquals(1L,list.get("total"));assertEquals("STORED_ONLY",items(list).getFirst().get("extractionStatus"));
        assertEquals("/api/v1/knowledge/files/"+id+"/preview",items(list).getFirst().get("previewUrl"));assertEquals("PDF",items(list).getFirst().get("previewType"));
        assertEquals("",db.queryForObject("SELECT extracted_text FROM knowledge_files WHERE id=?",String.class,id));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_article_chunks WHERE organization_id=?",Integer.class,member.organizationId()));
    }
    @Test void powerpointOriginalsAreStoredInOssAndDownloadedUnchangedWithoutPretendExtraction() throws Exception {
        var member=tenant(false);
        var zipBytes=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("ppt/presentation.xml"));zip.write("<p:presentation xmlns:p='http://schemas.openxmlformats.org/presentationml/2006/main'/>".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
        var originals=Map.of("ppt",new byte[]{(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1,0,1,2,3},"pptx",zipBytes.toByteArray());
        for(var original:originals.entrySet()) {
            var result=files.upload(new MockMultipartFile("file","培训."+original.getKey(),"application/octet-stream",original.getValue()),UUID.randomUUID().toString(),null,"system",member);
            var saved=file(result);String id=id(result);assertEquals("STORED_ONLY",saved.get("extractionStatus"));assertEquals(0,saved.get("extractedCharacters"));
            assertEquals("/api/v1/knowledge/files/"+id+"/preview",saved.get("previewUrl"));assertEquals("PDF",saved.get("previewType"));
            assertEquals(original.getKey().equals("ppt")?"application/vnd.ms-powerpoint":"application/vnd.openxmlformats-officedocument.presentationml.presentation",saved.get("contentType"));
            assertEquals(KnowledgeFileService.digest(original.getValue()),saved.get("sha256"));
            assertTrue(saved.get("extractionNote").toString().contains("尚未接入 PowerPoint"));
            try(var input=files.download(id,member).object().resource().getInputStream()){assertArrayEquals(original.getValue(),input.readAllBytes());}
            assertEquals("",db.queryForObject("SELECT extracted_text FROM knowledge_files WHERE id=?",String.class,id));
        }
        assertEquals(2L,files.list(null,"system",1,"",member).get("total"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM knowledge_articles WHERE organization_id=?",Integer.class,member.organizationId()));
    }
    @Test void filePaginationDoesNotHideItemsPastFirstPage() {
        var member=tenant(false);for(int i=0;i<41;i++)upload(member,"page-"+i+".txt","row "+i);
        var first=files.list(null,"system",1,"",member);var second=files.list(null,"system",2,"",member);
        assertEquals(41L,first.get("total"));assertEquals(true,first.get("hasMore"));assertEquals(40,items(first).size());assertEquals(1,items(second).size());assertEquals(false,second.get("hasMore"));
        var ids=new HashSet<>(items(first).stream().map(r->r.get("id")).toList());assertFalse(ids.contains(items(second).getFirst().get("id")));
    }
    private PlatformPrincipal tenant(boolean admin){String id=UUID.randomUUID().toString();db.update("INSERT INTO organizations(id,code,name) VALUES(?,?,?)",id,id,"File test tenant");return member(id,admin);}
    private PlatformPrincipal member(String org,boolean admin){String id=UUID.randomUUID().toString();db.update("INSERT INTO app_users(id,organization_id,username,display_name) VALUES(?,?,?,?)",id,org,id,"Original Member");if(admin){String role=UUID.randomUUID().toString();db.update("INSERT INTO roles(id,organization_id,code,name) VALUES(?,?,?,?)",role,org,"ADMIN","Admin");db.update("INSERT INTO user_roles(user_id,role_id) VALUES(?,?)",id,role);}return new PlatformPrincipal(id,org,org,id,"Stale Session Name","",true,admin?List.of("ADMIN"):List.of(),List.of());}
    private String article(PlatformPrincipal user){return knowledge.save(null,new HashMap<>(Map.of("category","system","title","Attachment article","body","真实文章正文","tags",List.of())),user).get("id").toString();}
    private MockMultipartFile part(String name,String body){return new MockMultipartFile("file",name,"application/octet-stream",body.getBytes(StandardCharsets.UTF_8));}
    private String upload(PlatformPrincipal user,String name,String body){return id(files.upload(part(name,body),UUID.randomUUID().toString(),null,"system",user));}
    @SuppressWarnings("unchecked") private Map<String,Object> file(Map<String,Object> response){return (Map<String,Object>)response.get("file");}
    private String id(Map<String,Object> response){return file(response).get("id").toString();}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(Map<String,Object> response){return (List<Map<String,Object>>)response.get("items");}
    private void status(HttpStatus expected,org.junit.jupiter.api.function.Executable action){assertEquals(expected,assertThrows(BusinessException.class,action).getStatus());}
}
