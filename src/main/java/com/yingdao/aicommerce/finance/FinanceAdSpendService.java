package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Service;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Service
public class FinanceAdSpendService {
    static final int MAX_RECORDS=50000;
    final FinanceRepository repo;final FinanceService finance;
    public FinanceAdSpendService(FinanceRepository repo,FinanceService finance){this.repo=repo;this.finance=finance;}
    List<Map<String,Object>> sources(Scope s,Filter f){
        var args=new ArrayList<Object>(List.of(s.organizationId(),f.start(),f.end()));
        String where="organization_id=? AND record_date BETWEEN ? AND ?";
        if(!f.platform().isBlank()){where+=" AND platform=?";args.add(f.platform());}
        if(!f.currency().isBlank()){where+=" AND currency=?";args.add(f.currency());}
        var result=new ArrayList<Map<String,Object>>();
        var accounts=new HashMap<String,String>();repo.jdbc().queryForList("SELECT advertiser_id,name FROM finance_ad_accounts WHERE organization_id=?",s.organizationId()).forEach(r->accounts.put(text(r.get("advertiser_id")),text(r.get("name"))));
        for(var record:repo.jdbc().queryForList("SELECT id,shop_id,platform,kind,record_date,currency,amount,source,raw_json FROM finance_records WHERE "+where+" AND kind IN ('AD','AD_GMV') AND (shop_id='UNASSIGNED' OR shop_id IN (SELECT id FROM finance_connections WHERE organization_id=? AND status<>'REMOVED')) LIMIT 50001",append(args,s.organizationId()))){
            var row=FinanceAdSpendReport.api(record,repo.read(text(record.get("raw_json"))));if(text(row.get("account_name")).isBlank())row.put("account_name",accounts.getOrDefault(text(row.get("account_id")),"账户信息未提供"));result.add(row);
        }
        for(var record:repo.jdbc().queryForList("SELECT * FROM finance_ad_spend_imports WHERE "+where+" LIMIT 50001",args.toArray())){
            var row=new LinkedHashMap<>(record);row.put("record_date",text(record.get("record_date")));row.put("source","IMPORT_FUNDING");row.put("shop_id","UNASSIGNED");result.add(row);
        }
        result.addAll(FinanceAdBcFunding.sources(repo,s,f));
        if(result.size()>MAX_RECORDS)throw bad("广告消耗超过 50,000 条，请缩小日期范围；没有发布截断汇总");return result;
    }
    static Object[] append(List<Object> values,Object extra){var all=new ArrayList<>(values);all.add(extra);return all.toArray();}
    List<Map<String,Object>> assignments(Scope s,Collection<String> sourceKeys){
        var result=new ArrayList<Map<String,Object>>();var keys=new ArrayList<>(new LinkedHashSet<>(sourceKeys));
        for(int i=0;i<keys.size();i+=500){var batch=keys.subList(i,Math.min(keys.size(),i+500));var args=new ArrayList<Object>();args.add(s.organizationId());args.addAll(batch);
        result.addAll(repo.jdbc().queryForList("""
            SELECT s.source_key,s.fingerprint,a.shop_id,a.amount,a.cash_amount,a.credit_amount,a.grant_amount
            FROM finance_ad_spend_allocation_sets s JOIN finance_ad_spend_allocations a ON a.allocation_set_id=s.id
            WHERE s.organization_id=? AND s.revision=(SELECT MAX(n.revision) FROM finance_ad_spend_allocation_sets n
              WHERE n.organization_id=s.organization_id AND n.source_key=s.source_key)
            """+" AND s.source_key IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",args.toArray()));}
        return result;
    }
    /** The authoritative source/assignment snapshot, before display currency conversion. */
    Map<String,Object> buildNativeSpendFacts(Scope s,Filter f){
        return repo.readSnapshot(30,()->{
            var shops=finance.shops(s);var sources=sources(s,f);var nativeData=new LinkedHashMap<String,Object>();
            nativeData.put("shops",shops);nativeData.put("sources",sources);nativeData.put("assignments",assignments(s,sources.stream().filter(r->!"API_BC_COST".equals(r.get("source"))).map(r->text(r.get("source_key"))).toList()));return nativeData;
        });
    }
    Map<String,Object> report(Scope s,Filter f,boolean cny,String fxSnapshot,String search,String status){
        // Financial rows/assignments share a DB snapshot; public rate fetching happens after it closes.
        return reportFromNative(buildNativeSpendFacts(s,f),f,cny,fxSnapshot,search,status);
    }
    Map<String,Object> reportFromNative(Map<String,Object> snapshot,Filter f,boolean cny,String fxSnapshot,String search,String status){
        var sources=rows(snapshot.get("sources"));
        var quote=cny?finance.exchangeRates().historical(fxSnapshot,sources.stream().map(FinanceFx::businessDate).filter(Objects::nonNull).toList()):null;
        var result=FinanceAdSpendReport.build(sources,rows(snapshot.get("assignments")),rows(snapshot.get("shops")),f,quote,search,status);
        result.put("currencies",sources.stream().map(r->text(r.get("currency"))).filter(v->!v.isBlank()).distinct().sorted().toList());
        result.put("fx",quote==null?null:quote.metadata());result.put("fxEvidence",quote==null?List.of():quote.evidence());result.put("filters",Map.of("currency",f.currency(),"displayCurrency",cny?"CNY":f.currency()));return result;
    }

