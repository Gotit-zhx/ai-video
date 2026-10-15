package com.yingdao.aicommerce.product.web;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.application.ProductCaptureService;
import com.yingdao.aicommerce.product.application.CaptureVideoSourceKey;
import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/product-captures/{capture}/videos")
public class ProductCaptureVideoController {
 private final ProductCaptureService captures; private final JdbcTemplate db; private final Path root;
 public ProductCaptureVideoController(ProductCaptureService captures,JdbcTemplate db,@Value("${app.ai-video-task-storage:/data/ai-video-tasks}")String storage){this.captures=captures;this.db=db;this.root=Path.of(storage,"capture-videos");}
 @GetMapping public ApiResponse<List<Map<String,Object>>> list(@PathVariable String capture,@AuthenticationPrincipal PlatformPrincipal user){
  captures.get(capture,user);return ApiResponse.ok(db.queryForList("SELECT id,source_url AS sourceUrl,mime_type AS mimeType,byte_length AS byteLength FROM product_capture_videos WHERE organization_id=? AND capture_id=? AND superseded_by IS NULL ORDER BY created_at,id",user.organizationId(),capture));
 }
 @PostMapping public ApiResponse<Map<String,Object>> upload(@PathVariable String capture,@RequestParam String sourceUrl,@RequestPart("file")MultipartFile file,@AuthenticationPrincipal PlatformPrincipal user)throws Exception{
  captures.get(capture,user);
  if(!user.permissionCodes().contains("product:collect"))throw new BusinessException(HttpStatus.FORBIDDEN,"无视频采集权限");
  if(file.getSize()<12||file.getSize()>64L*1024*1024)throw new BusinessException(HttpStatus.BAD_REQUEST,"视频大小须在64MiB以内");
  if(sourceUrl.length()>4000||!sourceUrl.startsWith("https://"))throw new BusinessException(HttpStatus.BAD_REQUEST,"视频来源链接无效");
  byte[] bytes=file.getBytes();String mime;
  if(bytes[4]=='f'&&bytes[5]=='t'&&bytes[6]=='y'&&bytes[7]=='p')mime="video/mp4";
  else if((bytes[0]&255)==0x1a&&(bytes[1]&255)==0x45&&(bytes[2]&255)==0xdf&&(bytes[3]&255)==0xa3)mime="video/webm";
  else throw new BusinessException(HttpStatus.BAD_REQUEST,"仅支持 MP4 或 WebM 视频文件");
  String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  String sourceKey=CaptureVideoSourceKey.key(sourceUrl);
  var existing=findExisting(user.organizationId(),capture,sourceKey,hash);
  if(existing.isPresent())return ApiResponse.ok(existing.get());
  String id=UUID.randomUUID().toString();Files.createDirectories(root);Files.write(root.resolve(id),bytes,StandardOpenOption.CREATE_NEW);
  try{
   db.update("INSERT INTO product_capture_videos(id,organization_id,capture_id,source_url,mime_type,byte_length,content_sha256,source_asset_key,created_at) VALUES(?,?,?,?,?,?,?,?,NOW(6))",id,user.organizationId(),capture,sourceUrl,mime,bytes.length,hash,sourceKey);
  }catch(DuplicateKeyException e){
   Files.deleteIfExists(root.resolve(id));
   var winner=findExisting(user.organizationId(),capture,sourceKey,hash);
   if(winner.isPresent())return ApiResponse.ok(winner.get());
   throw e;
  }catch(Exception e){Files.deleteIfExists(root.resolve(id));throw e;}
  return ApiResponse.ok(Map.of("id",id,"byteLength",bytes.length));
 }
 private Optional<Map<String,Object>> findExisting(String organization,String capture,String sourceKey,String contentHash){
  var rows=db.queryForList("SELECT COALESCE(superseded_by,id) AS id FROM product_capture_videos WHERE organization_id=? AND capture_id=? AND (source_asset_key=? OR content_sha256=?) ORDER BY created_at,id LIMIT 1",organization,capture,sourceKey,contentHash);
  if(!rows.isEmpty())return Optional.of(rows.getFirst());
  // Old rows predate source keys. Compare only within the already-authorized capture.
  var legacy=db.queryForList("SELECT id,source_url AS sourceUrl FROM product_capture_videos WHERE organization_id=? AND capture_id=? AND source_asset_key IS NULL AND superseded_by IS NULL ORDER BY created_at,id",organization,capture);
  return legacy.stream().filter(row->sourceKey.equals(CaptureVideoSourceKey.key((String)row.get("sourceUrl"))))
    .findFirst().map(row->Map.<String,Object>of("id",row.get("id")));
 }
 @GetMapping("/{id}/file") public ResponseEntity<FileSystemResource> file(@PathVariable String capture,@PathVariable String id,@AuthenticationPrincipal PlatformPrincipal user){
  captures.get(capture,user);
  var rows=db.queryForList("SELECT mime_type,storage_video_id FROM product_capture_videos WHERE organization_id=? AND capture_id=? AND id=?",user.organizationId(),capture,id);
  if(rows.isEmpty()||!id.matches("[a-f0-9-]{36}"))throw new BusinessException(HttpStatus.NOT_FOUND,"视频不存在");
  String file=Objects.toString(rows.getFirst().get("storage_video_id"),id);
  if(!file.matches("[a-f0-9-]{36}"))throw new BusinessException(HttpStatus.NOT_FOUND,"视频归档不存在");
  return ResponseEntity.ok().contentType(MediaType.parseMediaType((String)rows.getFirst().get("mime_type"))).header("X-Content-Type-Options","nosniff").body(new FileSystemResource(root.resolve(file)));
 }
}
