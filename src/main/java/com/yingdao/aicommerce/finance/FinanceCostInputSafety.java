package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import java.time.YearMonth;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static com.yingdao.aicommerce.finance.FinanceCostInputPreview.*;

/** All explicit human cost writes own a transaction and activate only after a fresh, authorized preview. */
public class FinanceCostInputSafety {
    private static final Set<String> TYPES=Set.of("IMPORT","REMOVE_SOURCE","MANUAL_CREATE","MANUAL_UPDATE","MANUAL_DELETE","MAPPING","SETTINGS");
    private static final Set<String> OPEN=Set.of("BLOCKED","REVIEW","READY","STALE");
    private final FinanceRepository repo;private final FinanceService service;private final FinanceCostWorkspace workspace;private final FinanceCostInputState state;
    public FinanceCostInputSafety(FinanceRepository repo,FinanceService service,FinanceCostWorkspace workspace){this.repo=repo;this.service=service;this.workspace=workspace;state=new FinanceCostInputState(repo,service,workspace);}
    private Scope manager(PlatformPrincipal p){service.requireManagement(p);return service.authorize(p);}
    private boolean manages(PlatformPrincipal p){try{service.requireManagement(p);return true;}catch(com.yingdao.aicommerce.common.exception.BusinessException e){return false;}}
    public Map<String,Object> stage(PlatformPrincipal p,String type,String group,String shop,String target,Map<String,Object> rawBody,byte[] bytes,String name,Map<String,Object> rawMetadata){
        Scope scope=manager(p);if(!TYPES.contains(type))throw bad("不支持的人工成本操作");
        var body=new LinkedHashMap<>(rawBody);body.remove("metadata");var meta=metadata(rawMetadata);
        // Match the existing save methods' required-text normalization before deriving target keys.
        if(Set.of("MAPPING","MANUAL_CREATE").contains(type))for(String field:List.of("product_id","sku_id","code","warehouse"))if(body.containsKey(field))body.put(field,text(body.get(field)).trim());
        if(Set.of("MANUAL_CREATE","MANUAL_UPDATE").contains(type)&&body.containsKey("unit"))body.put("unit",text(body.get("unit")).trim());
        if(bytes!=null&&bytes.length>12*1024*1024)throw bad("文件不能超过 12 MB");
        return repo.transaction(()->{
            String actualGroup=shop.isBlank()?group:text(service.ownedShop(scope,shop).get("group_id"));
            if(!group.isBlank()&&!group.equals(actualGroup))throw bad("店铺不属于所选分组");state.lock(scope,actualGroup,shop);
            String actualTarget=target;
            Map<String,Object> payload=new LinkedHashMap<>(body);
            if(type.equals("IMPORT")){
                if(bytes==null)throw bad("请选择成本文件");
                var existingSource=state.one("SELECT id FROM finance_cost_sources WHERE organization_id=? AND group_id=? AND content_hash=?",scope.organizationId(),actualGroup,hash(Base64.getEncoder().encodeToString(bytes)));
                if(!existingSource.isEmpty())actualTarget=text(existingSource.get("id"));
                try{payload.put("costs",parse(bytes,name));payload.put("fileFormat",name.toLowerCase(Locale.ROOT).endsWith(".csv")?".csv":".xlsx");}catch(com.yingdao.aicommerce.common.exception.BusinessException e){payload.put("parseInvalid",true);payload.put("parseReason",boundedParseReason(e.getMessage()));payload.put("invalidFileHash",hash(Base64.getEncoder().encodeToString(bytes)));}
            }else validate(scope,type,actualGroup,shop,actualTarget,body);
            if(noChange(scope,type,actualGroup,shop,actualTarget,payload))return Map.<String,Object>of("staged",true,"duplicate",true,"status","APPLIED");
            var preview=preview(scope,type,actualGroup,shop,actualTarget,payload,meta,name);
            if(type.equals("IMPORT")&&!Boolean.TRUE.equals(map(preview.get("validation")).get("blocked"))&&number(map(preview.get("validation")).get("changedCells"))==0)
                return Map.<String,Object>of("staged",true,"duplicate",true,"status","APPLIED","rows",rows(payload.get("costs")).size());
            String baseline=state.fingerprint(scope,actualGroup,shop,payload),semantic=state.digest(Map.of("type",type,"target",actualTarget,"shop",shop,"payload",semanticPayload(payload),"metadata",meta));
            var duplicate=repo.jdbc().queryForList("SELECT id,status FROM finance_cost_input_changes WHERE organization_id=? AND group_id=? AND semantic_hash=? AND baseline_hash=? AND status IN ('BLOCKED','REVIEW','READY') ORDER BY created_at LIMIT 1",scope.organizationId(),actualGroup,semantic,baseline);
            if(!duplicate.isEmpty())return staged(duplicate.getFirst(),true,rows(payload.get("costs")).size());
            return insert(scope,p.userId(),type,actualGroup,shop,actualTarget,payload,meta,bytes,name,semantic,baseline,preview,state.capture(scope,type,actualGroup,shop,actualTarget,payload),null);
        });
    }
    private Object semanticPayload(Map<String,Object> payload){
        if(!payload.containsKey("costs"))return payload;
        var normalized=new TreeMap<String,Object>();
        for(var row:rows(payload.get("costs"))){var copy=new TreeMap<>(row);copy.remove("row");copy.remove("identifierEvidence");var prices=new TreeMap<String,Object>();map(row.get("prices")).forEach((month,price)->prices.put(month,decimal(price)==null?price:decimal(price)));copy.put("prices",prices);normalized.put(state.digest(copy),copy);}
        return normalized.values();
    }
    private Map<String,Object> insert(Scope s,String actor,String type,String group,String shop,String target,Map<String,Object> payload,Map<String,Object> meta,byte[] bytes,String name,String semantic,String baseline,Map<String,Object> preview,Map<String,Object> before,String original){
        String id=UUID.randomUUID().toString();
        String originalHash=bytes==null?null:hash(Base64.getEncoder().encodeToString(bytes));byte[] storedBytes=bytes;String originalRef=original;
        if(bytes!=null){var retained=state.one("SELECT id FROM finance_cost_input_changes WHERE organization_id=? AND original_hash=? AND original_bytes IS NOT NULL ORDER BY created_at LIMIT 1",s.organizationId(),originalHash);if(!retained.isEmpty()){storedBytes=null;originalRef=text(retained.get("id"));}}
        repo.jdbc().update("""
            INSERT INTO finance_cost_input_changes(id,organization_id,group_id,shop_id,type,status,target_id,source_name,payload_json,metadata_json,
            original_bytes,original_hash,original_change_id,semantic_hash,baseline_hash,validation_json,diff_json,impact_json,state_json,requires_independent_review,submitted_by,last_edited_by)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,s.organizationId(),group,shop,type,preview.get("status"),target,name.substring(0,Math.min(250,name.length())),repo.write(payload),repo.write(meta),storedBytes,
            originalHash,originalRef,semantic,baseline,repo.write(preview.get("validation")),repo.write(previewData(preview)),repo.write(preview.get("impact")),repo.write(before),preview.get("requiresIndependentReview"),actor,actor);
        return Map.of("staged",true,"changeId",id,"status",preview.get("status"),"duplicate",false,"rows",rows(payload.get("costs")).size());
    }
    private Map<String,Object> staged(Map<String,Object> row,boolean duplicate,int count){return Map.of("staged",true,"changeId",row.get("id"),"status",row.get("status"),"duplicate",duplicate,"rows",count);}
    private Map<String,Object> preview(Scope s,String type,String group,String shop,String target,Map<String,Object> payload,Map<String,Object> meta,String name){
        String scopeShop=Set.of("MAPPING","SETTINGS").contains(type)?shop:"";
        var impact=state.impact(s,group,scopeShop);boolean independent=number(impact.get("scopeOrderCount"))>LARGE_ORDER_SCOPE;
        Map<String,Object> validation;List<Map<String,Object>> diff;Map<String,Object> inputSummary=Map.of();
        if(type.equals("IMPORT")){
            var current=rows(workspace.book(s,group).get("costs"));var supplied=new LinkedHashMap<>(meta);supplied.put("nowMonth",YearMonth.now(java.time.ZoneId.of("Asia/Shanghai")).toString());supplied.put("sourceVerified",false);
            validation=Boolean.TRUE.equals(payload.get("parseInvalid"))?new LinkedHashMap<>(parseFailure(text(payload.get("parseReason")))):new LinkedHashMap<>(FinanceCostValidation.analyze(rows(payload.get("costs")),current,supplied,Map.of()));
            var normalized=rows(validation.remove("normalizedCosts"));
            diff=fileDiff(normalized,current,name);
            // The actual calculation key is warehouse+code+month; declared identifier types cannot bypass this gate.
            var active=new HashMap<String,Map<String,Object>>();for(var old:current)active.put(old.get("warehouse")+"\0"+old.get("sku"),map(old.get("prices")));
            boolean override=normalized.stream().anyMatch(row->{var prices=active.getOrDefault(row.get("warehouse")+"\0"+row.get("sku"),Map.of());return map(row.get("prices")).entrySet().stream().anyMatch(cell->prices.containsKey(cell.getKey())&&decimal(cell.getValue()).compareTo(decimal(prices.get(cell.getKey())))!=0);});
            independent|=override||rows(validation.get("issues")).stream().anyMatch(i->"IDENTITY_CHANGED".equals(i.get("code")));validation.put("priceOverride",override);
            var byBusinessKey=new HashMap<String,java.math.BigDecimal>();boolean conflict=false;
            for(var row:rows(payload.get("costs")))for(var price:map(row.get("prices")).entrySet()){
                var value=decimal(price.getValue());if(value==null)continue;
                String key=row.get("warehouse")+"\0"+row.get("sku")+"\0"+price.getKey();var prior=byBusinessKey.putIfAbsent(key,value);
                if(prior!=null&&prior.compareTo(value)!=0)conflict=true;
            }
            if(conflict){var issues=new ArrayList<>(rows(validation.get("issues")));issues.add(issue("DUPLICATE_PRICE_CONFLICT","BLOCK","同仓、同型号、同月存在不同单价，声明编号类型不能改变实际计价键"));validation.put("issues",issues);validation.put("blocked",true);validation.put("risk","BLOCKED");}
            boolean needsType=!Set.of("SKU","SPU","MIXED").contains(text(meta.get("identifierType")))&&rows(payload.get("costs")).stream().anyMatch(r->!Set.of("SKU","SPU").contains(text(r.get("identifierType"))));
            boolean needsUnit=text(meta.get("unit")).isBlank()&&rows(payload.get("costs")).stream().anyMatch(r->text(r.get("unit")).isBlank());
            boolean needsUniform=("SPU".equals(meta.get("identifierType"))||rows(payload.get("costs")).stream().anyMatch(r->"SPU".equals(r.get("identifierType"))))&&!Boolean.TRUE.equals(meta.get("spuUniform"));
            validation.put("needsMetadata",needsType||needsUnit||needsUniform);
            if("MIXED".equals(meta.get("identifierType")))independent=true;
        }else{
            var before=state.capture(s,type,group,shop,target,payload);var old=new LinkedHashMap<String,Object>();
            if(type.equals("SETTINGS"))old.putAll(decode(map(before.get("settings")).get("data_json")));
            if(type.equals("MAPPING"))old.putAll(decode(map(before.get("mapping")).get("data_json")));
            if(type.startsWith("MANUAL")){
                var manual=map(before.get("manual"));if(!manual.isEmpty()){old.put("unit",manual.get("unit"));old.put("unitPriceCny",manual.get("unit_price_cny"));old.put("code",manual.get("code"));old.put("warehouse",manual.get("warehouse"));}
            }
            if(type.equals("REMOVE_SOURCE")){var source=map(before.get("source"));old.put("sourceName",source.get("source_name"));old.put("costRows",rows(decode(source.get("data_json")).get("costs")).size());}
            Map<String,Object> effectiveAfter=Set.of("REMOVE_SOURCE","MANUAL_DELETE").contains(type)?Map.of():payload;
            if(type.equals("MANUAL_UPDATE")){
                // changeManual updates only unit and price; the target code/warehouse remain unchanged.
                old.put("unitPriceCny",decimal(old.get("unitPriceCny")).stripTrailingZeros());
                var after=new LinkedHashMap<>(old);after.put("unit",payload.get("unit"));after.put("unitPriceCny",decimal(payload.get("unitPriceCny")).stripTrailingZeros());
                effectiveAfter=after;
                inputSummary=Map.of("code",old.get("code"),"warehouse",old.get("warehouse"),"unit",after.get("unit"),"oldUnit",old.get("unit"),"newUnit",after.get("unit"));
            }
            diff=configurationDiff(old,effectiveAfter);
            independent|=Set.of("REMOVE_SOURCE","MANUAL_UPDATE","MANUAL_DELETE").contains(type);
            if(type.equals("SETTINGS"))independent|=!diff.isEmpty();
            if(Set.of("MAPPING","MANUAL_CREATE").contains(type))independent|=!repo.jdbc().queryForList("SELECT id FROM finance_cost_mappings WHERE organization_id=? AND shop_id=? AND product_id=? AND sku_id=? LIMIT 1",s.organizationId(),shop,payload.get("product_id"),payload.get("sku_id")).isEmpty();
            validation=new LinkedHashMap<>();validation.put("blocked",false);validation.put("risk",independent?"HIGH":"REVIEW");validation.put("issues",List.of(issue("HUMAN_INPUT_UNVERIFIED","REVIEW","请核对理由、原始依据和操作实际适用范围")));
            validation.put("priceOverride",type.equals("MANUAL_UPDATE")&&decimal(old.get("unitPriceCny")).compareTo(decimal(payload.get("unitPriceCny")))!=0);
        }
        var result=new LinkedHashMap<String,Object>();if(!inputSummary.isEmpty())result.put("inputSummary",inputSummary);result.put("validation",validation);result.put("impact",impact);result.put("diff",diff);result.put("requiresIndependentReview",independent);
        result.put("status",Boolean.TRUE.equals(validation.get("blocked"))?"BLOCKED":independent||Boolean.TRUE.equals(validation.get("requiresReview"))||!rows(validation.get("issues")).isEmpty()?"REVIEW":"READY");return result;
    }
    /** Persist only the bounded public preview, never the application payload or private target snapshot. */
    private Map<String,Object> previewData(Map<String,Object> preview){
        var result=new LinkedHashMap<String,Object>();result.put("items",preview.get("diff"));
        if(preview.containsKey("inputSummary"))result.put("inputSummary",preview.get("inputSummary"));return result;
    }
    private void validate(Scope s,String type,String group,String shop,String target,Map<String,Object> body){
        Set<String> allowed=switch(type){
            case "SETTINGS"->Set.of("warehouse","rules","version");
            case "MAPPING"->Set.of("product_id","sku_id","warehouse","code","factor","confirmed","manualCostId","version");
            case "MANUAL_CREATE"->Set.of("product_id","sku_id","warehouse","code","factor","confirmed","zeroConfirmed","unit","unitPriceCny","version");
            case "MANUAL_UPDATE"->Set.of("unit","unitPriceCny","confirmed","zeroConfirmed","version");
            case "MANUAL_DELETE"->Set.of("version");
            default->Set.of();};
        if(!allowed.containsAll(body.keySet()))throw bad("成本操作包含不支持的字段");
        if(Set.of("SETTINGS","MAPPING","MANUAL_CREATE").contains(type)&&shop.isBlank())throw bad("请选择店铺");
        var current=state.capture(s,type,group,shop,target,body);
        if(type.equals("REMOVE_SOURCE")&&map(current.get("source")).isEmpty())throw bad("成本来源不存在或不属于当前分组");
        if(Set.of("MANUAL_UPDATE","MANUAL_DELETE").contains(type)){
            var row=map(current.get("manual"));if(row.isEmpty()||number(row.get("version"))!=number(body.get("version")))throw bad("手动成本已删除或被修改，请刷新后重试");
        }
        if(Set.of("MANUAL_CREATE","MANUAL_UPDATE").contains(type)){boundedNumber(body.get("unitPriceCny"));FinanceCostWorkspace.manualPrice(body);FinanceCostWorkspace.required(body,"unit",40);}
        if(type.equals("SETTINGS")){
            var rules=map(body.get("rules"));if(!Set.of("currency","unit","basis","missing","freight","inbound").containsAll(rules.keySet()))throw bad("成本规则包含不支持的开关");
            FinanceCostWorkspace.choice(rules,"currency","","CNY");FinanceCostWorkspace.choice(rules,"unit","","包","片","箱","套");FinanceCostWorkspace.choice(rules,"basis","order","shipment");FinanceCostWorkspace.choice(rules,"missing","pending","previous");FinanceCostWorkspace.choice(rules,"freight","","included","excluded");FinanceCostWorkspace.choice(rules,"inbound","","included","excluded");
            String warehouse=text(body.get("warehouse"));if(!warehouse.isBlank()&&FinanceCostSources.allCosts(workspace.book(s,group)).stream().noneMatch(c->warehouse.equals(c.get("warehouse"))))throw bad("成本仓不属于当前分组");
            if(number(map(current.get("settings")).get("version"))!=number(body.get("version")))throw bad("配置已被修改，请刷新");
        }
        if(Set.of("MAPPING","MANUAL_CREATE").contains(type)){
            for(String key:List.of("product_id","sku_id","code","warehouse")){String v=FinanceCostWorkspace.required(body,key,key.equals("warehouse")?120:key.equals("code")?255:128);if(v.startsWith("=")||v.matches(".*[\\r\\n].*"))throw bad("型号和仓库必须是有效文本");}
            boundedNumber(body.get("factor"));FinanceCostWorkspace.positive(body.get("factor"));if(!Boolean.TRUE.equals(body.get("confirmed")))throw bad("请确认销售规格与成本型号");
            if(number(map(current.get("mapping")).get("version"))!=number(body.get("version")))throw bad("关联已被修改，请刷新");
            if(type.equals("MAPPING")){
                String manual=text(body.get("manualCostId"));
                if(manual.isBlank()){
                    if(rows(workspace.book(s,group).get("costs")).stream().noneMatch(c->Objects.equals(body.get("warehouse"),c.get("warehouse"))&&Objects.equals(body.get("code"),c.get("sku"))))throw bad("成本型号不属于当前分组和仓库");
                }else if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_manual_costs WHERE organization_id=? AND group_id=? AND id=? AND warehouse=? AND code=?",Long.class,s.organizationId(),group,manual,body.get("warehouse"),body.get("code"))!=1)throw bad("手动成本不属于当前分组和仓库");
            }else if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_manual_costs WHERE organization_id=? AND group_id=? AND warehouse=? AND code=?",Long.class,s.organizationId(),group,body.get("warehouse"),body.get("code"))>0)throw bad("同型号同仓库已有手动成本，请选择已有成本");
        }
    }
    public Map<String,Object> list(PlatformPrincipal p,String group,String shop,int page){
        Scope s=service.authorize(p);return repo.transaction(()->{
            if(!group.isBlank())new FinanceGroups(repo).require(s,group,false);
            String actualGroup=shop.isBlank()?group:text(service.ownedShop(s,shop).get("group_id"));if(!group.isBlank()&&!group.equals(actualGroup))throw bad("店铺不属于所选分组");
            var args=new ArrayList<Object>();args.add(s.organizationId());String where="organization_id=?";
            if(!actualGroup.isBlank()){where+=" AND group_id=?";args.add(actualGroup);}if(!shop.isBlank()){where+=" AND (shop_id=? OR shop_id='')";args.add(shop);}
            long total=repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_cost_input_changes WHERE "+where,Long.class,args.toArray());
            int actualPage=Math.max(1,page);args.add(30);args.add((long)(actualPage-1)*30);
            var items=repo.jdbc().queryForList("SELECT "+PUBLIC_COLUMNS+" FROM finance_cost_input_changes WHERE "+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(r->view(p,r,false)).toList();
            return Map.of("items",items,"total",total,"page",actualPage,"pageSize",30);
        });
    }
    private static final String PUBLIC_COLUMNS="id,organization_id,group_id,shop_id,type,status,target_id,source_name,metadata_json,validation_json,diff_json,impact_json,requires_independent_review,submitted_by,last_edited_by,confirmed_by,reason,evidence_ref,version,created_at,updated_at,applied_at";
    public Map<String,Object> detail(PlatformPrincipal p,String id){Scope s=service.authorize(p);return repo.transaction(()->view(p,row(s,id,false,false),true));}
    private Map<String,Object> row(Scope s,String id,boolean lock,boolean privateData){
        var found=repo.jdbc().queryForList("SELECT "+(privateData?"*":PUBLIC_COLUMNS)+" FROM finance_cost_input_changes WHERE organization_id=? AND id=?"+(lock?" FOR UPDATE":""),s.organizationId(),id);
        if(found.isEmpty())throw bad("没有找到本公司的成本暂存单");return found.getFirst();
    }
    /** Resolve the immutable routing key before opening a repeatable-read snapshot, then lock group first.
     * Every draft mutation follows group -> draft -> original draft. Shop FOR SHARE gating remains in state.lock.
     */
    private <T> T changeTransaction(Scope s,String id,java.util.function.Function<Map<String,Object>,T> action){
        var route=state.one("SELECT group_id FROM finance_cost_input_changes WHERE organization_id=? AND id=?",s.organizationId(),id);
        if(route.isEmpty())throw bad("没有找到本公司的成本暂存单");String group=text(route.get("group_id"));
        return repo.transaction(()->{
            // Missing/deleted groups still allow rejecting a draft; confirm marks those drafts STALE.
            repo.jdbc().queryForList("SELECT id FROM finance_groups WHERE organization_id=? AND id=? FOR UPDATE",s.organizationId(),group);
            var locked=row(s,id,true,true);
            if(!group.equals(text(locked.get("group_id"))))throw bad("暂存单分组已变化，请刷新");
            return action.apply(locked);
        });
    }
    private Map<String,Object> view(PlatformPrincipal p,Map<String,Object> row,boolean details){
        var result=new LinkedHashMap<String,Object>();
        for(String field:List.of("id","type","status","version"))result.put(field,row.get(field));
        String[][] names={{"group_id","groupId"},{"shop_id","shopId"},{"source_name","sourceName"},{"submitted_by","submittedBy"},{"last_edited_by","lastEditedBy"},{"confirmed_by","confirmedBy"},{"created_at","createdAt"},{"updated_at","updatedAt"},{"applied_at","appliedAt"}};
        for(var name:names)result.put(name[1],row.get(name[0]));
        var validation=decode(row.get("validation_json"));var issues=rows(validation.get("issues"));validation.put("issueCount",issues.size());validation.put("issues",issues.stream().limit(DISPLAY_LIMIT).toList());
        boolean independent=bool(row.get("requires_independent_review")),manage=manages(p),own=p.userId().equals(row.get("submitted_by"));String status=text(row.get("status"));
        result.put("validation",validation);result.put("impact",decode(row.get("impact_json")));result.put("requiresIndependentReview",independent);
        result.put("canConfirm",manage&&Set.of("READY","REVIEW").contains(status)&&!Boolean.TRUE.equals(validation.get("blocked"))&&!Boolean.TRUE.equals(validation.get("needsMetadata"))&&(!independent||!own&&!p.userId().equals(row.get("last_edited_by"))));
        result.put("canReject",manage&&own&&OPEN.contains(status));result.put("canRevise",manage&&own&&OPEN.contains(status));result.put("canUndo",manage&&status.equals("APPLIED")&&!"UNDO".equals(row.get("type")));
        if(details){
            var preview=decode(row.get("diff_json"));result.put("metadata",decode(row.get("metadata_json")));result.put("diff",rows(preview.get("items")).stream().limit(DISPLAY_LIMIT).toList());
            if("MANUAL_UPDATE".equals(row.get("type"))){
                // These five server-generated strings are bounded by target schema and validated unit length.
                var summary=new LinkedHashMap<String,Object>();var saved=map(preview.get("inputSummary"));
                for(String field:List.of("code","warehouse","unit","oldUnit","newUnit"))if(saved.get(field) instanceof String value)summary.put(field,value);
                if(!summary.isEmpty())result.put("inputSummary",summary);
            }
            result.put("actualScope",decode(row.get("impact_json")).get("actualScope"));result.put("reason",row.get("reason"));result.put("evidenceRef",row.get("evidence_ref"));
        }
        return result;
    }
    public Map<String,Object> confirm(PlatformPrincipal p,String id,Map<String,Object> request){
        Scope s=manager(p);Map<String,Object> result=changeTransaction(s,id,row->{
            if("APPLIED".equals(row.get("status")))return view(p,row,true);
            version(row,request);if(!Set.of("READY","REVIEW").contains(text(row.get("status"))))throw bad("此暂存单不可确认，请修正或重新预览");
            var payload=decode(row.get("payload_json"));String group=text(row.get("group_id")),shop=text(row.get("shop_id")),type=text(row.get("type")),target=text(row.get("target_id"));
            try{state.lock(s,group,shop);}catch(com.yingdao.aicommerce.common.exception.BusinessException e){markStale(s,id);return Map.of("stale",true);}
            if(!Objects.equals(row.get("baseline_hash"),state.fingerprint(s,group,shop,payload))){markStale(s,id);return Map.of("stale",true);}
            var validation=decode(row.get("validation_json"));if(Boolean.TRUE.equals(validation.get("blocked")))throw bad("硬性错误不可忽略");if(Boolean.TRUE.equals(validation.get("needsMetadata")))throw bad("请先补充单位、编号类型或SPU统一成本说明");
            boolean independent=bool(row.get("requires_independent_review"));if(independent&&(p.userId().equals(row.get("submitted_by"))||p.userId().equals(row.get("last_edited_by"))))throw bad("此操作需要另一位有财务管理权限的成员复核");
            if(!Boolean.TRUE.equals(request.get("acknowledgeImpact")))throw bad("请确认实际适用范围");
            var impact=decode(row.get("impact_json"));String scopeShop=Set.of("MAPPING","SETTINGS").contains(type)||"UNDO".equals(type)&&Set.of("MAPPING","SETTINGS").contains(text(payload.get("originalType")))?shop:"";
            var freshImpact=state.impact(s,group,scopeShop);
            if(number(freshImpact.get("scopeOrderCount"))!=number(impact.get("scopeOrderCount"))){markStale(s,id);return Map.of("stale",true);}
            if(Boolean.TRUE.equals(impact.get("hasHistorical"))&&!Boolean.TRUE.equals(request.get("acknowledgeHistorical")))throw bad("请单独确认会影响适用历史订单");
            String reason=FinanceCostWorkspace.required(request,"reason",2000),evidence=FinanceCostWorkspace.required(request,"evidenceRef",2000);
            if(Boolean.TRUE.equals(validation.get("priceOverride"))&&!Boolean.TRUE.equals(request.get("revision")))throw bad("覆盖已有价格须声明修订意图并提供依据");
            Map<String,Object> applied;
            if(type.equals("UNDO")){
                var original=row(s,text(payload.get("originalId")),true,true);var before=decode(original.get("state_json"));var originalResult=decode(original.get("result_json"));
                state.restore(s,p.userId(),group,shop,text(original.get("target_id")),before,map(originalResult.get("afterState")));applied=Map.of();
            }else{
                if(!type.equals("IMPORT"))validate(s,type,group,shop,target,payload);
                applied=apply(s,p.userId(),type,group,shop,target,payload,row);
                if(Set.of("IMPORT","MANUAL_CREATE").contains(type))target=text(applied.get("id"));
            }
            var after=type.equals("UNDO")?Map.<String,Object>of():state.capture(s,type,group,shop,target,payload);
            var saved=new LinkedHashMap<>(applied);saved.put("afterState",after);
            repo.jdbc().update("UPDATE finance_cost_input_changes SET status='APPLIED',target_id=?,result_json=?,after_hash=?,confirmed_by=?,reason=?,evidence_ref=?,version=version+1,updated_at=UTC_TIMESTAMP(6),applied_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND id=?",target,repo.write(saved),state.fingerprint(s,group,shop,payload),p.userId(),reason,evidence,s.organizationId(),id);
            return view(p,row(s,id,false,false),true);
        });
        if(Boolean.TRUE.equals(result.get("stale")))throw bad("有效配置或已入库范围已变化，旧预览已过期，请重新预览");return result;
    }
    private Map<String,Object> apply(Scope s,String actor,String type,String group,String shop,String target,Map<String,Object> payload,Map<String,Object> row){
        switch(type){
            case "IMPORT":{
                byte[] bytes=originalBytes(s,row);
                if(!state.digest(semanticPayload(Map.of("costs",parse(bytes,"input"+text(payload.get("fileFormat")))))).equals(state.digest(semanticPayload(payload))))throw bad("暂存文件与规范化资料不一致，请重新上传");
                if(!target.isBlank())workspace.removeSource(s,actor,group,target);
                return workspace.importCosts(s,actor,group,bytes,text(row.get("source_name")),normalizedCosts(payload));
            }
            case "REMOVE_SOURCE":workspace.removeSource(s,actor,group,target);break;
            case "MANUAL_CREATE":return workspace.manualMapping(s,actor,shop,payload);
            case "MANUAL_UPDATE":workspace.changeManual(s,actor,group,target,payload,false);break;
            case "MANUAL_DELETE":workspace.changeManual(s,actor,group,target,payload,true);break;
            case "MAPPING":workspace.mapping(s,actor,shop,payload);break;
            case "SETTINGS":workspace.settings(s,actor,shop,payload);break;
            default:throw bad("未知的成本操作");
        }
        return Map.of();
    }
    private byte[] originalBytes(Scope s,Map<String,Object> row){
        byte[] bytes=(byte[])row.get("original_bytes");
        if(bytes==null)bytes=repo.jdbc().queryForObject("SELECT original_bytes FROM finance_cost_input_changes WHERE organization_id=? AND id=?",byte[].class,s.organizationId(),row.get("original_change_id"));
        if(bytes==null||!hash(Base64.getEncoder().encodeToString(bytes)).equals(row.get("original_hash")))throw bad("原始文件校验失败，请重新上传");return bytes;
    }
    private boolean noChange(Scope s,String type,String group,String shop,String target,Map<String,Object> payload){
        if(!Set.of("SETTINGS","MAPPING","MANUAL_UPDATE").contains(type))return false;
        var current=state.capture(s,type,group,shop,target,payload);Map<String,Object> previous,desired;
        if(type.equals("SETTINGS")){previous=decode(map(current.get("settings")).get("data_json"));var rules=new LinkedHashMap<>(map(payload.get("rules")));rules.put("currency","CNY");desired=Map.of("warehouse",text(payload.get("warehouse")),"rules",rules);}
        else if(type.equals("MAPPING")){previous=decode(map(current.get("mapping")).get("data_json"));desired=new LinkedHashMap<>(Map.of("warehouse",payload.get("warehouse"),"code",payload.get("code"),"factor",decimal(payload.get("factor")),"groupId",group));if(!text(payload.get("manualCostId")).isBlank())desired.put("manualCostId",payload.get("manualCostId"));}
        else{var old=map(current.get("manual"));if(old.isEmpty())return false;previous=Map.of("unit",old.get("unit"),"price",old.get("unit_price_cny"));desired=Map.of("unit",payload.get("unit"),"price",decimal(payload.get("unitPriceCny")));}
        return state.digest(previous).equals(state.digest(desired));
    }
    private List<Map<String,Object>> normalizedCosts(Map<String,Object> payload){
        var merged=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:rows(payload.get("costs"))){if(!(row.get("sku") instanceof String))throw bad("暂存编号类型不安全，请重新上传原始资料");String sku=text(row.get("sku"));var target=merged.computeIfAbsent(row.get("warehouse")+"\0"+sku,k->new LinkedHashMap<>(Map.of("sku",sku,"warehouse",row.get("warehouse"),"prices",new TreeMap<String,Object>())));for(var price:map(row.get("prices")).entrySet())map(target.get("prices")).put(price.getKey(),decimal(price.getValue()));}
        return new ArrayList<>(merged.values());
    }
    public Map<String,Object> revise(PlatformPrincipal p,String id,Map<String,Object> request){Scope s=manager(p);return changeTransaction(s,id,row->{
        version(row,request);owner(p,row);if(!OPEN.contains(text(row.get("status"))))throw bad("此暂存单不可补充资料，请重新提交");
        String group=text(row.get("group_id")),shop=text(row.get("shop_id")),type=text(row.get("type")),target=text(row.get("target_id"));state.lock(s,group,shop);
        var meta=metadata(map(request.get("metadata")));var payload=decode(row.get("payload_json"));
        var current=state.capture(s,type,group,shop,target,payload);
        if(!type.equals("UNDO")&&payload.containsKey("version")){String key=type.equals("SETTINGS")?"settings":type.equals("MAPPING")||type.equals("MANUAL_CREATE")?"mapping":"manual";payload.put("version",number(map(current.get(key)).get("version")));}
        Map<String,Object> preview;
        if(type.equals("UNDO")){
            var original=row(s,text(payload.get("originalId")),true,true);
            if(!Objects.equals(original.get("after_hash"),state.fingerprint(s,group,shop,payload)))throw bad("之后已有其他修改，撤销单不能重新绑定新配置，请重新预览所需操作");
            preview=reversePreview(s,group,shop,original);
        }else{
            if(!type.equals("IMPORT"))validate(s,type,group,shop,target,payload);
            preview=preview(s,type,group,shop,target,payload,meta,text(row.get("source_name")));
        }
        repo.jdbc().update("UPDATE finance_cost_input_changes SET semantic_hash=?,payload_json=?,metadata_json=?,validation_json=?,diff_json=?,impact_json=?,state_json=?,baseline_hash=?,requires_independent_review=?,status=?,last_edited_by=?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND id=?",state.digest(Map.of("type",type,"target",target,"shop",shop,"payload",semanticPayload(payload),"metadata",meta)),repo.write(payload),repo.write(meta),repo.write(preview.get("validation")),repo.write(previewData(preview)),repo.write(preview.get("impact")),repo.write(current),state.fingerprint(s,group,shop,payload),preview.get("requiresIndependentReview"),preview.get("status"),p.userId(),s.organizationId(),id);
        return view(p,row(s,id,false,false),true);
    });}
    public Map<String,Object> reject(PlatformPrincipal p,String id,Map<String,Object> request){Scope s=manager(p);return changeTransaction(s,id,row->{
        version(row,request);owner(p,row);if(!OPEN.contains(text(row.get("status"))))throw bad("此暂存单不可驳回");
        repo.jdbc().update("UPDATE finance_cost_input_changes SET status='REJECTED',reason=?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND id=?",FinanceCostWorkspace.required(request,"reason",2000),s.organizationId(),id);return view(p,row(s,id,false,false),true);
    });}
    public Map<String,Object> undo(PlatformPrincipal p,String id,Map<String,Object> request){Scope s=manager(p);return changeTransaction(s,id,row->{
        version(row,request);if(!"APPLIED".equals(row.get("status"))||"UNDO".equals(row.get("type")))throw bad("此操作不可撤销");
        String group=text(row.get("group_id")),shop=text(row.get("shop_id"));state.lock(s,group,shop);var original=decode(row.get("payload_json"));
        if(!Objects.equals(row.get("after_hash"),state.fingerprint(s,group,shop,original)))throw bad("之后已有其他修改，无法唯一反向恢复；请重新预览所需操作");
        String reason=FinanceCostWorkspace.required(request,"reason",2000),evidence=FinanceCostWorkspace.required(request,"evidenceRef",2000);
        var payload=new LinkedHashMap<>(original);payload.put("originalId",id);payload.put("originalType",row.get("type"));
        var preview=reversePreview(s,group,shop,row);
        return insert(s,p.userId(),"UNDO",group,shop,text(row.get("target_id")),payload,Map.of("reason",reason,"evidenceRef",evidence),null,text(row.get("source_name")),state.digest(Map.of("undo",id)),state.fingerprint(s,group,shop,payload),preview,Map.of(),id);
    });}
    private Map<String,Object> reversePreview(Scope s,String group,String shop,Map<String,Object> row){
        var impact=state.impact(s,group,Set.of("MAPPING","SETTINGS").contains(text(row.get("type")))?shop:"");
        var diffs=new ArrayList<Map<String,Object>>();
        for(var old:rows(decode(row.get("diff_json")).get("items"))){
            var reversed=new LinkedHashMap<>(old);
            if(old.containsKey("oldPrice")){
                reversed.put("oldPrice",old.get("newPrice"));reversed.put("newPrice",old.get("oldPrice"));
                reversed.put("oldSource",old.get("newSource"));reversed.put("newSource",old.get("oldSource"));
            }else{reversed.put("before",old.get("after"));reversed.put("after",old.get("before"));}
            diffs.add(reversed);
        }
        return Map.<String,Object>of("validation",Map.of("blocked",false,"risk","HIGH","issues",List.of(issue("UNDO_REVIEW","REVIEW","撤销会恢复该操作前配置，须独立复核"))),"impact",impact,"diff",diffs,"requiresIndependentReview",true,"status","REVIEW");
    }
    private static String boundedParseReason(String message){String safe=text(message).replaceAll("[\\p{Cntrl}]"," ");return safe.substring(0,Math.min(300,safe.length()));}
    private static void boundedNumber(Object value){var amount=decimal(value);if(amount==null||(long)amount.precision()-amount.scale()>18||amount.scale()>6)throw bad("金额或换算数量超出范围，最多6位小数");}
    private void owner(PlatformPrincipal p,Map<String,Object> row){if(!p.userId().equals(row.get("submitted_by")))throw bad("仅提交者可以补充资料或驳回");}
    private void version(Map<String,Object> row,Map<String,Object> request){if(!request.containsKey("version")||number(row.get("version"))!=number(request.get("version")))throw bad("暂存单版本已变化，请刷新");}
    private void markStale(Scope s,String id){repo.jdbc().update("UPDATE finance_cost_input_changes SET status='STALE',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND id=?",s.organizationId(),id);}
    private Map<String,Object> decode(Object value){return value==null||text(value).isBlank()?new LinkedHashMap<>():new LinkedHashMap<>(workspace.decode(value));}
    private static boolean bool(Object value){return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()!=0;}
}