    Map<String,Object> saveImport(Scope s,String actor,List<Map<String,Object>> entries,String filename){
        if(entries.size()>20000||filename==null||filename.length()>250)throw bad("报表大小或文件名不正确");
        return repo.transaction(()->{
            int inserted=0;var checked=new HashSet<String>();
            for(var r:entries){
                FinanceAdSpendReport.validateFunding(r);
                String bucket=text(r.get("platform"))+"\0"+r.get("record_date")+"\0"+r.get("currency");
                if(text(r.get("account_id")).startsWith("manual:")&&checked.add(bucket)&&repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_records WHERE organization_id=? AND platform=? AND record_date=? AND currency=? AND source='API' AND kind IN ('AD','AD_GMV')",Integer.class,s.organizationId(),r.get("platform"),r.get("record_date"),r.get("currency"))>0)throw bad("该期间已有 API 投放记录，请填写真实广告账户 ID 后核对重叠，不能以未知来源叠加");
            }
            for(int start=0;start<entries.size();start+=250){
                var batch=entries.subList(start,Math.min(entries.size(),start+250));var args=new ArrayList<Object>();
                for(var r:batch)Collections.addAll(args,s.organizationId(),r.get("source_key"),r.get("platform"),r.get("account_id"),r.get("account_name"),r.get("campaign_id"),r.get("campaign_name"),r.get("report_type"),r.get("record_date"),r.get("currency"),r.get("amount"),r.get("cash_amount"),r.get("credit_amount"),r.get("grant_amount"),repo.write(r),filename,actor);
                inserted+=repo.jdbc().update("""
                    INSERT IGNORE INTO finance_ad_spend_imports(organization_id,source_key,platform,account_id,account_name,campaign_id,campaign_name,
                    report_type,record_date,currency,amount,cash_amount,credit_amount,grant_amount,source_json,file_name,created_by)
                    VALUES
                    """+String.join(",",Collections.nCopies(batch.size(),"(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")),args.toArray());
                var keys=new ArrayList<Object>();keys.add(s.organizationId());batch.forEach(r->keys.add(r.get("source_key")));var saved=new HashMap<String,Map<String,Object>>();
                repo.jdbc().queryForList("SELECT source_key,currency,amount,cash_amount,credit_amount,grant_amount FROM finance_ad_spend_imports WHERE organization_id=? AND source_key IN ("+String.join(",",Collections.nCopies(batch.size(),"?"))+")",keys.toArray()).forEach(r->saved.put(text(r.get("source_key")),r));
                for(var r:batch){var existing=saved.get(text(r.get("source_key")));if(existing==null||!FinanceAdSpendReport.fingerprint(existing).equals(FinanceAdSpendReport.fingerprint(r)))throw bad("导入记录与已保存的同日同账户同系列金额冲突，整份文件未生效");}
            }
            return Map.of("inserted",inserted,"duplicateRows",entries.size()-inserted,"rows",entries.size());
        });
    }
    Map<String,Object> source(Scope s,String key,boolean lock){
        var imports=repo.jdbc().queryForList("SELECT * FROM finance_ad_spend_imports WHERE organization_id=? AND source_key=?"+(lock?" FOR UPDATE":""),s.organizationId(),key);
        if(!imports.isEmpty()){var r=new LinkedHashMap<>(imports.getFirst());r.put("source","IMPORT_FUNDING");r.put("shop_id","UNASSIGNED");r.put("record_date",text(r.get("record_date")));return r;}
        var records=repo.jdbc().queryForList("SELECT id,shop_id,platform,kind,record_date,currency,amount,source,raw_json FROM finance_records WHERE organization_id=? AND id=? AND kind IN ('AD','AD_GMV')"+(lock?" FOR UPDATE":""),s.organizationId(),key);
        if(records.isEmpty())throw bad("没有找到本公司的广告消耗记录");var r=records.getFirst();return FinanceAdSpendReport.api(r,repo.read(text(r.get("raw_json"))));
    }
    Map<String,Object> sourceDetails(Scope s,String key){
        var r=source(s,key,false);r.put("fingerprint",FinanceAdSpendReport.fingerprint(r));r.put("allocations",assignments(s,List.of(key)));return r;
    }
    void assign(Scope s,String actor,String key,String fingerprint,List<Map<String,Object>> splits){
        if(key==null||!key.matches("[a-f0-9]{64}")||splits==null||splits.size()>100)throw bad("分配记录不正确");
        repo.transaction(()->{
            var source=source(s,key,true);if(source.get("amount")==null)throw bad("来源合计未知，暂时不能分配");if(!FinanceAdSpendReport.fingerprint(source).equals(fingerprint))throw bad("来源金额已变化，请刷新后重新核对");
            if(!Set.of("","UNASSIGNED").contains(text(source.get("shop_id"))))throw bad("此明细已按官方或现有明确配置归店，不应再次跨店分摊");
            var prepared=new ArrayList<Map<String,Object>>();for(var split:splits){var r=new LinkedHashMap<>(split);finance.ownedShop(s,text(r.get("shop_id")));r.put("fingerprint",fingerprint);prepared.add(r);}
            FinanceAdSpendReport.allocated(source,prepared,finance.shops(s));
            int revision=repo.jdbc().queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM finance_ad_spend_allocation_sets WHERE organization_id=? AND source_key=?",Integer.class,s.organizationId(),key);
            String id=UUID.randomUUID().toString();repo.jdbc().update("INSERT INTO finance_ad_spend_allocation_sets(id,organization_id,source_key,revision,fingerprint,created_by) VALUES(?,?,?,?,?,?)",id,s.organizationId(),key,revision,fingerprint,actor);
            for(var r:prepared)repo.jdbc().update("INSERT INTO finance_ad_spend_allocations(allocation_set_id,shop_id,amount,cash_amount,credit_amount,grant_amount) VALUES(?,?,?,?,?,?)",id,r.get("shop_id"),decimal(r.get("amount")),decimal(r.get("cash_amount")),decimal(r.get("credit_amount")),decimal(r.get("grant_amount")));
            return null;
        });
    }
}
