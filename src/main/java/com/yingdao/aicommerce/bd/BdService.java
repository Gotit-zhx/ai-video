package com.yingdao.aicommerce.bd;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;

@Service
public class BdService {
 private final JdbcTemplate db; private final ObjectMapper json;
 public BdService(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 static BusinessException bad(String s){return new BusinessException(HttpStatus.BAD_REQUEST,s);}
 static String str(Map<String,Object> m,String key){return Objects.toString(m.get(key),"").trim();}
 static boolean admin(PlatformPrincipal p){return p.roleCodes().contains("ADMIN")||p.roleCodes().contains("PLATFORM_ADMIN");}
 void kind(String k){if(!Set.of("influencers","collaborations").contains(k))throw bad("未知记录类型");}
 @SuppressWarnings("unchecked") Map<String,Object> view(Map<String,Object> row,PlatformPrincipal p){
  var m=new LinkedHashMap<String,Object>(json.readValue(row.get("payload").toString(),Map.class));
  for(String k:List.of("id","owner_id","creator_id","influencer_id","version","deleted","created_at","updated_at"))m.put(k,row.get(k));
  m.put("editable",admin(p)||p.userId().equals(row.get("owner_id")));return m;
 }
 Map<String,Object> raw(String id,PlatformPrincipal p){var rows=db.queryForList("SELECT * FROM bd_records WHERE id=? AND organization_id=?",id,p.organizationId());if(rows.isEmpty())throw new BusinessException(HttpStatus.NOT_FOUND,"记录不存在");return rows.getFirst();}
 void writeAccess(Map<String,Object> row,PlatformPrincipal p){if(!admin(p)&&!p.userId().equals(row.get("owner_id")))throw new BusinessException(HttpStatus.FORBIDDEN,"仅负责人和管理员可以维护此记录");}
 public List<Map<String,Object>> list(String k,boolean deleted,PlatformPrincipal p){kind(k);return db.queryForList("SELECT * FROM bd_records WHERE organization_id=? AND kind=? AND deleted=? ORDER BY updated_at DESC",p.organizationId(),k,deleted).stream().map(r->view(r,p)).toList();}
 public Map<String,Object> get(String id,PlatformPrincipal p){return view(raw(id,p),p);}
 public List<Map<String,Object>> members(PlatformPrincipal p){return db.queryForList("SELECT id,display_name FROM app_users WHERE organization_id=? AND status='ACTIVE' ORDER BY display_name",p.organizationId());}
 void owner(String id,PlatformPrincipal p){if(db.queryForObject("SELECT COUNT(*) FROM app_users WHERE id=? AND organization_id=? AND status='ACTIVE'",Integer.class,id,p.organizationId())!=1)throw bad("负责人必须是同公司有效成员");}
 static final Set<String> FIELDS=Set.of("name","platform","account","homepage","realName","mcn","otherAccounts","followers","plays","metricPeriod","metricDate","country","audience","city","languages","categories","tags","contentStyle","level","email","backupEmail","social","messenger","contact","stage","validity","nextDate","nextAction","project","notes","source","method","currency","commission","fee","sampleCost","shippingCost","commissionCost","otherCost","gmv","orders","refunds","startDate","endDate","dueDate","contentType","plannedCount","deliveredCount","works","shop","product","sampleStatus","tracking","sampleSku","sampleQuantity","settlement","rating","period","dataSource");
 static Map<String,Object> clean(Map<String,Object> body){var m=new LinkedHashMap<String,Object>();for(String k:FIELDS){String v=str(body,k);if(v.length()>10000)throw bad("字段内容过长");m.put(k,v);}return m;}
 static void validate(Map<String,Object> m,String k){
  if(str(m,"name").isEmpty())throw bad("请填写名称");
  if(k.equals("influencers")&&(str(m,"platform").isEmpty()||str(m,"account").isEmpty()))throw bad("平台和账号 ID 必填");
  for(String key:List.of("followers","plays","commission","fee","sampleCost","shippingCost","commissionCost","otherCost","gmv","orders","refunds","plannedCount","deliveredCount","sampleQuantity")){String s=str(m,key);if(s.isEmpty())continue;try{var n=new BigDecimal(s);if(n.signum()<0||n.precision()>18||(key.equals("commission")&&n.compareTo(new BigDecimal("100"))>0))throw bad("数值超出范围："+key);}catch(NumberFormatException e){throw bad("数值格式错误："+key);}}
  for(String key:List.of("metricDate","nextDate","startDate","endDate","dueDate")){if(!str(m,key).isEmpty())try{LocalDate.parse(str(m,key));}catch(Exception e){throw bad("日期格式错误："+key);}}
  if(!str(m,"startDate").isEmpty()&&!str(m,"endDate").isEmpty()&&str(m,"startDate").compareTo(str(m,"endDate"))>0)throw bad("结束日期不能早于开始日期");
  String url=str(m,"homepage");if(!url.isEmpty()&&!url.matches("https?://[^\\s]+"))throw bad("主页链接必须使用 http 或 https");
 }
 @Transactional public Map<String,Object> save(String k,String id,Map<String,Object> body,PlatformPrincipal p){
  kind(k);Map<String,Object> row=id==null?null:raw(id,p);if(row!=null){writeAccess(row,p);if(!k.equals(row.get("kind")))throw bad("记录类型错误");if(Boolean.TRUE.equals(row.get("deleted")))throw bad("请先恢复已删除记录");}
  var m=clean(body);validate(m,k);String owner=str(body,"owner_id");if(owner.isEmpty())owner=row==null?p.userId():row.get("owner_id").toString();owner(owner,p);
  if(!admin(p)&&row==null&&!owner.equals(p.userId()))throw bad("新建记录请先由本人负责，再转交同事");
  String influencer=null;if(k.equals("collaborations")){influencer=str(body,"influencer_id");var target=raw(influencer,p);if(!"influencers".equals(target.get("kind"))||Boolean.TRUE.equals(target.get("deleted")))throw bad("请选择有效达人");}
  String identity=k.equals("influencers")?(str(m,"platform")+":"+str(m,"account").replaceFirst("^@","")).toLowerCase(Locale.ROOT):null;
  if(identity!=null&&identity.length()>255)throw bad("账号过长");
  if(row!=null){var old=view(row,p);for(String key:List.of("lockOwner","lockUntil","lockReason","lastContact"))if(old.containsKey(key))m.put(key,old.get(key));}
  try{
   if(id==null){id=UUID.randomUUID().toString();db.update("INSERT INTO bd_records(id,organization_id,kind,creator_id,owner_id,influencer_id,identity_key,payload) VALUES(?,?,?,?,?,?,?,?)",id,p.organizationId(),k,p.userId(),owner,influencer,identity,json.writeValueAsString(m));}
   else {int v;try{v=Integer.parseInt(str(body,"version"));}catch(Exception e){throw bad("缺少版本，请刷新后重试");}if(db.update("UPDATE bd_records SET owner_id=?,influencer_id=?,identity_key=?,payload=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND organization_id=? AND version=?",owner,influencer,identity,json.writeValueAsString(m),id,p.organizationId(),v)!=1)throw new BusinessException(HttpStatus.CONFLICT,"记录已被更新，请关闭详情并刷新");}
  }catch(org.springframework.dao.DuplicateKeyException e){throw new BusinessException(HttpStatus.CONFLICT,"该平台账号已存在（含回收站），请搜索或恢复原记录");}
  return get(id,p);
 }
 @Transactional public void deleted(String id,boolean deleted,PlatformPrincipal p){var r=raw(id,p);writeAccess(r,p);if(deleted&&"influencers".equals(r.get("kind"))&&db.queryForObject("SELECT COUNT(*) FROM bd_records WHERE organization_id=? AND influencer_id=? AND deleted=FALSE",Integer.class,p.organizationId(),id)>0)throw bad("达人存在合作记录，请先将其合作记录移入回收站");if(!deleted&&r.get("influencer_id")!=null&&Boolean.TRUE.equals(raw(r.get("influencer_id").toString(),p).get("deleted")))throw bad("请先恢复关联达人");db.update("UPDATE bd_records SET deleted=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND organization_id=?",deleted,id,p.organizationId());}
 public List<Map<String,Object>> followups(String id,PlatformPrincipal p){raw(id,p);return db.queryForList("SELECT * FROM bd_followups WHERE organization_id=? AND influencer_id=? ORDER BY created_at DESC",p.organizationId(),id);}
 @Transactional public void followup(String id,Map<String,Object> b,PlatformPrincipal p){
  var r=raw(id,p);writeAccess(r,p);if(!"influencers".equals(r.get("kind"))||Boolean.TRUE.equals(r.get("deleted")))throw bad("请选择有效达人");String summary=str(b,"summary");if(summary.isEmpty()||summary.length()>10000)throw bad("请填写跟进内容（最多10000字）");String next=str(b,"nextDate");if(!next.isEmpty())try{LocalDate.parse(next);}catch(Exception e){throw bad("日期格式错误");}
  db.update("INSERT INTO bd_followups(id,organization_id,influencer_id,author_id,author_name,channel,summary,next_date) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),p.organizationId(),id,p.userId(),p.displayName(),str(b,"channel"),summary,next.isEmpty()?null:next);
  var m=view(r,p);m.put("lastContact",LocalDateTime.now().toString());m.put("nextDate",next);m.put("nextAction",str(b,"nextAction"));updatePayload(r,m,p);
 }
 void updatePayload(Map<String,Object> r,Map<String,Object> m,PlatformPrincipal p){if(db.update("UPDATE bd_records SET payload=?,owner_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND organization_id=? AND version=?",json.writeValueAsString(m),m.get("owner_id"),r.get("id"),p.organizationId(),r.get("version"))!=1)throw new BusinessException(HttpStatus.CONFLICT,"记录已变更，请刷新重试");}
 @Transactional public void lock(String id,Map<String,Object>b,PlatformPrincipal p){var r=raw(id,p);if(!"influencers".equals(r.get("kind"))||Boolean.TRUE.equals(r.get("deleted")))throw bad("请选择有效达人");var m=view(r,p);String lockOwner=str(m,"lockOwner"),until=str(m,"lockUntil");boolean active=!lockOwner.isEmpty()&&!until.isEmpty()&&LocalDate.parse(until).compareTo(LocalDate.now())>=0;if(active&&!lockOwner.equals(p.userId())&&!admin(p))throw new BusinessException(HttpStatus.CONFLICT,"该达人已被其他 BD 锁定");if(Boolean.TRUE.equals(b.get("release"))){writeAccess(r,p);m.remove("lockOwner");m.remove("lockUntil");m.remove("lockReason");}else{String end=str(b,"until");try{if(LocalDate.parse(end).isBefore(LocalDate.now()))throw bad("到期日期不能早于今天");}catch(java.time.format.DateTimeParseException e){throw bad("请选择到期日期");}m.put("owner_id",p.userId());m.put("lockOwner",p.userId());m.put("lockUntil",end);m.put("lockReason",str(b,"reason"));}
  updatePayload(r,m,p);
 }
 @Transactional public List<Map<String,Object>> importRows(String k,List<Map<String,Object>> rows,PlatformPrincipal p){if(rows.isEmpty()||rows.size()>500)throw bad("每次导入1至500行");var result=new ArrayList<Map<String,Object>>();for(var row:rows)result.add(save(k,null,row,p));return result;}
}
