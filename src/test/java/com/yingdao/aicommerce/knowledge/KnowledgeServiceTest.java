package com.yingdao.aicommerce.knowledge;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final KnowledgeService service=new KnowledgeService(db,new ObjectMapper());
    private PlatformPrincipal user(String... permissions) { return new PlatformPrincipal("user-a","org-a","org","user","User","",true,List.of(),List.of(permissions)); }
    private Map<String,Object> draft() { return new HashMap<>(Map.of("category","system","title","Search guide","body","Search team articles.","language","en","platform","ALL","market","ALL","status","DRAFT","tags",List.of("guide"))); }
    private Map<String,Object> row() { var result=draft();result.put("id","article-a");result.put("version",1);result.put("tags_json","[\"guide\"]");result.put("retrieval_enabled",true);return result; }
    @BeforeEach void activeMember() {
        when(db.queryForList(contains("FROM app_users u JOIN organizations"),eq("user-a"),eq("org-a")))
                .thenReturn(List.of(Map.of("id","user-a","display_name","Current Name","username","current-user")));
    }
    @Test void rejectsUnknownCategoryAndUnsafeSourceBeforeAnyWrite() {
        var body=draft();body.put("category","tenant-b");assertThrows(BusinessException.class,()->service.save(null,body,user()));
        body.put("category","system");body.put("sourceUrl","javascript:alert(1)");assertThrows(BusinessException.class,()->service.save(null,body,user()));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void ordinaryMemberCannotChooseRestrictedCreateStates() {
        for(String status:List.of("DRAFT","ARCHIVED")){var body=draft();body.put("status",status);assertEquals(HttpStatus.FORBIDDEN,assertThrows(BusinessException.class,()->service.save(null,body,user())).getStatus());}
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void ordinaryAndPublishOnlyMembersCannotReadAuditLog() {
        for(var principal:List.of(user(),user("knowledge:view"),user("knowledge:publish")))
            assertEquals(HttpStatus.FORBIDDEN,assertThrows(BusinessException.class,()->service.versions("article-a",principal)).getStatus());
        verify(db,never()).queryForList(contains("knowledge_article_versions v"),any(Object[].class));
    }
    @Test void tenantMemberWithoutKnowledgePermissionsCanReadDrafts() {
        when(db.queryForList(contains("FROM knowledge_articles WHERE"),eq("org-a"),eq("article-a"))).thenReturn(List.of(row()));
        var result=service.get("article-a",user());assertEquals("DRAFT",result.get("status"));assertEquals("Search team articles.",result.get("body"));
        assertFalse(result.containsKey("actor"));assertFalse(result.containsKey("_audit"));
    }
    @Test void tenantBoundaryIsAppliedBeforeReadingAnyArticle() {
        when(db.queryForList(contains("FROM knowledge_articles WHERE"),eq("org-a"),eq("foreign-id"))).thenReturn(List.of());
        assertThrows(BusinessException.class,()->service.get("foreign-id",user()));
        verify(db).queryForList("SELECT * FROM knowledge_articles WHERE organization_id=? AND id=?","org-a","foreign-id");
    }
    @Test void disabledDatabaseMemberIsRejectedEvenWhenSessionStillEnabled() {
        when(db.queryForList(contains("FROM app_users u JOIN organizations"),eq("user-a"),eq("org-a"))).thenReturn(List.of());
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(BusinessException.class,()->service.save(null,draft(),user())).getStatus());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void missingTenantAndDisabledPrincipalCannotReachDatabase() {
        for(var principal:List.of(new PlatformPrincipal("user-a","","org","user","User","",true,List.of(),List.of()),new PlatformPrincipal("user-a","org-a","org","user","User","",false,List.of(),List.of())))
            assertThrows(BusinessException.class,()->service.get("article-a",principal));
        assertThrows(BusinessException.class,()->service.get("article-a",null));verifyNoInteractions(db);
    }
    @Test void ordinaryMemberCannotChangeExistingStatusThroughSave() {
        var existing=row();existing.put("status","PUBLISHED");when(db.queryForList(contains("FROM knowledge_articles WHERE"),eq("org-a"),eq("article-a"))).thenReturn(List.of(existing));
        var body=draft();body.put("version",1);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(BusinessException.class,()->service.save("article-a",body,user())).getStatus());
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void chunksAreBoundedAndDoNotSplitUnicodePairs() {
        String content="A".repeat(1199)+"😀"+"B".repeat(2500);
        var chunks=KnowledgeService.chunks(content);assertEquals(content,String.join("",chunks));
        assertTrue(chunks.stream().allMatch(c->c.length()<=1200&&!Character.isHighSurrogate(c.charAt(c.length()-1))));
    }
    @Test void retrievalEscapesWildcardsAndUsesOnlyCurrentPublishedTenantChunks() {
        when(db.queryForList(contains("FROM knowledge_article_chunks c"),any(Object[].class))).thenReturn(new ArrayList<>());
        var result=service.retrieve("50%_off!","en",user());
        assertEquals("keyword",result.get("engine"));assertEquals(false,result.get("vectorIndexConnected"));
        verify(db).queryForList(argThat(sql->sql.contains("c.organization_id=?")&&sql.contains("a.status='PUBLISHED'")&&sql.contains("a.version=c.article_version")&&sql.contains("a.retrieval_enabled=TRUE")),eq("org-a"),eq("%50!%!_off!!%"),eq("%50!%!_off!!%"),eq("en"));
    }
    @Test void concurrentEditFailsBeforeVersionOrChunkWrites() {
        when(db.queryForObject(contains("r.code IN ('ADMIN','PLATFORM_ADMIN')"),eq(Integer.class),eq("user-a"),eq("org-a"))).thenReturn(1);
        when(db.queryForList(contains("FROM knowledge_articles WHERE"),eq("org-a"),eq("article-a"))).thenReturn(List.of(Map.of("status","DRAFT","version",2)));
        var body=draft();body.put("version",1);
        assertEquals(HttpStatus.CONFLICT,assertThrows(BusinessException.class,()->service.save("article-a",body,user())).getStatus());
        verify(db,never()).update(startsWith("INSERT INTO knowledge_article_versions"),any(Object[].class));
        verify(db,never()).update(startsWith("DELETE FROM knowledge_article_chunks"),any(Object[].class));
    }
    @Test void auditRoleCheckUsesCurrentTenantMappingRatherThanCachedAdminRole() {
        var staleAdmin=new PlatformPrincipal("user-a","org-a","org","user","User","",true,List.of("ADMIN"),List.of());
        assertFalse(service.administrator(staleAdmin));
        when(db.queryForObject(contains("FROM user_roles ur JOIN roles r"),eq(Integer.class),eq("user-a"),eq("org-a"))).thenReturn(1);
        assertTrue(service.administrator(user()),"A newly granted current admin role is recognized without a stale principal role requirement");
    }
}
