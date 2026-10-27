package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Internal expense accounting only: no marketplace writes, payments or changes to platform facts. */
@Service
public class FinanceOrderCharges {
    final FinanceRepository repo; final FinanceService service;
    @org.springframework.beans.factory.annotation.Autowired
    public FinanceOrderCharges(FinanceRepository repo,FinanceService service){this.repo=repo;this.service=service;}
    FinanceOrderCharges(FinanceRepository repo){this(repo,null);}
    static BigDecimal price(Object v){
        BigDecimal n=decimal(v);
        if(n==null||n.signum()<0||n.scale()>6||n.precision()-n.scale()>18)throw bad("金额需为非负数，最多 6 位小数");
        return n;
    }
    static String cell(List<String> r,int i){return FinanceCostWorkspace.cell(r,i);}
    static int column(List<String> headers,String...names){
        for(int i=0;i<headers.size();i++)if(Set.of(names).contains(headers.get(i).trim()))return i;
        return -1;
    }
    /** Keeps positional source cells, including duplicate date headers and error cells. */
    static List<Map<String,Object>> parse(byte[] bytes,String name){
        var rows=FinanceWorkbook.readCostTable(bytes,name);
        if(rows.size()<2||rows.size()>2001)throw bad("请上传含 1～2000 条记录的 Excel 或 CSV");
        var h=rows.getFirst();
        int order=column(h,"订单编号","订单号","order_id"),shop=column(h,"店铺","店铺名称"),
            fee=column(h,"代刷费CNY","服务费CNY","服务费（人民币）"),
            principal=column(h,"实际订单金额","订单金额(平台显示)","订单原币金额");
        if(order<0||shop<0||fee<0||principal<0)throw bad("需要订单编号、店铺、代刷费CNY（或服务费CNY）、实际订单金额列");
        var out=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
        for(int i=1;i<rows.size();i++){
            var r=rows.get(i);if(r.stream().allMatch(String::isBlank))continue;
            String id=cell(r,order),hint=cell(r,shop);
            if(id.isBlank()||id.length()>255||!id.matches("[A-Za-z0-9_-]+")||hint.isBlank()||hint.length()>255)
                throw bad("第 "+(i+1)+" 行订单编号或店铺不正确；订单号请保留文本格式");
            if(!seen.add(hint+"\0"+id))throw bad("第 "+(i+1)+" 行存在重复店铺订单，请先核对");
            var line=new LinkedHashMap<String,Object>();
            line.put("rowNumber",i+1);line.put("orderId",id);line.put("shopHint",hint);
            try{line.put("principalNative",price(cell(r,principal).replace(",","")));line.put("serviceFeeCny",price(cell(r,fee).replace(",","")));}
            catch(RuntimeException e){throw bad("第 "+(i+1)+" 行本金或人民币服务费不正确");}
            line.put("headers",h);line.put("cells",r);
            line.put("dateWarning",java.util.stream.IntStream.range(0,h.size()).anyMatch(c->h.get(c).contains("结算月份")&&cell(r,c).startsWith("#")));
            out.add(line);
        }
        if(out.isEmpty())throw bad("没有识别到费用记录");
        return out;
    }
    Map<String,Object> decode(Object v){return new FinanceOrderCosts(repo).decode(v);}
    static String normal(String s){return s.toLowerCase(Locale.ROOT).replaceAll("\\s+","").trim();}
    Map<String,Object> match(String org,String group,String id,String hint,BigDecimal principal){
        var candidates=repo.jdbc().queryForList("""
            SELECT r.id,r.shop_id,r.order_id,r.currency,r.amount,c.name,c.display_name,c.platform,c.region
            FROM finance_records r JOIN finance_connections c ON c.id=r.shop_id AND c.organization_id=r.organization_id
            WHERE r.organization_id=? AND c.group_id=? AND c.status<>'REMOVED'
              AND r.kind='ORDER' AND r.source='API' AND r.order_id=? LIMIT 10
            """,org,group,id).stream().filter(r->id.equals(text(r.get("order_id")))).toList();
        if(candidates.isEmpty()){
            var elsewhere=repo.jdbc().queryForList("""
                SELECT DISTINCT g.name FROM finance_connections c
                JOIN finance_groups g ON g.organization_id=c.organization_id AND g.id=c.group_id
                JOIN finance_records r ON r.organization_id=c.organization_id AND r.shop_id=c.id
                  AND r.kind='ORDER' AND r.source='API' AND r.order_id=?
                WHERE c.organization_id=? AND c.status<>'REMOVED' AND c.group_id<>? LIMIT 5
                """,id,org,group);
            if(!elsewhere.isEmpty())return Map.of("matchStatus","REVIEW","matchNote",
                "订单在其他分组（"+String.join("、",elsewhere.stream().map(r->text(r.get("name"))).toList())+"），请在对应分组导入");
            return Map.of("matchStatus","UNMATCHED","matchNote","尚未找到平台订单；请核对来源店铺授权、订单编号及历史同步，已保留来源");
        }
        // Exact store identity hints narrow candidates; no fuzzy name or buyer-account inference.
        var named=candidates.stream().filter(r->normal(hint).equals(normal(text(r.get("name"))))||normal(hint).equals(normal(text(r.get("display_name"))))).toList();
        if(named.size()!=1)return Map.of("matchStatus","REVIEW","matchNote",named.isEmpty()?"店铺名称与平台不一致，请核对原表":"存在多个同号同名订单，请核对");
        var row=named.getFirst();
        if(decimal(row.get("amount"))==null||decimal(row.get("amount")).compareTo(principal)!=0)
            return Map.of("matchStatus","REVIEW","matchNote","原表本金与平台订单金额不一致，请核对");
        var result=new LinkedHashMap<String,Object>(row);
        result.put("matchStatus","MATCHED");result.put("matchNote","订单编号、店铺和原币金额一致");
        return result;
    }
    public Map<String,Object> preview(Scope s,String group,byte[] bytes,String name){
        new FinanceGroups(repo).require(s,group,false);
        var rows=parse(bytes,name);
        for(var r:rows)r.put("match",match(s.organizationId(),group,text(r.get("orderId")),text(r.get("shopHint")),decimal(r.get("principalNative"))));
        return Map.of("rows",rows,"count",rows.size(),"matched",rows.stream().filter(r->"MATCHED".equals(map(r.get("match")).get("matchStatus"))).count(),
            "serviceFeeCny",rows.stream().map(r->decimal(r.get("serviceFeeCny"))).reduce(BigDecimal.ZERO,BigDecimal::add));
    }
    void audit(Scope s,String actor,String type,String id,Object before,Object after,String reason){
        new FinanceCostWorkspace(repo,service).audit(s,actor,type,id,before,after,reason);
    }
    void invalidate(Scope s){if(service!=null)service.invalidateAfterCommit(s);}
    @Transactional public Map<String,Object> upload(Scope s,String actor,String group,byte[] bytes,String name){
        new FinanceGroups(repo).require(s,group,true);
        String digest=hash(Base64.getEncoder().encodeToString(bytes));
        var old=repo.jdbc().queryForList("SELECT id FROM finance_charge_imports WHERE organization_id=? AND group_id=? AND content_hash=?",s.organizationId(),group,digest);
        if(!old.isEmpty())return Map.of("duplicate",true,"id",old.getFirst().get("id"));
        var rows=parse(bytes,name);String id=UUID.randomUUID().toString();
        repo.jdbc().update("INSERT INTO finance_charge_imports(id,organization_id,group_id,source_name,content_hash,row_count,created_by) VALUES(?,?,?,?,?,?,?)",
            id,s.organizationId(),group,name.substring(0,Math.min(250,name.length())),digest,rows.size(),actor);
        repo.jdbc().batchUpdate("""
            INSERT INTO finance_charge_rows(id,organization_id,group_id,import_id,source_row,order_id,shop_hint,principal_native,service_fee_cny,data_json)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """,rows.stream().map(r->new Object[]{UUID.randomUUID().toString(),s.organizationId(),group,id,r.get("rowNumber"),r.get("orderId"),
                r.get("shopHint"),r.get("principalNative"),r.get("serviceFeeCny"),repo.write(r)}).toList());
        audit(s,actor,"ORDER_CHARGE_IMPORT",id,null,Map.of("file",name,"rows",rows.size(),"groupId",group),"导入费用来源；唯一匹配订单标为刷单并排除经营汇总");
        refreshSources(s,group);invalidate(s);
        return Map.of("duplicate",false,"id",id,"rows",rows.size());
    }
    @Transactional public void remove(Scope s,String actor,String group,String id){
        new FinanceGroups(repo).require(s,group,true);
        var old=repo.jdbc().queryForList("SELECT * FROM finance_charge_imports WHERE organization_id=? AND group_id=? AND id=?",s.organizationId(),group,id);
        if(old.isEmpty())throw bad("文件已移除或不属于当前分组");
        audit(s,actor,"ORDER_CHARGE_IMPORT_DELETE",id,null,Map.of("groupId",group,"file",old.getFirst().get("source_name")),"移除来源，按剩余有效来源重新核算");
        var affected=repo.jdbc().queryForList("SELECT DISTINCT matched_shop_id,order_id FROM finance_charge_rows WHERE organization_id=? AND import_id=? AND matched_shop_id IS NOT NULL",s.organizationId(),id);
        repo.jdbc().update("DELETE FROM finance_charge_rows WHERE organization_id=? AND import_id=?",s.organizationId(),id);
        repo.jdbc().update("DELETE FROM finance_charge_imports WHERE organization_id=? AND id=?",s.organizationId(),id);
        affected.forEach(r->rebuildMarker(s.organizationId(),text(r.get("matched_shop_id")),text(r.get("order_id"))));invalidate(s);
    }
    void enqueueGroup(Scope s,String group){new FinanceOrderCosts(repo).requestLabelGroup(s,group);}
    Map<String,Object> labelConfiguration(Scope s,List<Map<String,Object>> shops,List<Map<String,Object>> costShops){
        var rules=repo.jdbc().queryForList("SELECT group_id,country FROM finance_label_rules WHERE organization_id=?",s.organizationId());
        var applicable=new HashSet<String>();
        for(var rule:rules)applicable.add(text(rule.get("group_id"))+":"+text(rule.get("country")));
        var wanted=new HashSet<String>();
        for(var cost:costShops)if(number(cost.get("order_count"))>number(cost.get("excluded_count")))wanted.add(text(cost.get("shop_id")));
        var missing=new ArrayList<Map<String,Object>>();int configured=0;
        for(var shop:shops)if(wanted.contains(text(shop.get("id")))){
            String group=text(shop.get("group_id")),country=text(shop.get("region"));
            if(applicable.contains(group+":")||applicable.contains(group+":"+country))configured++;
            else missing.add(Map.of("shopId",text(shop.get("id")),"name",first(shop,"display_name","name"),
                "groupId",group,"region",country));
        }
        return Map.of("configuredShops",configured,"missingShops",missing);
    }
    @Transactional public void saveRule(Scope s,String actor,Map<String,Object> b){
        String group=text(b.get("groupId"));new FinanceGroups(repo).require(s,group,true);
        String country=text(b.get("country"));
        if(!Set.of("","PH","TH","MY","VN","SG","ID","US","GB").contains(country))throw bad("请选择支持的店铺国家或分组默认");
        BigDecimal value=price(b.get("unitPriceCny"));
        String id=text(b.get("id"));Map<String,Object> old=null;
        if(id.isBlank()){
            if(!repo.jdbc().queryForList("SELECT id FROM finance_label_rules WHERE organization_id=? AND group_id=? AND country=?",s.organizationId(),group,country).isEmpty())
                throw bad("该分组和国家已有贴单单价，请调整现有规则");
            // Retained as historical configuration metadata; it no longer gates order eligibility.
            LocalDate date=text(b.get("effectiveDate")).isBlank()?LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")):FinanceCostWorkspace.day(b.get("effectiveDate"));
            id=UUID.randomUUID().toString();
            repo.jdbc().update("INSERT INTO finance_label_rules(id,organization_id,group_id,country,effective_date,unit_price_cny,created_by) VALUES(?,?,?,?,?,?,?)",id,s.organizationId(),group,country,date,value,actor);
        }else{
            var rows=repo.jdbc().queryForList("SELECT * FROM finance_label_rules WHERE organization_id=? AND group_id=? AND id=? FOR UPDATE",s.organizationId(),group,id);
            if(rows.isEmpty()||number(rows.getFirst().get("version"))!=number(b.get("version")))throw bad("规则已更新，请刷新后再修改");
            old=rows.getFirst();
            if(!country.equals(text(old.get("country"))))throw bad("修改单价时不能改变国家，请新增对应国家的规则");
            repo.jdbc().update("UPDATE finance_label_rules SET unit_price_cny=?,version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=?",value,id);
        }
        audit(s,actor,"LABEL_RULE",id,old,b,"保存当前人民币贴单单价；按查询范围内首次平台结算的适用订单统计");enqueueGroup(s,group);invalidate(s);
    }
    @Transactional public void removeRule(Scope s,String actor,String group,String id,long version){
        new FinanceGroups(repo).require(s,group,true);
        if(repo.jdbc().update("DELETE FROM finance_label_rules WHERE organization_id=? AND group_id=? AND id=? AND version=?",s.organizationId(),group,id,version)!=1)
            throw bad("规则已更新或删除，请刷新");
        audit(s,actor,"LABEL_RULE_DELETE",id,null,Map.of("groupId",group,"version",version),"移除规则并重算受影响订单");enqueueGroup(s,group);invalidate(s);
    }
    public Map<String,Object> workspace(Scope s,String group,int page){
        return workspace(s,group,page,null);
    }
    public Map<String,Object> workspace(Scope s,String group,int page,Filter filter){
        new FinanceGroups(repo).require(s,group,false);
        var imports=repo.jdbc().queryForList("SELECT id,source_name,row_count,created_at FROM finance_charge_imports WHERE organization_id=? AND group_id=? ORDER BY created_at DESC,id",s.organizationId(),group);
        var rules=repo.jdbc().queryForList("SELECT * FROM finance_label_rules WHERE organization_id=? AND group_id=? ORDER BY country,updated_at DESC,id",s.organizationId(),group);
        var activeCountries=new HashSet<String>();
        for(var rule:rules)rule.put("active",activeCountries.add(text(rule.get("country"))));
        var count=repo.jdbc().queryForMap("SELECT COUNT(*) records,COALESCE(SUM(matched_shop_id IS NULL),0) unmatched,COALESCE(SUM(service_fee_cny),0) service_fee_cny FROM finance_charge_rows WHERE organization_id=? AND group_id=?",s.organizationId(),group);
        var rows=repo.jdbc().queryForList("SELECT * FROM finance_charge_rows WHERE organization_id=? AND group_id=? ORDER BY import_id,source_row LIMIT 50 OFFSET ?",s.organizationId(),group,(Math.max(1,page)-1L)*50);
        for(var r:rows){
            var m=matchedRow(s.organizationId(),r);
            r.put("match",m);r.put("source",decode(r.remove("data_json")));
            if("MATCHED".equals(m.get("matchStatus"))){
                var marker=repo.jdbc().queryForList("SELECT service_fee_cny,source_count FROM finance_marketing_orders WHERE organization_id=? AND shop_id=? AND order_id=?",s.organizationId(),m.get("shop_id"),r.get("order_id"));
                r.put("marker",marker.isEmpty()?Map.of():marker.getFirst());
                if(marker.isEmpty())r.put("match",Map.of("matchStatus","REVIEW","matchNote","已找到对应订单，点击重新匹配后计入统计"));
            }
        }
        var marked=repo.jdbc().queryForMap("SELECT COUNT(*) orders,COALESCE(SUM(m.service_fee_cny),0) service_fee_cny,COALESCE(SUM(m.service_fee_cny IS NULL),0) conflicts FROM finance_marketing_orders m JOIN finance_connections c ON c.organization_id=m.organization_id AND c.id=m.shop_id AND c.status<>'REMOVED' WHERE m.organization_id=? AND EXISTS(SELECT 1 FROM finance_charge_rows x WHERE x.organization_id=m.organization_id AND x.matched_shop_id=m.shop_id AND x.order_id=m.order_id AND x.group_id=?)",s.organizationId(),group);
        var result=new LinkedHashMap<String,Object>(Map.of("imports",imports,"rules",rules,"rows",rows,"totals",count,"marked",marked,"page",Math.max(1,page),"pageSize",50,"groupId",group));
        if(filter!=null){
            var scoped=new Filter(filter.start(),filter.end(),filter.shopId(),filter.platform(),"","day",group);
            var costs=new FinanceCostReport(repo).summary(s,scoped,"SETTLED_ORDER");
            result.put("labelSummary",costs);
            var q=repo.query(s,scoped,"r");
            var period=repo.jdbc().queryForMap("""
                SELECT COUNT(*) orders,COALESCE(SUM(m.service_fee_cny),0) service_fee_cny,
                  COALESCE(SUM(m.service_fee_cny IS NULL),0) conflicts
                FROM finance_marketing_orders m JOIN finance_records r FORCE INDEX(idx_finance_marketing_order)
                  ON r.organization_id=m.organization_id AND r.shop_id=m.shop_id AND r.order_id=m.order_id
                WHERE
                """+FinanceCostReport.firstSettlementIdentity("r",true)+" AND "+q.where(),q.args().toArray());
            result.put("periodMarked",period);
            result.put("filters",Map.of("start",scoped.start(),"end",scoped.end(),"shopId",scoped.shopId(),"platform",scoped.platform(),"groupId",group));
        }
        return result;
    }
    @Transactional public void rematch(Scope s,String actor,String group){
        new FinanceGroups(repo).require(s,group,true);
        refreshSources(s,group);
        audit(s,actor,"ORDER_CHARGE_REMATCH",group,null,Map.of("groupId",group),"重新匹配当前分组费用来源；不跨分组、不重复累计服务费");
        invalidate(s);
    }
    Map<String,Object> matchedRow(String org,Map<String,Object> r){
        String pinned=text(r.get("matched_shop_id"));
        if(!pinned.isBlank()){
            var found=repo.jdbc().queryForList("SELECT r.id,r.shop_id,r.currency,c.platform,c.region FROM finance_records r JOIN finance_connections c ON c.id=r.shop_id AND c.organization_id=r.organization_id WHERE r.organization_id=? AND r.shop_id=? AND r.order_id=? AND r.kind='ORDER' AND r.source='API' AND c.status<>'REMOVED' LIMIT 2",org,pinned,r.get("order_id"));
            if(found.size()==1){var m=new LinkedHashMap<>(found.getFirst());m.put("matchStatus","MATCHED");m.put("matchNote","已关联，按店铺唯一标识保留");return m;}
        }
        return match(org,text(r.get("group_id")),text(r.get("order_id")),text(r.get("shop_hint")),decimal(r.get("principal_native")));
    }
    void refreshSources(Scope s,String group){
        for(var row:repo.jdbc().queryForList("SELECT * FROM finance_charge_rows WHERE organization_id=? AND group_id=? AND matched_shop_id IS NULL",s.organizationId(),group))matchAndSave(s.organizationId(),row);
    }
    void matchAndSave(String org,Map<String,Object> row){
        var m=matchedRow(org,row);if(!"MATCHED".equals(m.get("matchStatus")))return;
        String shop=text(m.get("shop_id"));if(!repo.writableShop(org,shop))return;
        if(repo.jdbc().update("UPDATE finance_charge_rows SET matched_shop_id=? WHERE organization_id=? AND id=?",shop,org,row.get("id"))!=1)return;
        rebuildMarker(org,shop,text(row.get("order_id")));
    }
    void rebuildMarker(String org,String shop,String order){
        var current=repo.jdbc().queryForList("SELECT service_fee_cny FROM finance_charge_rows WHERE organization_id=? AND matched_shop_id=? AND order_id=? FOR UPDATE",org,shop,order);
        var totals=new LinkedHashMap<String,Object>();totals.put("n",current.size());
        totals.put("low",current.stream().map(r->decimal(r.get("service_fee_cny"))).min(BigDecimal::compareTo).orElse(null));
        totals.put("high",current.stream().map(r->decimal(r.get("service_fee_cny"))).max(BigDecimal::compareTo).orElse(null));
        if(number(totals.get("n"))==0){repo.jdbc().update("DELETE FROM finance_marketing_orders WHERE organization_id=? AND shop_id=? AND order_id=?",org,shop,order);return;}
        BigDecimal fee=decimal(totals.get("low")).compareTo(decimal(totals.get("high")))==0?decimal(totals.get("low")):null;
        repo.jdbc().update("INSERT INTO finance_marketing_orders(organization_id,shop_id,order_id,service_fee_cny,source_count) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE service_fee_cny=VALUES(service_fee_cny),source_count=VALUES(source_count)",org,shop,order,fee,totals.get("n"));
    }
    void orderArrived(Scope s,Entry e){
        if(!"ORDER".equals(e.kind())||!"API".equals(e.source()))return;
        var rows=repo.jdbc().queryForList("SELECT x.* FROM finance_charge_rows x JOIN finance_connections c ON c.organization_id=x.organization_id AND c.group_id=x.group_id WHERE c.id=? AND x.organization_id=? AND x.order_id=? AND x.matched_shop_id IS NULL",e.shopId(),s.organizationId(),e.orderId());
        for(var r:rows){new FinanceGroups(repo).require(s,text(r.get("group_id")),true);matchAndSave(s.organizationId(),r);}
    }
    static String normalOrder(String alias){
        return "NOT EXISTS(SELECT 1 FROM finance_marketing_orders mark WHERE mark.organization_id="+alias+".organization_id AND mark.shop_id="+alias+".shop_id AND mark.order_id="+alias+".order_id)";
    }
    void attach(Scope s,List<Map<String,Object>> records){
        for(int from=0;from<records.size();from+=100){
            var batch=records.subList(from,Math.min(records.size(),from+100));
            var ids=batch.stream().map(r->text(r.get("id"))).filter(v->!v.isBlank()).toList();if(ids.isEmpty())continue;
            var args=new ArrayList<Object>();args.add(s.organizationId());args.addAll(ids);
            var marked=new HashSet<>(repo.jdbc().queryForList("SELECT r.id FROM finance_records r JOIN finance_marketing_orders m ON m.organization_id=r.organization_id AND m.shop_id=r.shop_id AND m.order_id=r.order_id WHERE r.organization_id=? AND r.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",String.class,args.toArray()));
            for(var r:batch)if(marked.contains(text(r.get("id")))){r.put("marketing_order",true);r.put("marketing_label","刷单");r.put("summary_inclusion","不计入经营汇总，原始账目保留");}
        }
    }
    Map<String,Object> label(String org,Map<String,Object> order,Map<String,Object> raw,FinanceOrderCosts.Context ctx,Map<String,Object> standard){
        var rule=labelRule(org,ctx);
        boolean independent=!rule.isEmpty()&&repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_records WHERE organization_id=? AND shop_id=? AND order_id=? AND kind='COST'",Long.class,org,ctx.shopId(),order.get("order_id"))>0;
        return label(order,raw,standard,rule,independent);
    }
    Map<String,Object> labelRule(String org,FinanceOrderCosts.Context ctx){
        var rules=repo.jdbc().queryForList("SELECT * FROM finance_label_rules WHERE organization_id=? AND group_id=? AND (country='' OR country=?) ORDER BY (country<>'') DESC,updated_at DESC,id LIMIT 1",org,ctx.groupId(),ctx.region());
        return rules.isEmpty()?Map.of():rules.getFirst();
    }
    static Map<String,Object> label(Map<String,Object> order,Map<String,Object> raw,Map<String,Object> standard,Map<String,Object> rule,boolean independent){
        var out=new LinkedHashMap<>(standard);BigDecimal value=BigDecimal.ZERO;String note="未启用贴单规则";int pending=0;
        out.remove("labelRuleId");out.remove("labelRuleVersion");
        boolean shipped=FinanceOrderCosts.shippingTime(raw)!=null||Set.of("COMPLETED","DELIVERED","SHIPPED","TO_CONFIRM_RECEIVE","IN_TRANSIT").contains(text(order.get("status")));
        boolean cancelled=Set.of("CANCELLED","CANCELED","UNPAID").contains(text(order.get("status")));
        if(shipped&&!cancelled){
            if(!rule.isEmpty()){
                value=decimal(rule.get("unit_price_cny"));out.put("labelRuleId",rule.get("id"));out.put("labelRuleVersion",rule.get("version"));note="人民币 / 每笔已发货订单；按当前分组及国家单价，查询范围内首次结算计入一次，不限制下单日期";
                if(independent){value=BigDecimal.ZERO;pending=1;note="该订单已有独立成本记录，贴单费暂不重复计入，请核对原有费用";}
            }
        }else note=cancelled?"未付款或取消订单，不计贴单费":"未发货，不计贴单费";
        out.put("labelCostCny",value);out.put("labelNote",note);out.put("chargesPending",pending);return out;
    }
}
