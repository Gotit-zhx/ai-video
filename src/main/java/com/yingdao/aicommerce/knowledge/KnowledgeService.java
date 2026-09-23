package com.yingdao.aicommerce.knowledge;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class KnowledgeService {
    private static final String WRITER_ROLE="KNOWLEDGE_WRITER";
    static final Set<String> CATEGORIES=Set.of("shop","influencer","video","system","faq");
    static final Set<String> LANGUAGES=Set.of("zh","en","ms","id","th","vi","fil");
    static final Set<String> STATUSES=Set.of("DRAFT","PUBLISHED","ARCHIVED");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public KnowledgeService(JdbcTemplate db,ObjectMapper json) { this.db=db;this.json=json; }
    boolean allowed(PlatformPrincipal user,String permission) {
        if(!member(user))return false;
        if(administrator(user))return true;
        Integer count=db.queryForObject("""
            SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id
            JOIN role_permissions rp ON rp.role_id=r.id JOIN permissions p ON p.id=rp.permission_id
            WHERE ur.user_id=? AND r.organization_id=? AND r.status='ACTIVE' AND p.code=?
            """,Integer.class,user.userId(),user.organizationId(),permission);
        return count!=null&&count>0;
    }
    static boolean member(PlatformPrincipal user) {
        return user!=null&&user.isEnabled()&&user.userId()!=null&&!user.userId().isBlank()
                &&user.organizationId()!=null&&!user.organizationId().isBlank();
    }
    boolean administrator(PlatformPrincipal user) {
        if(!member(user))return false;
        Integer count=db.queryForObject("""
            SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id
            WHERE ur.user_id=? AND r.organization_id=? AND r.status='ACTIVE' AND r.code IN ('ADMIN','PLATFORM_ADMIN')
            """,Integer.class,user.userId(),user.organizationId());
        return count!=null&&count>0;
    }
    boolean canWrite(PlatformPrincipal user) {
        if(!member(user))return false;
        if(administrator(user))return true;
        Integer count=db.queryForObject("""
            SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id
            WHERE ur.user_id=? AND r.organization_id=? AND r.status='ACTIVE' AND r.code=?
            """,Integer.class,user.userId(),user.organizationId(),WRITER_ROLE);
        return count!=null&&count>0;
    }
    Map<String,Object> requireWrite(PlatformPrincipal user) {
        var actor=readAccess(user);
        if(!canWrite(user))throw new BusinessException(HttpStatus.FORBIDDEN,"仅有知识库读和上传权限，请联系管理员开通写权限");
        return actor;
    }
    public Map<String,Boolean> writerAccess(PlatformPrincipal user) {
        readAccess(user);
        if(!administrator(user))throw new BusinessException(HttpStatus.FORBIDDEN,"仅管理员可配置知识库权限");
        var rows=db.queryForList("""
            SELECT u.id,EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
                WHERE ur.user_id=u.id AND r.organization_id=u.organization_id AND r.status='ACTIVE'
                AND r.code IN ('ADMIN','PLATFORM_ADMIN',?)) AS can_write
            FROM app_users u WHERE u.organization_id=?
            """,WRITER_ROLE,user.organizationId());
        var access=new LinkedHashMap<String,Boolean>();
        for(var row:rows)access.put(row.get("id").toString(),flag(row.get("can_write")));
        return access;
    }
    @Transactional
    public Map<String,Object> setWriterAccess(String userId,Map<String,Object> body,PlatformPrincipal user) {
        readAccess(user);
        if(!administrator(user))throw new BusinessException(HttpStatus.FORBIDDEN,"仅管理员可配置知识库权限");
        if(!Set.of("canWrite").containsAll(body.keySet())||!(body.get("canWrite") instanceof Boolean enabled))
            throw bad("知识库写权限配置无效");
        var target=db.queryForList("""
            SELECT id FROM app_users WHERE id=? AND organization_id=? AND status='ACTIVE' FOR UPDATE
            """,userId,user.organizationId());
        if(target.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"成员不存在或已停用");
        Integer adminCount=db.queryForObject("""
            SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id
            WHERE ur.user_id=? AND r.organization_id=? AND r.status='ACTIVE' AND r.code IN ('ADMIN','PLATFORM_ADMIN')
            """,Integer.class,userId,user.organizationId());
        if(adminCount!=null&&adminCount>0)throw new BusinessException(HttpStatus.CONFLICT,"管理员始终拥有写权限");
        if(enabled) {
            db.update("""
                INSERT INTO roles(id,organization_id,code,name,description,data_scope,built_in)
                VALUES(?,?,?,'知识库编辑','可新建、编辑、删除文章及移除附件','ALL',TRUE)
                ON DUPLICATE KEY UPDATE id=id
                """,UUID.randomUUID().toString(),user.organizationId(),WRITER_ROLE);
            db.update("""
                INSERT IGNORE INTO role_permissions(role_id,permission_id)
                SELECT r.id,p.id FROM roles r JOIN permissions p ON p.code='knowledge:edit'
                WHERE r.organization_id=? AND r.code=?
                """,user.organizationId(),WRITER_ROLE);
            db.update("UPDATE roles SET status='ACTIVE' WHERE organization_id=? AND code=?",user.organizationId(),WRITER_ROLE);
            db.update("""
                INSERT IGNORE INTO user_roles(user_id,role_id)
                SELECT ?,id FROM roles WHERE organization_id=? AND code=?
                """,userId,user.organizationId(),WRITER_ROLE);
        } else db.update("""
            DELETE ur FROM user_roles ur JOIN roles r ON r.id=ur.role_id
            WHERE ur.user_id=? AND r.organization_id=? AND r.code=?
            """,userId,user.organizationId(),WRITER_ROLE);
        return Map.of("userId",userId,"canWrite",enabled);
    }
    Map<String,Object> require(PlatformPrincipal user,String permission) {
        var actor=readAccess(user);
        if(!allowed(user,permission))throw new BusinessException(HttpStatus.FORBIDDEN,"没有知识库操作权限");
        return actor;
    }
    Map<String,Object> readAccess(PlatformPrincipal user) {
        if(!member(user))throw new BusinessException(HttpStatus.FORBIDDEN,"请使用已启用的企业成员账号访问知识库");
        var rows=db.queryForList("""
            SELECT u.id,u.display_name,u.username FROM app_users u JOIN organizations o ON o.id=u.organization_id
            WHERE u.id=? AND u.organization_id=? AND u.status='ACTIVE' AND o.status='ACTIVE'
            """,user.userId(),user.organizationId());
        if(rows.isEmpty())throw new BusinessException(HttpStatus.FORBIDDEN,"账号或企业已停用，请联系管理员");
        return rows.getFirst();
    }
    static BusinessException bad(String message) { return new BusinessException(HttpStatus.BAD_REQUEST,message); }
    static String str(Map<String,Object> body,String key) { return Objects.toString(body.get(key),"").trim(); }
    static String bounded(String value,int max,String label) { if(value.length()>max) throw bad(label+"过长");return value; }
    static String like(String value) { return "%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%"; }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    static int version(Object value) {
        try { int n=Integer.parseInt(Objects.toString(value,""));if(n>0)return n; }
        catch(NumberFormatException ignored) { }
        throw bad("缺少有效文章版本，请刷新后重试");
    }
    static boolean flag(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue()!=0; }
    static Map<String,Object> clean(Map<String,Object> body) {
        var out=new LinkedHashMap<String,Object>();
        String category=str(body,"category"),language=str(body,"language"),status=str(body,"status");
        if(!CATEGORIES.contains(category))throw bad("请选择知识库栏目");
        if(!LANGUAGES.contains(language))throw bad("请选择文章语言");
        if(!STATUSES.contains(status))throw bad("文章状态无效");
        String title=bounded(str(body,"title"),200,"标题"),content=bounded(str(body,"body"),100000,"正文");
        if(title.isEmpty())throw bad("请填写标题");
        String platform=str(body,"platform"),market=str(body,"market");
        if(!Set.of("ALL","SHOPEE","TIKTOK_SHOP").contains(platform))throw bad("平台无效");
        if(!Set.of("ALL","MY","SG","ID","PH","TH","VN","CN","OTHER").contains(market))throw bad("市场无效");
        String url=bounded(str(body,"sourceUrl"),2000,"来源链接");
        if(!url.isEmpty())try {
            URI uri=URI.create(url);
            if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)throw bad("来源链接必须是有效的 HTTP 或 HTTPS 地址");
        } catch(IllegalArgumentException e) { throw bad("来源链接必须是有效的 HTTP 或 HTTPS 地址"); }
        Object rawTags=body.getOrDefault("tags",List.of());
        if(!(rawTags instanceof List<?> items) || items.size()>20)throw bad("最多填写20个标签");
        var tags=new LinkedHashSet<String>();
        for(Object item:items) { if(!(item instanceof String))throw bad("标签格式无效");String tag=bounded(item.toString().trim(),40,"标签");if(!tag.isEmpty())tags.add(tag); }
        Object enabled=body.getOrDefault("retrievalEnabled",true);
        if(!(enabled instanceof Boolean))throw bad("检索开关无效");
        out.put("category",category);out.put("language",language);out.put("status",status);out.put("title",title);out.put("body",content);
        out.put("summary",bounded(str(body,"summary"),1000,"摘要"));out.put("platform",platform);out.put("market",market);
        out.put("sourceLabel",bounded(str(body,"sourceLabel"),200,"来源名称"));out.put("sourceUrl",url);out.put("tags",List.copyOf(tags));out.put("retrievalEnabled",enabled);
        return out;
    }
    Map<String,Object> raw(String id,PlatformPrincipal user,boolean lock) {
        var rows=db.queryForList("SELECT * FROM knowledge_articles WHERE organization_id=? AND id=?"+(lock?" FOR UPDATE":""),user.organizationId(),id);
        if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"文章不存在");return rows.getFirst();
    }
    Map<String,Object> view(Map<String,Object> row,boolean content) {
        var out=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","category","title","summary","language","platform","market","status","version"))out.put(key,row.get(key));
        for(var field:Map.of("sourceLabel","source_label","sourceUrl","source_url","createdAt","created_at","updatedAt","updated_at","publishedAt","published_at","contentSha256","content_sha256").entrySet())out.put(field.getKey(),row.get(field.getValue()));
        out.put("tags",json.readValue(Objects.toString(row.get("tags_json"),"[]"),List.class));out.put("retrievalEnabled",flag(row.get("retrieval_enabled")));
        if(content)out.put("body",row.get("body"));
        return out;
    }
    public Map<String,Object> list(String category,String query,String status,String language,int page,PlatformPrincipal user) {
        readAccess(user);
        if(!category.isEmpty()&&!CATEGORIES.contains(category))throw bad("栏目无效");
        if(!language.isEmpty()&&!LANGUAGES.contains(language))throw bad("语言无效");
        if(!status.isEmpty()&&!STATUSES.contains(status))throw bad("状态无效");
        if(page<1 || page>10000)throw bad("页码无效");bounded(query,100,"搜索内容");
        StringBuilder where=new StringBuilder(" WHERE organization_id=?");var args=new ArrayList<Object>();args.add(user.organizationId());
        if(!category.isEmpty()){where.append(" AND category=?");args.add(category);}
        if(!language.isEmpty()){where.append(" AND language=?");args.add(language);}
        if(status.isEmpty())where.append(" AND status<>'ARCHIVED'");else{where.append(" AND status=?");args.add(status);}
        if(!query.isBlank()){where.append(" AND (title LIKE ? ESCAPE '!' OR summary LIKE ? ESCAPE '!' OR body LIKE ? ESCAPE '!' OR CAST(tags_json AS CHAR) LIKE ? ESCAPE '!')");for(int n=0;n<4;n++)args.add(like(query.trim()));}
        Long total=db.queryForObject("SELECT COUNT(*) FROM knowledge_articles"+where,Long.class,args.toArray());
        args.add(40);args.add((page-1)*40);
        var items=db.queryForList("SELECT * FROM knowledge_articles"+where+" ORDER BY updated_at DESC,id LIMIT ? OFFSET ?",args.toArray()).stream().map(row->view(row,false)).toList();
        Long libraryCount=db.queryForObject("SELECT COUNT(*) FROM knowledge_articles WHERE organization_id=?",Long.class,user.organizationId());
        return Map.of("items",items,"page",page,"pageSize",40,"total",total==null?0:total,"canEdit",canWrite(user),"canPublish",allowed(user,"knowledge:publish"),"canAudit",administrator(user),"libraryEmpty",libraryCount!=null&&libraryCount==0);
    }
    public Map<String,Object> get(String id,PlatformPrincipal user) {
        readAccess(user);var row=raw(id,user,false);
        return view(row,true);
    }
    @Transactional
    public Map<String,Object> save(String id,Map<String,Object> body,PlatformPrincipal user) {
        var actor=id==null?readAccess(user):requireWrite(user);
        Map<String,Object> old=id==null?null:raw(id,user,true);
        var input=old==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(view(old,true));
        input.putAll(body);
        input.putIfAbsent("language","zh");input.putIfAbsent("platform","ALL");input.putIfAbsent("market","ALL");
        input.putIfAbsent("summary","");input.putIfAbsent("body","");input.putIfAbsent("tags",List.of());
        if(str(input,"status").isEmpty())input.put("status",old==null?"PUBLISHED":old.get("status"));
        var clean=clean(input);String status=clean.get("status").toString();
        // Saving a new shared article publishes it directly; explicit state administration remains restricted.
        if(old==null?!"PUBLISHED".equals(status):!Objects.equals(old.get("status"),status))require(user,"knowledge:publish");
        return persist(id,clean,old,body.get("version"),user,actor,old==null?"CREATE":"EDIT");
    }

    @Transactional
    public Map<String,Object> changeStatus(String id,Map<String,Object> body,PlatformPrincipal user) {
        var actor=require(user,"knowledge:publish");
        if(!Set.of("status","version").containsAll(body.keySet()))throw bad("状态操作不能修改文章内容");
        String status=str(body,"status");if(!STATUSES.contains(status))throw bad("文章状态无效");
        int expectedVersion=version(body.get("version"));var old=raw(id,user,true);
        var content=view(old,true);content.put("status",status);
        return persist(id,clean(content),old,expectedVersion,user,actor,"STATUS_CHANGE");
    }
    @Transactional
    public Map<String,Object> delete(String id,Map<String,Object> body,PlatformPrincipal user) {
        var actor=requireWrite(user);
        if(!Set.of("version").containsAll(body.keySet()))throw bad("删除操作不能修改文章内容");
        int expectedVersion=version(body.get("version"));
        var old=raw(id,user,true);
        if("ARCHIVED".equals(old.get("status")))throw new BusinessException(HttpStatus.CONFLICT,"文章已删除，请刷新后重试");
        var content=view(old,true);
        content.put("status","ARCHIVED");
        // Preserve immutable versions and attached files for the administrator's audit log.
        return persist(id,clean(content),old,expectedVersion,user,actor,"DELETE");
    }
    private Map<String,Object> persist(String id,Map<String,Object> clean,Map<String,Object> old,Object expectedVersion,PlatformPrincipal user,Map<String,Object> actor,String action) {
        String status=clean.get("status").toString();
        int next=1;String sha=hash(clean.get("body").toString());
        if(old==null) {
            id=UUID.randomUUID().toString();
            db.update("""
                INSERT INTO knowledge_articles(id,organization_id,category,title,summary,body,language,platform,market,tags_json,
                source_label,source_url,status,version,content_sha256,retrieval_enabled,created_by,updated_by,published_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,?,?,IF(?='PUBLISHED',NOW(6),NULL))
                """,id,user.organizationId(),clean.get("category"),clean.get("title"),clean.get("summary"),clean.get("body"),clean.get("language"),clean.get("platform"),clean.get("market"),json.writeValueAsString(clean.get("tags")),clean.get("sourceLabel"),clean.get("sourceUrl"),status,sha,clean.get("retrievalEnabled"),user.userId(),user.userId(),status);
        } else {
            int prior=version(expectedVersion);next=prior+1;
            int changed=db.update("""
                UPDATE knowledge_articles SET category=?,title=?,summary=?,body=?,language=?,platform=?,market=?,tags_json=?,
                source_label=?,source_url=?,status=?,version=version+1,content_sha256=?,retrieval_enabled=?,updated_by=?,updated_at=NOW(6),
                published_at=IF(?='PUBLISHED',NOW(6),NULL) WHERE id=? AND organization_id=? AND version=?
                """,clean.get("category"),clean.get("title"),clean.get("summary"),clean.get("body"),clean.get("language"),clean.get("platform"),clean.get("market"),json.writeValueAsString(clean.get("tags")),clean.get("sourceLabel"),clean.get("sourceUrl"),status,sha,clean.get("retrievalEnabled"),user.userId(),status,id,user.organizationId(),prior);
            if(changed!=1)throw new BusinessException(HttpStatus.CONFLICT,"文章已被更新，请刷新后重试");
        }
        clean.put("version",next);clean.put("id",id);clean.put("contentSha256",sha);
        if(old!=null&&"EDIT".equals(action)) {
            var before=view(old,true);
            if(!Objects.equals(before.get("status"),clean.get("status"))&&AUDITED_FIELDS.stream().filter(k->!"status".equals(k)).allMatch(k->Objects.equals(before.get(k),clean.get(k))))action="STATUS_CHANGE";
        }
        clean.put("_audit",Map.of("schemaVersion",1,"action",action,"userId",user.userId(),
                "displayName",Objects.toString(actor.get("display_name"),""),"username",Objects.toString(actor.get("username"),""),
                "fromVersion",old==null?0:next-1,"toVersion",next));
        db.update("INSERT INTO knowledge_article_versions(article_id,organization_id,version,snapshot_json,content_sha256,author_id) VALUES(?,?,?,?,?,?)",id,user.organizationId(),next,json.writeValueAsString(clean),sha,user.userId());
        // Versioned source snapshots remain immutable; only the current published retrieval material is refreshed.
        db.update("DELETE FROM knowledge_article_chunks WHERE organization_id=? AND article_id=?",user.organizationId(),id);
        if("PUBLISHED".equals(status)&&Boolean.TRUE.equals(clean.get("retrievalEnabled"))) {
            var chunks=chunks(clean.get("body").toString());
            for(int i=0;i<chunks.size();i++)db.update("INSERT INTO knowledge_article_chunks(organization_id,article_id,article_version,chunk_index,content,content_sha256) VALUES(?,?,?,?,?,?)",user.organizationId(),id,next,i,chunks.get(i),hash(chunks.get(i)));
        }
        return get(id,user);
    }
    static List<String> chunks(String text) {
        var result=new ArrayList<String>();String rest=text.strip();
        while(!rest.isEmpty()) {
            int end=Math.min(1200,rest.length());
            if(end<rest.length()){if(Character.isHighSurrogate(rest.charAt(end-1)))end--;int newline=rest.lastIndexOf('\n',end);if(newline>=end/2)end=newline;}
            String chunk=rest.substring(0,end).strip();if(!chunk.isEmpty())result.add(chunk);rest=rest.substring(end).strip();
        }
        return List.copyOf(result);
    }
    private static final List<String> AUDITED_FIELDS=List.of("category","title","summary","body","language","platform","market","tags","sourceLabel","sourceUrl","status","retrievalEnabled");
    public List<Map<String,Object>> versions(String id,PlatformPrincipal user) {
        readAccess(user);if(!administrator(user))throw new BusinessException(HttpStatus.FORBIDDEN,"仅管理员可查看操作记录");raw(id,user,false);
        var rows=db.queryForList("""
            SELECT v.version,v.snapshot_json,v.content_sha256,v.created_at,v.author_id,
                   u.display_name AS actor_display_name,u.username AS actor_username,p.snapshot_json AS previous_snapshot
            FROM knowledge_article_versions v
            LEFT JOIN app_users u ON u.id=v.author_id AND u.organization_id=v.organization_id
            LEFT JOIN knowledge_article_versions p ON p.article_id=v.article_id AND p.organization_id=v.organization_id AND p.version=v.version-1
            WHERE v.organization_id=? AND v.article_id=? ORDER BY v.version DESC LIMIT 100
            """,user.organizationId(),id);
        return rows.stream().map(this::auditView).toList();
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> auditView(Map<String,Object> row) {
        var article=new LinkedHashMap<String,Object>(json.readValue(row.get("snapshot_json").toString(),Map.class));
        Map<String,Object> previous=row.get("previous_snapshot")==null?Map.of():json.readValue(row.get("previous_snapshot").toString(),Map.class);
        Object metadata=article.remove("_audit");Map<String,Object> audit=metadata instanceof Map<?,?>?(Map<String,Object>)metadata:Map.of();
        String authorId=Objects.toString(row.get("author_id"),"");
        boolean verifiedSnapshot=authorId.equals(audit.get("userId"));
        String display=Objects.toString(verifiedSnapshot?audit.get("displayName"):row.get("actor_display_name"),"");
        String username=Objects.toString(verifiedSnapshot?audit.get("username"):row.get("actor_username"),"");
        int current=((Number)row.get("version")).intValue();var changes=new ArrayList<Map<String,Object>>();
        for(String field:AUDITED_FIELDS)if(!Objects.equals(previous.get(field),article.get(field))) {
            var change=new LinkedHashMap<String,Object>();change.put("field",field);change.put("before",previous.get(field));change.put("after",article.get(field));changes.add(change);
        }
        String fallback=current==1?"CREATE":changes.stream().allMatch(c->"status".equals(c.get("field")))&&!changes.isEmpty()?"STATUS_CHANGE":"EDIT";
        String action=Objects.toString(audit.get("action"),fallback);
        if(!Set.of("CREATE","EDIT","STATUS_CHANGE","DELETE").contains(action))action=fallback;
        var out=new LinkedHashMap<String,Object>();out.put("version",current);out.put("fromVersion",current-1);out.put("toVersion",current);
        out.put("createdAt",auditTime(row.get("created_at")));out.put("contentSha256",row.get("content_sha256"));out.put("action",action);
        String identitySource=verifiedSnapshot?"SNAPSHOT":row.get("actor_username")!=null?"CURRENT_PROFILE":"ID_ONLY";
        out.put("actor",Map.of("userId",authorId,"displayName",display,"username",username,"identitySource",identitySource));out.put("changes",changes);out.put("article",article);
        return out;
    }
    private static String auditTime(Object value) {
        if(value instanceof java.sql.Timestamp time)return time.toInstant().toString();
        if(value instanceof java.time.LocalDateTime time)return time.toInstant(java.time.ZoneOffset.UTC).toString();
        if(value instanceof java.time.Instant time)return time.toString();
        return Objects.toString(value,"");
    }
    public Map<String,Object> retrieve(String query,String language,PlatformPrincipal user) {
        readAccess(user);bounded(query,100,"搜索内容");if(query.isBlank())throw bad("请输入搜索内容");if(!language.isEmpty()&&!LANGUAGES.contains(language))throw bad("语言无效");
        var args=new ArrayList<Object>(List.of(user.organizationId(),like(query.trim()),like(query.trim())));String languageFilter="";
        if(!language.isEmpty()){languageFilter=" AND a.language=?";args.add(language);}
        var rows=db.queryForList("""
            SELECT c.article_id,c.article_version,c.chunk_index,c.content,c.content_sha256,a.title,a.category,a.language,a.platform,a.market,a.source_label,a.source_url,a.updated_at
            FROM knowledge_article_chunks c JOIN knowledge_articles a ON a.id=c.article_id AND a.organization_id=c.organization_id AND a.version=c.article_version
            WHERE c.organization_id=? AND a.status='PUBLISHED' AND a.retrieval_enabled=TRUE AND (c.content LIKE ? ESCAPE '!' OR a.title LIKE ? ESCAPE '!')
            """+languageFilter+" ORDER BY a.updated_at DESC,c.article_id,c.chunk_index LIMIT 20",args.toArray());
        for(var row:rows)row.put("citation","knowledge:"+row.get("article_id")+":v"+row.get("article_version")+":"+row.get("chunk_index"));
        return Map.of("engine","keyword","vectorIndexConnected",false,"items",rows);
    }
}
