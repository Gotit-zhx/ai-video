package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceBatchItem;
import com.yingdao.aicommerce.product.mapper.CommerceBatchMapper;
import com.yingdao.aicommerce.shop.mapper.ShopMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;

/** Frozen per-product destination settings, prepared before paid image generation. */
@Service
public class CommerceBatchPublicationOptions {
 public record PublicationRequest(String captureId,String shopId,Map<String,Object> request) {}
 private static final Set<String> FIELDS=Set.of("autoConvertPrice","publishPrice","publishStock","weightGrams",
  "packageLengthCm","packageWidthCm","packageHeightCm","categoryId","warehouseId","productAttributes",
  "logisticInfo","skuStocks","shopeePayload","sizeChartImageId");
 private final CommerceBatchMapper batches;private final ProductCaptureService captures;private final ShopMapper shops;
 private final JdbcTemplate db;private final ObjectMapper json;
 @org.springframework.beans.factory.annotation.Autowired private CommerceSourceCopyService sourceCopies;
 public CommerceBatchPublicationOptions(CommerceBatchMapper batches,ProductCaptureService captures,ShopMapper shops,JdbcTemplate db,ObjectMapper json){
  this.batches=batches;this.captures=captures;this.shops=shops;this.db=db;this.json=json;
 }
 public Map<String,Object> find(String org,String batch,CommerceBatchItem item){
  var rows=db.queryForList("SELECT request_json FROM commerce_batch_item_publish_options WHERE organization_id=? AND batch_id=? AND item_id=?",org,batch,item.id());
  if(rows.isEmpty())rows=db.queryForList("SELECT request_json FROM commerce_batch_publish_options WHERE organization_id=? AND batch_id=? AND shop_id=?",org,batch,item.shopId());
  return rows.isEmpty()?Map.of():json.readValue((String)rows.getFirst().get("request_json"),Map.class);
 }
 /** The same batch lock used by save closes the gap between reading and submitting. */
 @Transactional public Map<String,Object> forSubmission(String org,String batch,CommerceBatchItem item){
  if(batches.lock(org,batch)==null)throw bad("批次不存在");
  var options=find(org,batch,item);var errors=problems(item,options,org);
  if(!errors.isEmpty())throw bad("发布配置不完整："+String.join("、",errors));
  db.update("INSERT INTO commerce_batch_item_publish_options(organization_id,batch_id,item_id,request_json,frozen) VALUES(?,?,?,?,TRUE) ON DUPLICATE KEY UPDATE frozen=TRUE",org,batch,item.id(),json.writeValueAsString(options));
  return options;
 }
 @Transactional public void save(String batch,List<PublicationRequest> requests,PlatformPrincipal user){
  var items=accessible(batch,user,true);
  if(requests==null||requests.isEmpty())throw bad("发布配置缺失：自动准备发布参数后再开始");
  var keyed=new LinkedHashMap<String,Map<String,Object>>();var errors=new ArrayList<String>();
  for(var request:requests){
   if(request==null||request.captureId()==null||request.shopId()==null)throw bad("发布参数缺少来源商品或目标店铺");
   String captureId=sourceCopies==null?request.captureId():sourceCopies.resolve(user.organizationId(),batch,request.captureId());
   String key=key(captureId,request.shopId());
   if(keyed.containsKey(key))throw bad("同一商品与店铺不能重复提交发布参数");
   if(items.stream().noneMatch(item->key.equals(key(item.captureId(),item.shopId()))))throw bad("发布参数中的商品或店铺不属于当前批次");
   var filtered=new LinkedHashMap<String,Object>();
   if(request.request()!=null)request.request().forEach((field,value)->{if(FIELDS.contains(field)&&value!=null)filtered.put(field,value);});
   if(sourceCopies!=null){
    var remapped=sourceCopies.remapRequest(user.organizationId(),captureId,filtered);
    filtered.clear();filtered.putAll(remapped);
   }
   if(json.writeValueAsString(filtered).length()>2_000_000)throw bad("发布参数超过大小限制");
   keyed.put(key,filtered);
  }
  for(var item:items){
   var options=keyed.get(key(item.captureId(),item.shopId()));
   if(options==null){errors.add(label(item)+"：发布配置缺失");continue;}
   var problems=problems(item,options,user.organizationId());
   if(!problems.isEmpty())errors.add(label(item)+"："+String.join("、",problems));
   int started=Objects.requireNonNullElse(db.queryForObject("SELECT COUNT(*) FROM commerce_batch_publications WHERE organization_id=? AND item_id=?",Integer.class,user.organizationId(),item.id()),0);
   int frozen=Objects.requireNonNullElse(db.queryForObject("SELECT COUNT(*) FROM commerce_batch_item_publish_options WHERE organization_id=? AND batch_id=? AND item_id=? AND frozen=TRUE",Integer.class,user.organizationId(),batch,item.id()),0);
   if(started>0||frozen>0){
    var original=find(user.organizationId(),batch,item);
    if(!canonical(original).equals(canonical(options)))errors.add(label(item)+"：已开始提交，不能修改固定发布参数");
    else keyed.put(key(item.captureId(),item.shopId()),original);
   }
  }
  if(!errors.isEmpty())throw bad("自动准备发布参数未完成："+String.join("；",errors));
  for(var item:items)db.update("INSERT INTO commerce_batch_item_publish_options(organization_id,batch_id,item_id,request_json) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE request_json=VALUES(request_json)",user.organizationId(),batch,item.id(),json.writeValueAsString(keyed.get(key(item.captureId(),item.shopId()))));
 }
 /** Compatibility for an existing single-product caller; never share its category with another product. */
 @Transactional public void saveOne(String batch,String capture,String shop,Map<String,Object> request,PlatformPrincipal user){
  var items=accessible(batch,user,true);
  String resolved=capture==null||sourceCopies==null?capture:sourceCopies.resolve(user.organizationId(),batch,capture);
  var matching=items.stream().filter(item->Objects.equals(shop,item.shopId())&&(resolved==null||Objects.equals(resolved,item.captureId()))).toList();
  if(matching.size()!=1)throw bad("请按商品与店铺提交完整发布参数");
  var chosen=matching.getFirst();var all=new ArrayList<PublicationRequest>();
  for(var item:items)all.add(new PublicationRequest(item.captureId(),item.shopId(),item.id().equals(chosen.id())?request:find(user.organizationId(),batch,item)));
  save(batch,all,user);
 }
 public List<PublicationRequest> get(String batch,PlatformPrincipal user){
  return accessible(batch,user,false).stream().map(item->new PublicationRequest(item.captureId(),item.shopId(),find(user.organizationId(),batch,item))).toList();
 }
 public void requireReady(String batch,PlatformPrincipal user){
  var items=accessible(batch,user,false);
  if(Objects.requireNonNullElse(db.queryForObject("SELECT COUNT(*) FROM commerce_batch_creation_modes WHERE organization_id=? AND batch_id=? AND mode='CREATIVE_ONLY'",Integer.class,user.organizationId(),batch),0)>0)return;
  var errors=new ArrayList<String>();
  for(var item:items){
   var options=find(user.organizationId(),batch,item);var problems=problems(item,options,user.organizationId());
   if(Boolean.TRUE.equals(options.get("autoConvertPrice"))){
    var source=captures.get(item.captureId(),user);
    if(source==null||source.capture()==null)problems.add("来源商品资料不可用");
    else {
     try {java.util.Currency.getInstance(Objects.toString(source.capture().getCurrency(),"").toUpperCase(Locale.ROOT));}
     catch(IllegalArgumentException failure){problems.add("来源商品币种无效，无法自动换算");}
     var shop=shops.selectById(item.shopId());
     if(shop!=null)try{PublishExchangeRates.currency(shop.getRegion());}catch(BusinessException failure){problems.add(failure.getMessage());}
     if(source.skus().isEmpty()||source.skus().stream().anyMatch(sku->sku.getPrice()==null||sku.getPrice().signum()<=0))problems.add("来源 SKU 售价缺失或无效，无法自动换算");
    }
   }
   if(!problems.isEmpty())errors.add(label(item)+"："+String.join("、",problems));
  }
  if(!errors.isEmpty())throw bad("发布配置缺失："+String.join("；",errors)+"；请自动准备发布参数后重试，尚未启动新的生图调用");
 }
 private List<CommerceBatchItem> accessible(String batch,PlatformPrincipal user,boolean lock){
  if(user==null||user.organizationId()==null||!user.permissionCodes().contains("product:publish"))throw new BusinessException(HttpStatus.FORBIDDEN,"无批次发布权限");
  if((lock?batches.lock(user.organizationId(),batch):batches.get(user.organizationId(),batch))==null)throw bad("批次不存在");
  var items=batches.items(user.organizationId(),batch);for(var item:items)captures.get(item.captureId(),user);return items;
 }
 private List<String> problems(CommerceBatchItem item,Map<String,Object> options,String org){
  var errors=new ArrayList<String>();var shop=shops.selectById(item.shopId());
  if(shop==null||!org.equals(shop.getOrganizationId()))return new ArrayList<>(List.of("目标店铺不可访问"));
  if(!"ACTIVE".equals(shop.getStatus()))errors.add("店铺授权已失效");
  String platform=shop.getPlatform();
  if(!Set.of("TIKTOK_SHOP","SHOPEE").contains(Objects.toString(platform,"")))errors.add("不支持此店铺平台");
  errors.addAll(validate(options,platform));return errors;
 }
 static List<String> validate(Map<String,Object> options,String platform){
  var errors=new ArrayList<String>();
  if(Boolean.TRUE.equals(options.get("autoConvertPrice"))){if(options.containsKey("publishPrice"))errors.add("自动换算与固定售价不能同时启用");}
  else if(!positive(options.get("publishPrice")))errors.add("售价规则缺失");
  if(!nonnegativeInteger(options.get("publishStock")))errors.add("库存无效");
  for(var field:Map.of("weightGrams","包裹重量","packageLengthCm","包裹长","packageWidthCm","包裹宽","packageHeightCm","包裹高").entrySet())if(!positive(options.get(field.getKey())))errors.add(field.getValue()+"无效");
  if(Objects.toString(options.get("categoryId"),"").isBlank())errors.add("商品类目缺失");
  if("TIKTOK_SHOP".equals(platform)&&Objects.toString(options.get("warehouseId"),"").isBlank())errors.add("发货仓库缺失");
  if("SHOPEE".equals(platform)&&(!(options.get("logisticInfo") instanceof List<?> list)||list.isEmpty()))errors.add("配送渠道缺失");
  return errors;
 }
 private static boolean positive(Object value){try{return new BigDecimal(String.valueOf(value)).signum()>0;}catch(Exception ignored){return false;}}
 private static Object canonical(Object value){
  if(value instanceof Number number)return new BigDecimal(number.toString()).stripTrailingZeros();
  if(value instanceof Map<?,?> map){var normalized=new TreeMap<String,Object>();map.forEach((k,v)->normalized.put(String.valueOf(k),canonical(v)));return normalized;}
  if(value instanceof List<?> list)return list.stream().map(CommerceBatchPublicationOptions::canonical).toList();
  return value;
 }
 private static boolean nonnegativeInteger(Object value){try{var n=new BigDecimal(String.valueOf(value));return n.signum()>=0&&n.stripTrailingZeros().scale()<=0;}catch(Exception ignored){return false;}}
 private static String key(String capture,String shop){return capture+":"+shop;}
 private static String label(CommerceBatchItem item){return item.captureId()+" → "+item.shopName();}
 private static BusinessException bad(String message){return new BusinessException(HttpStatus.CONFLICT,message);}
}
