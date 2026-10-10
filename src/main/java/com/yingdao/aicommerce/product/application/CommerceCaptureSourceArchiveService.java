package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Explicit file import; never weakens public-address checks in the remote downloader. */
@Service
public class CommerceCaptureSourceArchiveService {
    public record View(String imageId,long captureVersion,String contentSha256,String provenance) {}
    private final JdbcTemplate db;private final ProductCaptureService captures;private final CommerceImageArchive archive;
    public CommerceCaptureSourceArchiveService(JdbcTemplate db,ProductCaptureService captures,CommerceImageArchive archive){this.db=db;this.captures=captures;this.archive=archive;}
    public View upload(String capture,String image,long version,byte[] bytes,PlatformPrincipal user){
        if(user==null||!user.permissionCodes().contains("product:edit"))throw new BusinessException(HttpStatus.FORBIDDEN,"无原图归档权限");
        var detail=captures.get(capture,user);
        if(detail.capture().getVersion()!=version||detail.images().stream().noneMatch(i->i.getId().equals(image)))throw bad("来源商品版本或图片不匹配");
        CommerceMockupComposer.decode(bytes);var stored=archive.store(user.organizationId(),bytes);
        db.update("INSERT IGNORE INTO commerce_capture_source_archives(organization_id,capture_id,capture_version,image_id,storage_key,content_sha256,provenance,created_by,created_at) VALUES(?,?,?,?,?,?,'UPLOADED_FILE',?,NOW(6))",user.organizationId(),capture,version,image,stored.storageKey(),stored.sha256(),user.userId());
        var row=db.queryForMap("SELECT * FROM commerce_capture_source_archives WHERE organization_id=? AND capture_id=? AND capture_version=? AND image_id=?",user.organizationId(),capture,version,image);
        if(!stored.sha256().equals(row.get("content_sha256")))throw bad("此版本原图已归档，不能覆盖；如需更换请更新来源商品");
        return new View(image,version,stored.sha256(),(String)row.get("provenance"));
    }
    public String find(String capture,String image,long version,PlatformPrincipal user){
        var detail=captures.get(capture,user);
        if(detail.capture().getVersion()!=version||detail.images().stream().noneMatch(i->i.getId().equals(image)))throw bad("来源版本已变化");
        var rows=db.queryForList("SELECT storage_key FROM commerce_capture_source_archives WHERE organization_id=? AND capture_id=? AND capture_version=? AND image_id=?",user.organizationId(),capture,version,image);
        if(rows.isEmpty())return null;String key=(String)rows.getFirst().get("storage_key");archive.read(user.organizationId(),key);return key;
    }
    private static BusinessException bad(String message){return new BusinessException(HttpStatus.CONFLICT,message);}
}
