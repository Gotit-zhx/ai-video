package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.mapper.CommerceTemplateMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Service
public class CommerceMockupGuideService {
 public record Request(String templateSnapshotId,String side,Map<String,String> prints){}
 public record View(String id,String templateSnapshotId,String side,String imageUrl,String artifactKind,boolean productionReady){}
 private final JdbcTemplate db;private final CommerceTemplateMapper templates;private final CommerceSampleService samples;private final CommercePrintTemplateService printTemplates;private final CommerceImageArchive archive;private final ObjectMapper json;
 public CommerceMockupGuideService(JdbcTemplate db,CommerceTemplateMapper templates,CommerceSampleService samples,CommercePrintTemplateService printTemplates,CommerceImageArchive archive,ObjectMapper json){this.db=db;this.templates=templates;this.samples=samples;this.printTemplates=printTemplates;this.archive=archive;this.json=json;}
 private void access(PlatformPrincipal u){if(u==null||u.organizationId()==null||!u.permissionCodes().contains("product:edit"))throw new BusinessException(HttpStatus.FORBIDDEN,"无套版创作权限");}
 public List<View> list(PlatformPrincipal u){access(u);return db.queryForList("SELECT * FROM commerce_mockup_guides WHERE organization_id=? AND created_by=? ORDER BY created_at DESC LIMIT 40",u.organizationId(),u.userId()).stream().map(this::view).toList();}
 public View create(Request r,String key,PlatformPrincipal u){
  access(u);if(r==null||r.templateSnapshotId()==null||!CommerceSurfaceTemplateInputs.SIDES.contains(Objects.toString(r.side(),""))||r.prints()==null||r.prints().isEmpty()||r.prints().size()>32||key==null||!key.matches("[A-Za-z0-9_-]{8,80}"))throw bad("请选择已保存的模板版本、面向和印花槽位");
  if(r.prints().entrySet().stream().anyMatch(e->e.getKey()==null||e.getValue()==null))throw bad("印花槽位与素材不能为空");
  String binding=json.writeValueAsString(new TreeMap<>(r.prints()));
  var existing=db.queryForList("SELECT * FROM commerce_mockup_guides WHERE organization_id=? AND created_by=? AND request_key=?",u.organizationId(),u.userId(),key);
  if(!existing.isEmpty())return replay(existing.getFirst(),r,binding);
  var snapshot=templates.snapshot(u.organizationId(),r.templateSnapshotId());if(snapshot==null||!"PRINT_LAYOUT".equals(snapshot.kind()))throw bad("印花模板版本不存在");
  var config=json.readValue(snapshot.configJson(),CommercePrintTemplateService.Configuration.class);CommercePrintTemplateService.validate(config);
  String base;byte[] output;
  if(CommerceSurfaceTemplateInputs.enabled(config)){
   var target=CommerceSurfaceTemplateInputs.load(config,archive,u.organizationId()).get(r.side());
   if(target==null)throw bad("当前面没有可见的三维印花位置，不能改用默认正面槽位");
   if(!r.prints().keySet().equals(target.visiblePrintIds()))throw bad("请选择当前面全部可见的三维印花位置，不能混入其他面或遗漏槽位");
   var replacements=new LinkedHashMap<String,byte[]>();
   for(var bindingEntry:r.prints().entrySet())replacements.put(bindingEntry.getKey(),Base64.getDecoder().decode(samples.printImage(bindingEntry.getValue(),u).dataBase64()));
   base=config.blankRenderedViews().get(r.side());
   try{output=CommerceSurfaceMockupComposer.compose(target.blank(),target.uv(),config.renderPrintIds(),replacements);}catch(IllegalArgumentException e){throw bad(e.getMessage());}
  }else{
   base=config.baseImages().get(r.side());if(base==null)throw bad("当前面没有上传底图。原商品清除旧印花流程尚未接通，不能直接叠印。");
   var layers=new ArrayList<CommerceMockupComposer.Layer>();
   for(var area:config.areas())if(r.prints().containsKey(area.id())){if(!r.side().equals(area.side()))throw bad("不能把另一面的印花混入当前面");var ref=samples.printImage(r.prints().get(area.id()),u);layers.add(new CommerceMockupComposer.Layer(area,Base64.getDecoder().decode(ref.dataBase64())));}
   if(layers.size()!=r.prints().size())throw bad("印花槽位不属于当前模板版本");
   var baseImage=printTemplates.image(base,u);
   try{output=CommerceMockupComposer.compose(Base64.getDecoder().decode(baseImage.dataBase64()),layers);}catch(IllegalArgumentException e){throw bad(e.getMessage());}
  }
  var stored=archive.store(u.organizationId(),output);String id=UUID.randomUUID().toString();
  db.update("INSERT IGNORE INTO commerce_mockup_guides(id,organization_id,created_by,request_key,template_snapshot_id,side,bindings_json,base_hash,storage_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,NOW(6))",id,u.organizationId(),u.userId(),key,r.templateSnapshotId(),r.side(),binding,base,stored.storageKey());
  return replay(db.queryForMap("SELECT * FROM commerce_mockup_guides WHERE organization_id=? AND created_by=? AND request_key=?",u.organizationId(),u.userId(),key),r,binding);
 }
 private View replay(Map<String,Object> row,Request r,String binding){if(!r.templateSnapshotId().equals(row.get("template_snapshot_id"))||!r.side().equals(row.get("side"))||!binding.equals(row.get("bindings_json")))throw new BusinessException(HttpStatus.CONFLICT,"同一请求编号不能更换模板或印花");return view(row);}
 public CommerceDesignImageGateway.Reference image(String id,PlatformPrincipal u){access(u);var rows=db.queryForList("SELECT * FROM commerce_mockup_guides WHERE id=? AND organization_id=? AND created_by=?",id,u.organizationId(),u.userId());if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"套版参考图不存在");return archive.read(u.organizationId(),(String)rows.getFirst().get("storage_key"));}
 private View view(Map<String,Object> r){String id=(String)r.get("id");return new View(id,(String)r.get("template_snapshot_id"),(String)r.get("side"),"/api/v1/commerce-mockup-guides/"+id+"/image","PLACEMENT_GUIDE",false);}
 private static BusinessException bad(String message){return new BusinessException(HttpStatus.BAD_REQUEST,message);}
}
