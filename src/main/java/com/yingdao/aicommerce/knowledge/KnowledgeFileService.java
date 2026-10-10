package com.yingdao.aicommerce.knowledge;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.storage.OssStorageService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Originals live only in configured Dayan OSS. Database text is an explicit, bounded derivative. */
@Service
public class KnowledgeFileService {
    public static final int MAX_BYTES=50*1024*1024;
    private static final String VIEW_COLUMNS="id,article_id,category,original_name,content_type,extension,size_bytes,sha256,status,version,extraction_status,extraction_note,extracted_characters,created_at,updated_at";
    private static final Set<String> INLINE_TYPES=Set.of("png","jpg","jpeg","webp","pdf");
    private static final Set<String> CONVERTED_TYPES=Set.of("ppt","pptx");
    private static final Map<String,String> TYPES=Map.ofEntries(
            Map.entry("pdf","application/pdf"),Map.entry("doc","application/msword"),Map.entry("docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls","application/vnd.ms-excel"),Map.entry("xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt","application/vnd.ms-powerpoint"),Map.entry("pptx","application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("txt","text/plain"),Map.entry("md","text/markdown"),Map.entry("csv","text/csv"),
            Map.entry("png","image/png"),Map.entry("jpg","image/jpeg"),Map.entry("jpeg","image/jpeg"),Map.entry("webp","image/webp"),Map.entry("mp4","video/mp4"));
    private final JdbcTemplate db;
    private final KnowledgeService knowledge;
    private final ObjectMapper json;
    private final ObjectProvider<OssStorageService> storage;
    private final String dayanBucket;
    private final KnowledgeFilePreviewService previews;
    public KnowledgeFileService(JdbcTemplate db,KnowledgeService knowledge,ObjectMapper json,ObjectProvider<OssStorageService> storage,
            @Value("${APP_DAYAN_OSS_BUCKET:}") String dayanBucket,KnowledgeFilePreviewService previews) {
        this.db=db;this.knowledge=knowledge;this.json=json;this.storage=storage;this.dayanBucket=dayanBucket.trim();this.previews=previews;
    }
    private OssStorageService oss() {
        OssStorageService oss=storage.getIfAvailable();
        if(oss==null||dayanBucket.isEmpty())throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"大言云 OSS 尚未配置，附件未保存，请稍后重试");
        return oss;
    }
    static String fileName(String raw) {
        if(raw==null)throw KnowledgeService.bad("缺少文件名");
        String name=raw.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).strip();
        if(name.isEmpty()||name.length()>240||name.codePoints().anyMatch(Character::isISOControl))throw KnowledgeService.bad("文件名无效或过长");
        return name;
    }
    static String extension(String name) {
        int dot=name.lastIndexOf('.');String ext=dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
        if(!TYPES.containsKey(ext))throw KnowledgeService.bad("支持 PDF、Word、Excel、PPT/PPTX、TXT、MD、CSV、PNG、JPG、WEBP 和 MP4 文件");return ext;
    }
    static void validateMagic(byte[] bytes,String extension) {
        boolean valid=switch(extension) {
            case "pdf" -> bytes.length>=5&&new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-");
            case "doc","xls","ppt" -> bytes.length>=8&&Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1});
            case "docx","xlsx","pptx" -> bytes.length>=4&&bytes[0]=='P'&&bytes[1]=='K'&&bytes[2]==3&&bytes[3]==4;
            case "png" -> bytes.length>=24&&Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10})&&new String(bytes,12,4,StandardCharsets.US_ASCII).equals("IHDR");
            case "jpg","jpeg" -> bytes.length>=4&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255;
            case "webp" -> bytes.length>=16&&new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP");
            case "mp4" -> bytes.length>=12&&new String(bytes,4,4,StandardCharsets.US_ASCII).equals("ftyp");
            default -> true;
        };
        if(!valid)throw KnowledgeService.bad("文件内容与扩展名不匹配，请上传有效文档");
    }
    static String digest(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}
    }
    private Map<String,Object> file(String id,PlatformPrincipal user,boolean lock) {
        var rows=db.queryForList("SELECT * FROM knowledge_files WHERE organization_id=? AND id=?"+(lock?" FOR UPDATE":""),user.organizationId(),id);
        if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"附件不存在");return rows.getFirst();
    }
    private static void active(Map<String,Object> row) {if(!"ACTIVE".equals(row.get("status")))throw new BusinessException(HttpStatus.NOT_FOUND,"附件已移除");}
    private static String text(Object value){return Objects.toString(value,"");}
    private static Object instant(Object value){
        if(value instanceof java.sql.Timestamp time)return time.toInstant().toString();
        if(value instanceof java.time.LocalDateTime time)return time.toInstant(java.time.ZoneOffset.UTC).toString();
        return value;
    }
    private Map<String,Object> view(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        for(String field:List.of("id","category","version","status"))result.put(field,row.get(field));
        for(var entry:Map.of("articleId","article_id","fileName","original_name","contentType","content_type","sizeBytes","size_bytes","sha256","sha256",
                "extractionStatus","extraction_status","extractionNote","extraction_note","extractedCharacters","extracted_characters").entrySet())result.put(entry.getKey(),row.get(entry.getValue()));
        result.put("createdAt",instant(row.get("created_at")));result.put("updatedAt",instant(row.get("updated_at")));
        result.put("downloadUrl","/api/v1/knowledge/files/"+row.get("id")+"/download");
        String extension=text(row.get("extension"));
        if("ACTIVE".equals(row.get("status"))&&(INLINE_TYPES.contains(extension)||CONVERTED_TYPES.contains(extension))) {
            result.put("previewUrl","/api/v1/knowledge/files/"+row.get("id")+"/preview");
            result.put("previewType",Set.of("png","jpg","jpeg","webp").contains(extension)?"IMAGE":"PDF");
        }
        // Neither storage capabilities nor audit identities are exposed by ordinary file lists.
        return result;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> upload(MultipartFile upload,String requestId,String articleId,String category,PlatformPrincipal user) {
        var actor=knowledge.readAccess(user);OssStorageService oss=oss();
        if(upload==null||upload.isEmpty()||upload.getSize()<=0)throw KnowledgeService.bad("请选择非空文件");
        if(upload.getSize()>MAX_BYTES)throw KnowledgeService.bad("单个附件不能超过 50 MB");
        try {requestId=UUID.fromString(requestId).toString();}catch(Exception e){throw KnowledgeService.bad("缺少有效上传请求编号，请刷新后重试");}
        String name=fileName(upload.getOriginalFilename()),ext=extension(name);byte[] bytes;
        try(InputStream input=upload.getInputStream()){bytes=input.readNBytes(MAX_BYTES+1);}catch(IOException e){throw KnowledgeService.bad("读取上传文件失败，请重新选择文件");}
        if(bytes.length==0||bytes.length>MAX_BYTES||bytes.length!=upload.getSize())throw KnowledgeService.bad("文件大小无效或上传未完整接收");
        validateMagic(bytes,ext);
        articleId=articleId==null||articleId.isBlank()?null:articleId.trim();
        if(articleId!=null)category=text(knowledge.raw(articleId,user,false).get("category"));
        if(!KnowledgeService.CATEGORIES.contains(category))throw KnowledgeService.bad("请选择知识库栏目");
        String sha=digest(bytes),requestHash=KnowledgeService.hash(json.writeValueAsString(Arrays.asList(name,sha,articleId,category)));
        // An exact request is serialized at this unique key, not at the whole organization.
        db.update("INSERT IGNORE INTO knowledge_file_uploads(organization_id,request_id,request_sha256) VALUES(?,?,?)",user.organizationId(),requestId,requestHash);
        var intent=db.queryForMap("SELECT file_id,request_sha256 FROM knowledge_file_uploads WHERE organization_id=? AND request_id=? FOR UPDATE",user.organizationId(),requestId);
        if(!requestHash.equals(intent.get("request_sha256")))throw new BusinessException(HttpStatus.CONFLICT,"上传请求编号已用于其他文件，请重新选择文件");
        if(intent.get("file_id")!=null) {
            var prior=file(text(intent.get("file_id")),user,false);
            if(!"ACTIVE".equals(prior.get("status")))throw new BusinessException(HttpStatus.CONFLICT,"此上传请求对应的附件已移除，请重新选择文件上传");
            return Map.of("file",view(prior),"replayed",true);
        }
        var extracted=KnowledgeDocumentTextExtractor.extract(bytes,ext);
        // Stable identity means an ambiguous OSS response can be retried without producing another original.
        String id=UUID.nameUUIDFromBytes((user.organizationId()+":"+requestId).getBytes(StandardCharsets.UTF_8)).toString();OssStorageService.StoredObject stored;
        try {
            stored=oss.store(bytes,TYPES.get(ext),"knowledge/"+user.organizationId()+"/"+id,name);
            if(!stored.location().startsWith("oss://"+dayanBucket+"/")||!oss.isOssLocation(stored.location())||stored.size()!=bytes.length)
                throw new IllegalStateException("Unexpected storage destination");
            if(!oss.exists(stored.location())||oss.download(stored.location()).size()!=bytes.length)throw new IllegalStateException("Object verification failed");
        } catch(Exception failure) {throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"附件上传至大言云 OSS 失败，未保存成功，请重试原文件");}
        // On database rollback retain the private original at the stable request key. An exact retry reuses it;
        // deleting after rollback could race a concurrent retry committing the very same original.
        // Revalidate current membership and binding after a potentially slow remote write.
        actor=knowledge.readAccess(user);if(articleId!=null)knowledge.raw(articleId,user,false);
        db.update("""
            INSERT INTO knowledge_files(id,organization_id,article_id,category,original_name,content_type,extension,size_bytes,sha256,oss_location,
                extraction_status,extraction_note,extracted_text,extracted_characters,uploaded_by)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,user.organizationId(),articleId,category,name,TYPES.get(ext),ext,bytes.length,sha,stored.location(),extracted.status(),extracted.note(),extracted.text(),extracted.text().length(),user.userId());
        var saved=file(id,user,false);event(saved,"UPLOAD",actor,user,0);
        db.update("UPDATE knowledge_file_uploads SET file_id=? WHERE organization_id=? AND request_id=?",id,user.organizationId(),requestId);
        return Map.of("file",view(saved),"replayed",false);
    }
    public Map<String,Object> list(String articleId,String category,int page,String query,PlatformPrincipal user) {
        knowledge.readAccess(user);if(page<1||page>10000)throw KnowledgeService.bad("页码无效");KnowledgeService.bounded(query,100,"搜索内容");var args=new ArrayList<Object>();args.add(user.organizationId());String filter;
        if(articleId!=null&&!articleId.isBlank()){knowledge.raw(articleId,user,false);filter=" AND article_id=?";args.add(articleId);}
        else {if(!KnowledgeService.CATEGORIES.contains(category))throw KnowledgeService.bad("请选择知识库栏目");filter=" AND article_id IS NULL AND category=?";args.add(category);}
        if(!query.isBlank()){filter+=" AND original_name LIKE ? ESCAPE '!'";args.add(KnowledgeService.like(query.strip()));}
        String where=" WHERE organization_id=? AND status='ACTIVE'"+filter;
        Long count=db.queryForObject("SELECT COUNT(*) FROM knowledge_files"+where,Long.class,args.toArray());
        args.add((page-1)*40);
        var rows=db.queryForList("SELECT "+VIEW_COLUMNS+" FROM knowledge_files"+where+" ORDER BY created_at DESC,id LIMIT 40 OFFSET ?",args.toArray());
        long total=count==null?0:count;
        return Map.of("items",rows.stream().map(this::view).toList(),"canAudit",knowledge.administrator(user),"maxFileBytes",MAX_BYTES,"page",page,"pageSize",40,"total",total,"hasMore",page*40L<total);
    }
    @Transactional
    public Map<String,Object> bind(String id,String articleId,int expectedVersion,PlatformPrincipal user) {
        var actor=knowledge.readAccess(user);var article=knowledge.raw(articleId,user,false);var row=file(id,user,true);active(row);
        if(row.get("article_id")!=null) {
            if(Objects.equals(row.get("article_id"),articleId))return Map.of("file",view(row));
            throw new BusinessException(HttpStatus.CONFLICT,"附件已属于另一篇文章，不能转移");
        }
        int changed=db.update("UPDATE knowledge_files SET article_id=?,category=?,version=version+1,updated_at=NOW(6) WHERE id=? AND organization_id=? AND version=? AND status='ACTIVE'",articleId,article.get("category"),id,user.organizationId(),expectedVersion);
        if(changed!=1)throw new BusinessException(HttpStatus.CONFLICT,"附件已更新，请刷新后重试");
        var saved=file(id,user,false);event(saved,"BIND",actor,user,expectedVersion);return Map.of("file",view(saved));
    }
    @Transactional
    public Map<String,Object> remove(String id,int expectedVersion,PlatformPrincipal user) {
        var actor=knowledge.requireWrite(user);var row=file(id,user,true);
        if("REMOVED".equals(row.get("status")))return Map.of("file",view(row));
        int changed=db.update("UPDATE knowledge_files SET status='REMOVED',version=version+1,removed_by=?,removed_at=NOW(6),updated_at=NOW(6) WHERE id=? AND organization_id=? AND version=? AND status='ACTIVE'",user.userId(),id,user.organizationId(),expectedVersion);
        if(changed!=1)throw new BusinessException(HttpStatus.CONFLICT,"附件已更新，请刷新后重试");
        var saved=file(id,user,false);event(saved,"REMOVE",actor,user,expectedVersion);return Map.of("file",view(saved));
    }
    private void event(Map<String,Object> row,String action,Map<String,Object> actor,PlatformPrincipal user,int before) {
        var snapshot=new LinkedHashMap<String,Object>(view(row));snapshot.remove("downloadUrl");
        snapshot.put("actor",Map.of("userId",user.userId(),"displayName",text(actor.get("display_name")),"username",text(actor.get("username")),"identitySource","SNAPSHOT"));
        snapshot.put("action",action);snapshot.put("fromVersion",before);snapshot.put("toVersion",row.get("version"));
        db.update("INSERT INTO knowledge_file_events(id,organization_id,article_id,category,file_id,file_version,action,actor_id,snapshot_json) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),user.organizationId(),row.get("article_id"),row.get("category"),row.get("id"),row.get("version"),action,user.userId(),json.writeValueAsString(snapshot));
    }
    public List<Map<String,Object>> audit(String articleId,String category,PlatformPrincipal user) {
        knowledge.readAccess(user);if(!knowledge.administrator(user))throw new BusinessException(HttpStatus.FORBIDDEN,"仅管理员可查看附件操作记录");
        var args=new ArrayList<Object>();args.add(user.organizationId());String filter;
        if(articleId!=null&&!articleId.isBlank()){knowledge.raw(articleId,user,false);filter=" AND f.article_id=?";args.add(articleId);}
        else {if(!KnowledgeService.CATEGORIES.contains(category))throw KnowledgeService.bad("请选择知识库栏目");filter=" AND f.category=?";args.add(category);}
        var rows=db.queryForList("""
            SELECT e.id,e.file_id,e.file_version,e.action,e.actor_id,e.snapshot_json,e.created_at
            FROM knowledge_file_events e JOIN knowledge_files f ON f.id=e.file_id AND f.organization_id=e.organization_id
            WHERE e.organization_id=?
            """+filter+" ORDER BY e.created_at DESC,e.id LIMIT 200",args.toArray());
        return rows.stream().map(row->{var item=new LinkedHashMap<String,Object>();item.put("id",row.get("id"));item.put("fileId",row.get("file_id"));item.put("version",row.get("file_version"));item.put("action",row.get("action"));item.put("createdAt",instant(row.get("created_at")));var snapshot=json.readValue(row.get("snapshot_json").toString(),Map.class);item.put("file",snapshot);item.put("fileName",snapshot.get("fileName"));item.put("sizeBytes",snapshot.get("sizeBytes"));item.put("actor",snapshot.get("actor"));item.put("fromVersion",snapshot.get("fromVersion"));item.put("toVersion",snapshot.get("toVersion"));return (Map<String,Object>)item;}).toList();
    }
    public Download download(String id,PlatformPrincipal user) {
        knowledge.readAccess(user);var row=file(id,user,false);active(row);
        return new Download(text(row.get("original_name")),storedDownload(row));
    }
    public Preview preview(String id,PlatformPrincipal user) {
        knowledge.readAccess(user);var row=file(id,user,false);active(row);String extension=text(row.get("extension"));
        if(CONVERTED_TYPES.contains(extension)) {
            var pdf=previews.convert(user.organizationId(),text(row.get("oss_location")),text(row.get("sha256")),extension);
            // Conversion can take time; stale privileges or a removed attachment must not receive a new preview.
            knowledge.readAccess(user);var current=file(id,user,false);active(current);
            if(!Objects.equals(row.get("sha256"),current.get("sha256")))throw new BusinessException(HttpStatus.CONFLICT,"附件已更新，请重新打开预览");
            return new Preview(text(row.get("original_name"))+".pdf","application/pdf",pdf);
        }
        if(!INLINE_TYPES.contains(extension))throw KnowledgeService.bad("此文件类型暂不支持在线预览，请下载原文件");
        return new Preview(text(row.get("original_name")),TYPES.get(extension),storedDownload(row));
    }
    private OssStorageService.Download storedDownload(Map<String,Object> row) {
        OssStorageService oss=oss();String location=text(row.get("oss_location"));
        if(!location.startsWith("oss://"+dayanBucket+"/")||!oss.isOssLocation(location))throw new BusinessException(HttpStatus.NOT_FOUND,"附件存储地址无效");
        try {var download=oss.download(location);if(download.size()!=((Number)row.get("size_bytes")).longValue())throw new IllegalStateException("Attachment length changed");return download;}
        catch(RuntimeException e){throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"附件暂时无法下载，请稍后重试");}
    }
    public record Download(String fileName,OssStorageService.Download object) { }
    public record Preview(String fileName,String contentType,OssStorageService.Download object) { }
}
