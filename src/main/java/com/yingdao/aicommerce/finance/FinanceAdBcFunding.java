package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Independently leased public BC discovery and account-day cost collection. Never posts AD profit entries. */
final class FinanceAdBcFunding {
    private final FinanceAds ads;
    private final FinanceRepository repo;
    private static final long HISTORY_VERSION=1;
    private static final Set<String> FINANCE_ROLES=Set.of("MANAGER","ANALYST");
    private static final List<String> MONEY=List.of("amount","cash_amount","grant_amount","tax_amount");
    private static final class ChangedReport extends RuntimeException {}
    private static final class EmptyRefresh extends RuntimeException {}
    private static final class Group {
        final String organization,key,token;
        final Map<String,Map<String,Object>> accounts=new LinkedHashMap<>();
        Group(String organization,String key,String token){this.organization=organization;this.key=key;this.token=token;}
    }
    private record Page(int pages,long total) {}
    FinanceAdBcFunding(FinanceAds ads){this.ads=ads;this.repo=ads.repo;}

    /** One provider request per lease, with a credential shared by many accounts queried only once. */
    void tick(){
        var groups=groups();Group selected=null;Map<String,Object> job=null;
        for(var group:groups.values()){
            repo.jdbc().update("INSERT IGNORE INTO finance_ad_bc_sync(organization_id,credential_key) VALUES(?,?)",group.organization,group.key);
            for(var account:group.accounts.values())if(!group.key.equals(account.get("funding_credential_key")))
                repo.jdbc().update("UPDATE finance_ad_accounts SET funding_credential_key=? WHERE organization_id=? AND advertiser_id=? AND enabled=TRUE AND access_token_encrypted=?",
                        group.key,group.organization,account.get("advertiser_id"),account.get("access_token_encrypted"));
            var due=repo.jdbc().queryForList("SELECT * FROM finance_ad_bc_sync WHERE organization_id=? AND credential_key=? AND next_at<=UTC_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))",group.organization,group.key);
            if(!due.isEmpty()&&(job==null||instant(due.getFirst().get("next_at")).isBefore(instant(job.get("next_at"))))){selected=group;job=due.getFirst();}
        }
        if(selected==null)return;
        String lease=UUID.randomUUID().toString();var group=selected;
        if(repo.jdbc().update("UPDATE finance_ad_bc_sync SET lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE) WHERE organization_id=? AND credential_key=? AND next_at<=UTC_TIMESTAMP(6) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))",lease,group.organization,group.key)!=1)return;
        // Reauthorization can reset pages between selection and claim. Only the claimed generation is authoritative.
        var claimed=repo.jdbc().queryForList("SELECT * FROM finance_ad_bc_sync WHERE organization_id=? AND credential_key=? AND lease_token=? AND lease_until>UTC_TIMESTAMP(6)",group.organization,group.key,lease);
        if(claimed.isEmpty())return;var state=claimed.getFirst();
        // Refresh BC access between complete days; a large day must not restart every six hours.
        var known=read(state.get("bc_json"));
        boolean discovery=state.get("bc_json")==null||!read(state.get("checkpoint_json")).containsKey("run")&&
                (!instant(state.get("discovery_next_at")).isAfter(Instant.now())||number(known.get("history_version"))<HISTORY_VERSION);
        try{if(discovery)discover(group,state,lease);else cost(group,state,lease);}
        catch(RuntimeException failure){failed(group,state,lease,discovery,failure);}
    }
    private Map<String,Group> groups(){
        var result=new LinkedHashMap<String,Group>();
        var accounts=repo.jdbc().queryForList("SELECT * FROM finance_ad_accounts a WHERE enabled=TRUE AND "+FinanceAds.visibleAccount()+" ORDER BY organization_id,advertiser_id LIMIT 5001");
        if(accounts.size()>5000)throw bad("BC 资金账户范围超过安全上限");
        for(var account:accounts){
            String organization=text(account.get("organization_id")),token;
            try{token=token(account);}catch(RuntimeException invalid){invalidCredential(account);continue;}
            String key=credentialKey(organization,token);
            var group=result.computeIfAbsent(organization+":"+key,k->new Group(organization,key,token));
            group.accounts.put(text(account.get("advertiser_id")),account);
        }
        return result;
    }
    private void invalidCredential(Map<String,Object> account){
        String organization=text(account.get("organization_id")),advertiser=text(account.get("advertiser_id"));
        String key=hash("finance-bc-unreadable\0"+organization+"\0"+advertiser+"\0"+text(account.get("access_token_encrypted")));
        repo.jdbc().update("INSERT IGNORE INTO finance_ad_bc_sync(organization_id,credential_key,status,error_message,next_at) VALUES(?,?,'NEEDS_AUTHORIZATION',?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY))",organization,key,"BC 资金凭据无法读取；请核对原有加密配置或使用原应用重新授权。");
        repo.jdbc().update("UPDATE finance_ad_accounts SET funding_credential_key=? WHERE organization_id=? AND advertiser_id=? AND access_token_encrypted=?",key,organization,advertiser,account.get("access_token_encrypted"));
    }
    private String token(Map<String,Object> account){
        return ads.cipher.decrypt(text(account.get("access_token_encrypted")),text(account.get("organization_id")),"finance:"+text(account.get("user_id"))+":"+text(account.get("advertiser_id")));
    }
    private static String credentialKey(String organization,String token){return hash("finance-bc\0"+organization+"\0"+token);}
    private boolean owns(Group g,String lease){
        return !repo.jdbc().queryForList("SELECT credential_key FROM finance_ad_bc_sync WHERE organization_id=? AND credential_key=? AND lease_token=? AND lease_until>UTC_TIMESTAMP(6) FOR UPDATE",g.organization,g.key,lease).isEmpty();
    }
    /** Account locks precede the funding lease lock, matching connect/pause lock order. */
    private Map<String,Map<String,Object>> currentAccounts(Group g){
        var current=new LinkedHashMap<String,Map<String,Object>>();
        for(var account:repo.jdbc().queryForList("SELECT * FROM finance_ad_accounts a WHERE organization_id=? AND enabled=TRUE AND "+FinanceAds.visibleAccount()+" ORDER BY advertiser_id FOR UPDATE",g.organization)){
            if(!g.key.equals(account.get("funding_credential_key")))continue;
            try{if(g.key.equals(credentialKey(g.organization,token(account))))current.put(text(account.get("advertiser_id")),account);}
            catch(RuntimeException invalid){/* Another credential group can continue; no fact is published for this account. */}
        }
        return current;
    }

    private void discover(Group g,Map<String,Object> state,String lease){
        int page=(int)number(state.get("discovery_page"));
        boolean owned=repo.transaction(()->!currentAccounts(g).isEmpty()&&owns(g,lease));
        if(!owned)return;
        var data=ads.getFunding("/bc/get/",g.token,FinanceAdBcApi.discoveryQuery(page));
        var returned=FinanceAdBcApi.businessCenters(data);var pagination=page(data,page,returned.size());
        var staged=read(state.get("discovery_stage_json"));var bcs=new ArrayList<>(rows(staged.get("list")));
        checkPagination(staged,pagination);long seen=number(staged.get("seen"))+returned.size();
        var ids=new HashSet<String>();bcs.forEach(b->ids.add(text(b.get("bc_id"))));
        for(var bc:returned)if(!ids.add(text(bc.get("bc_id"))))throw bad("BC 发现分页包含重复标识");else bcs.add(bc);
        if(bcs.size()>200)throw bad("BC 发现范围超过安全上限，未发布截断结果");
        if(page==pagination.pages&&seen!=pagination.total)throw new ChangedReport();
        repo.transaction(()->{
            if(currentAccounts(g).isEmpty()||!owns(g,lease))return null;
            if(page<pagination.pages){
                var next=new LinkedHashMap<String,Object>();next.put("list",bcs);next.put("seen",seen);rememberPagination(next,pagination);
                repo.jdbc().update("UPDATE finance_ad_bc_sync SET discovery_page=?,discovery_stage_json=? WHERE organization_id=? AND credential_key=?",page+1,repo.write(next),g.organization,g.key);
                release(g,lease,"SYNCING",null,30,false);return null;
            }
            boolean financial=bcs.stream().anyMatch(b->FINANCE_ROLES.contains(text(b.get("finance_role"))));
            String status=financial?"SYNCING":bcs.isEmpty()?"NEEDS_BC_ACCESS":"NEEDS_FINANCE_ROLE";
            String message=financial?roleWarning(bcs):bcs.isEmpty()?"当前授权未返回可访问的 Business Center；请核对 BC 访问关系及 Read Business Center 授权。":"当前可访问 BC 未返回 Finance Manager 或 Finance Analyst 角色；请由 BC 管理员配置财务角色。";
            var cp=restart(read(state.get("checkpoint_json")));
            var previous=read(state.get("bc_json"));
            if(financial&&(number(previous.get("history_version"))<HISTORY_VERSION||!readableBcs(rows(previous.get("list"))).containsAll(readableBcs(bcs))))cp.put("history_required",true);
            long discoveryDelay=financial&&roleWarning(bcs)==null?21600:3600;
            repo.jdbc().update("UPDATE finance_ad_bc_sync SET bc_json=?,discovery_page=1,discovery_stage_json=NULL,discovery_next_at=?,checkpoint_json=? WHERE organization_id=? AND credential_key=?",
                    repo.write(Map.of("list",bcs,"history_version",HISTORY_VERSION)),Timestamp.from(Instant.now().plusSeconds(discoveryDelay)),cp.isEmpty()?null:repo.write(cp),g.organization,g.key);
            repo.jdbc().update("DELETE FROM finance_ad_bc_stage WHERE organization_id=? AND credential_key=?",g.organization,g.key);
            release(g,lease,status,message,financial?30:discoveryDelay,false);return null;
        });
    }
    private void cost(Group g,Map<String,Object> state,String lease){
        var allBcs=rows(read(state.get("bc_json")).get("list"));
        var bcs=allBcs.stream().filter(b->FINANCE_ROLES.contains(text(b.get("finance_role")))).toList();
        if(bcs.isEmpty())throw bad("BC 财务角色尚未确认");
        var cp=read(state.get("checkpoint_json"));
        Instant now=Instant.now();var zones=g.accounts.values().stream().map(a->ZoneId.of(text(a.get("timezone")))).distinct().toList();
        LocalDate today=latestDay(now,zones);
        if(Boolean.TRUE.equals(cp.remove("history_required"))){
            LocalDate from=today.minusDays(89),until=today;
            if(cp.containsKey("from")){var unfinished=LocalDate.parse(text(cp.get("from")));if(unfinished.isBefore(from))from=unfinished;}
            if(cp.containsKey("until")){var unfinished=LocalDate.parse(text(cp.get("until")));if(unfinished.isAfter(until))until=unfinished;}
            cp.put("from",from.toString());cp.put("until",until.toString());
        }else if(cp.isEmpty()){
            boolean nightly=nightly(state.get("last_success_at")==null?null:instant(state.get("last_success_at")),now,zones);
            cp.put("from",today.minusDays(state.get("last_success_at")==null?89:nightly?30:6).toString());cp.put("until",today.toString());
        }
        cp.putIfAbsent("bc_index",0);cp.putIfAbsent("page",1);cp.putIfAbsent("run",UUID.randomUUID().toString());
        LocalDate day=LocalDate.parse(text(cp.get("from"))),until=LocalDate.parse(text(cp.get("until")));
        if(day.isAfter(until)||day.plusYears(1).isBefore(until))throw bad("BC 资金回补日期无效");
        int index=(int)number(cp.get("bc_index")),page=(int)number(cp.get("page"));
        if(index<0||index>=bcs.size())throw new ChangedReport();
        String bc=text(bcs.get(index).get("bc_id"));
        boolean owned=repo.transaction(()->{
            if(currentAccounts(g).isEmpty()||!owns(g,lease))return false;
            repo.jdbc().update("UPDATE finance_ad_bc_sync SET checkpoint_json=?,status='SYNCING' WHERE organization_id=? AND credential_key=?",repo.write(cp),g.organization,g.key);return true;
        });
        if(!owned)return;
        var data=ads.getFunding("/bc/account/cost/get/",g.token,FinanceAdBcApi.costQuery(bc,day,page));
        var rawRows=FinanceAdAutomation.requiredRows(data,"cost_list");var pagination=page(data,page,rawRows.size());
        checkPagination(cp,pagination);long seen=number(cp.get("seen"))+rawRows.size();
        if(page==pagination.pages&&seen!=pagination.total)throw new ChangedReport();
        var facts=FinanceAdBcApi.costs(data,day,g.accounts.keySet());
        repo.transaction(()->{
            var current=currentAccounts(g);if(current.isEmpty()||!owns(g,lease))return null;
            for(var fact:facts){
                String advertiser=text(fact.get("account_id"));if(!current.containsKey(advertiser))continue;
                if(!text(current.get(advertiser).get("currency")).equals(fact.get("currency")))throw bad("BC 费用币种与已验证账户不一致");
                var old=repo.jdbc().queryForList("SELECT page_number FROM finance_ad_bc_stage WHERE organization_id=? AND credential_key=? AND run_id=? AND bc_id=? AND advertiser_id=?",g.organization,g.key,cp.get("run"),bc,advertiser);
                if(old.stream().anyMatch(r->number(r.get("page_number"))!=page))throw new ChangedReport();
                var staged=new LinkedHashMap<>(fact);for(String key:MONEY)if(staged.get(key)!=null)staged.put(key,decimal(staged.get(key)).toPlainString());
                repo.jdbc().update("INSERT INTO finance_ad_bc_stage(organization_id,credential_key,run_id,bc_id,advertiser_id,record_date,currency,page_number,source_json) VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE source_json=VALUES(source_json)",g.organization,g.key,cp.get("run"),bc,advertiser,day.toString(),fact.get("currency"),page,repo.write(staged));
            }
            if(page<pagination.pages){cp.put("page",page+1);cp.put("seen",seen);rememberPagination(cp,pagination);}
            else if(index+1<bcs.size()){clearPage(cp);cp.put("bc_index",index+1);}
            else{
                publish(g,current,cp,day);
                repo.jdbc().update("DELETE FROM finance_ad_bc_stage WHERE organization_id=? AND credential_key=? AND run_id=?",g.organization,g.key,cp.get("run"));
                if(day.isBefore(until)){cp.clear();cp.put("from",day.plusDays(1).toString());cp.put("until",until.toString());}
                else cp.clear();
            }
            boolean done=cp.isEmpty();
            repo.jdbc().update("UPDATE finance_ad_bc_sync SET checkpoint_json=? WHERE organization_id=? AND credential_key=?",done?null:repo.write(cp),g.organization,g.key);
            release(g,lease,done?"SUCCESS":"SYNCING",roleWarning(allBcs),done?3600:30,done);return null;
        });
    }
    private void publish(Group g,Map<String,Map<String,Object>> current,Map<String,Object> cp,LocalDate day){
        var facts=new LinkedHashMap<String,Map<String,Object>>();var provenance=new HashMap<String,Set<String>>();
        for(var staged:repo.jdbc().queryForList("SELECT bc_id,advertiser_id,source_json FROM finance_ad_bc_stage WHERE organization_id=? AND credential_key=? AND run_id=? ORDER BY advertiser_id,bc_id",g.organization,g.key,cp.get("run"))){
            String advertiser=text(staged.get("advertiser_id"));if(!current.containsKey(advertiser))continue;
            var row=repo.read(text(staged.get("source_json")));var old=facts.putIfAbsent(advertiser,row);
            if(old!=null&&(!text(old.get("currency")).equals(row.get("currency"))||MONEY.stream().anyMatch(k->!sameMoney(old.get(k),row.get(k)))))throw new ChangedReport();
            provenance.computeIfAbsent(advertiser,k->new TreeSet<>()).add(text(staged.get("bc_id")));
        }
        // A successful empty or narrower response does not authorize erasing known cost facts.
        for(var old:repo.jdbc().queryForList("SELECT advertiser_id,currency FROM finance_ad_bc_costs WHERE organization_id=? AND record_date=?",g.organization,day.toString())){
            String advertiser=text(old.get("advertiser_id"));if(!current.containsKey(advertiser))continue;
            if(!facts.containsKey(advertiser)||!text(old.get("currency")).equals(facts.get(advertiser).get("currency")))throw new EmptyRefresh();
        }
        for(var row:facts.values()){
            String advertiser=text(row.get("account_id"));
            repo.jdbc().update("""
                INSERT INTO finance_ad_bc_costs(organization_id,source_key,advertiser_id,advertiser_name,record_date,currency,amount,cash_amount,grant_amount,tax_amount,bc_ids_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE advertiser_name=VALUES(advertiser_name),amount=VALUES(amount),cash_amount=VALUES(cash_amount),
                  grant_amount=VALUES(grant_amount),tax_amount=VALUES(tax_amount),bc_ids_json=VALUES(bc_ids_json),observed_at=UTC_TIMESTAMP(6)
                """,g.organization,row.get("source_key"),advertiser,row.get("account_name"),day.toString(),row.get("currency"),row.get("amount"),row.get("cash_amount"),row.get("grant_amount"),row.get("tax_amount"),repo.write(provenance.get(advertiser)));
        }
    }
    private static boolean sameMoney(Object a,Object b){BigDecimal x=decimal(a),y=decimal(b);return x==null?y==null:y!=null&&x.compareTo(y)==0;}
    static boolean nightly(Instant lastSuccess,Instant now,Collection<ZoneId> zones){
        return lastSuccess!=null&&latestDay(lastSuccess,zones).isBefore(latestDay(now,zones));
    }
    private static LocalDate latestDay(Instant at,Collection<ZoneId> zones){
        return zones.stream().map(z->at.atZone(z).toLocalDate()).max(Comparator.naturalOrder()).orElseThrow();
    }
    private static Page page(Map<String,Object> data,int requested,int size){
        var info=map(data.get("page_info"));
        for(String field:List.of("page","total_page","total_number"))if(!text(info.get(field)).matches("\\d{1,8}"))throw bad("BC 资金缺少有效分页状态");
        int pages=(int)number(info.get("total_page"));long total=number(info.get("total_number"));
        if(number(info.get("page"))!=requested||size>50||pages>1000||total>50000||requested<1||pages<requested&&!(requested==1&&pages==0&&size==0&&total==0)||size>total||requested<pages&&size==0)throw bad("BC 资金分页不完整");
        return new Page(Math.max(1,pages),total);
    }
    private static void checkPagination(Map<String,Object> cp,Page pagination){
        if(cp.containsKey("expected_total")&&(number(cp.get("expected_total"))!=pagination.total||number(cp.get("expected_pages"))!=pagination.pages))throw new ChangedReport();
    }
    private static void rememberPagination(Map<String,Object> cp,Page p){cp.put("expected_total",p.total);cp.put("expected_pages",p.pages);}
    private static void clearPage(Map<String,Object> cp){cp.put("page",1);cp.remove("seen");cp.remove("expected_total");cp.remove("expected_pages");}
    private static Map<String,Object> restart(Map<String,Object> cp){var result=new LinkedHashMap<String,Object>();for(String key:List.of("from","until","history_required"))if(cp.containsKey(key))result.put(key,cp.get(key));return result;}
    private Map<String,Object> read(Object json){return json==null?new LinkedHashMap<>():new LinkedHashMap<>(repo.read(text(json)));}
    private static Set<String> readableBcs(List<Map<String,Object>> bcs){var ids=new HashSet<String>();for(var bc:bcs)if(FINANCE_ROLES.contains(text(bc.get("finance_role"))))ids.add(text(bc.get("bc_id")));return ids;}
    private static String roleWarning(List<Map<String,Object>> bcs){return bcs.stream().anyMatch(b->!FINANCE_ROLES.contains(text(b.get("finance_role"))))?"部分可见 BC 未返回财务角色；仅查询已确认财务角色的 BC。请核对实际付款 BC 及其 Finance Analyst 或 Finance Manager 角色，尚未确认这些 BC 与本广告账户的付款关系。":null;}
    private void release(Group g,String lease,String status,String message,long delay,boolean success){
        repo.jdbc().update("UPDATE finance_ad_bc_sync SET status=?,error_message=?,failures=0,next_at=?,last_success_at=CASE WHEN ? THEN UTC_TIMESTAMP(6) ELSE last_success_at END,lease_token=NULL,lease_until=NULL WHERE organization_id=? AND credential_key=? AND lease_token=?",status,message,Timestamp.from(Instant.now().plusSeconds(delay)),success,g.organization,g.key,lease);
    }
    private void failed(Group g,Map<String,Object> state,String lease,boolean discovery,RuntimeException failure){
        repo.transaction(()->{
            if(currentAccounts(g).isEmpty()||!owns(g,lease))return null;
            String status="ERROR",message="BC 资金接口暂未完成或返回不完整；已保留原费用与进度，系统将自动重试。";
            long delay=FinanceAdAutomation.backoff(number(state.get("failures")));
            if(failure instanceof FinanceAdFailures.ProviderFailure provider){
                String code=provider.code();
                if(Set.of("40001","40125","40118").contains(code)){
                    status="NEEDS_PERMISSION";delay=86400;
                    message=discovery?"BC 发现被拒绝（错误码 "+code+"）；请核对原应用及令牌的 Read Business Center 权限并重新授权。":"BC 费用读取被拒绝（错误码 "+code+"）；请核对 Ad Account Balance and Transaction 权限、BC 财务角色并重新授权。";
                }else if(Set.of("40102","40104","40105","40106","40113").contains(code)){
                    status="NEEDS_AUTHORIZATION";delay=86400;message="BC 资金授权不可用（错误码 "+code+"）；请使用原应用重新完成广告授权。";
                }else message="BC 资金接口拒绝请求（错误码 "+code+"）；已保留费用与同步进度，系统将自动重试。";
            }
            if(failure instanceof ChangedReport){
                var saved=repo.jdbc().queryForList("SELECT checkpoint_json FROM finance_ad_bc_sync WHERE organization_id=? AND credential_key=?",g.organization,g.key).getFirst();
                var cp=restart(read(saved.get("checkpoint_json")));
                repo.jdbc().update("UPDATE finance_ad_bc_sync SET checkpoint_json=? WHERE organization_id=? AND credential_key=?",cp.isEmpty()?null:repo.write(cp),g.organization,g.key);
                repo.jdbc().update("DELETE FROM finance_ad_bc_stage WHERE organization_id=? AND credential_key=?",g.organization,g.key);
                message="BC 资金分页或不同 BC 的同账户金额发生变化；已保留上次费用，当前日期将完整重查。";
            }
            if(failure instanceof EmptyRefresh)message="BC 资金刷新未返回已有日期的账户费用；已保留上次费用和进度，待核对并自动重试。";
            if(discovery)repo.jdbc().update("UPDATE finance_ad_bc_sync SET discovery_page=1,discovery_stage_json=NULL WHERE organization_id=? AND credential_key=?",g.organization,g.key);
            repo.jdbc().update("UPDATE finance_ad_bc_sync SET status=?,error_message=?,failures=failures+1,next_at=?,lease_token=NULL,lease_until=NULL WHERE organization_id=? AND credential_key=? AND lease_token=?",status,message,Timestamp.from(Instant.now().plusSeconds(delay)),g.organization,g.key,lease);return null;
        });
    }

    /** Runs inside the account authorization transaction; a same-token permission refresh also retries now. */
    void reauthorized(Scope s,String advertiser,String token){
        String key=credentialKey(s.organizationId(),token);
        var previous=repo.jdbc().queryForList("SELECT funding_credential_key FROM finance_ad_accounts WHERE organization_id=? AND advertiser_id=?",s.organizationId(),advertiser);
        String old=previous.isEmpty()?"":text(previous.getFirst().get("funding_credential_key"));
        if(!old.isBlank())repo.jdbc().update("UPDATE finance_ad_bc_sync SET lease_token=NULL,lease_until=NULL WHERE organization_id=? AND credential_key=?",s.organizationId(),old);
        repo.jdbc().update("INSERT IGNORE INTO finance_ad_bc_sync(organization_id,credential_key) VALUES(?,?)",s.organizationId(),key);
        var state=repo.jdbc().queryForList("SELECT checkpoint_json FROM finance_ad_bc_sync WHERE organization_id=? AND credential_key=?",s.organizationId(),key).getFirst();
        var cp=restart(read(state.get("checkpoint_json")));
        cp.put("history_required",true);
        repo.jdbc().update("UPDATE finance_ad_bc_sync SET status='READY',error_message=NULL,failures=0,next_at=UTC_TIMESTAMP(6),discovery_next_at=UTC_TIMESTAMP(6),discovery_page=1,discovery_stage_json=NULL,checkpoint_json=?,lease_token=NULL,lease_until=NULL WHERE organization_id=? AND credential_key=?",cp.isEmpty()?null:repo.write(cp),s.organizationId(),key);
        repo.jdbc().update("DELETE FROM finance_ad_bc_stage WHERE organization_id=? AND credential_key=?",s.organizationId(),key);
        repo.jdbc().update("UPDATE finance_ad_accounts SET funding_credential_key=? WHERE organization_id=? AND advertiser_id=?",key,s.organizationId(),advertiser);
    }
    void revoke(Scope s,String advertiser){
        repo.jdbc().update("UPDATE finance_ad_bc_sync j JOIN finance_ad_accounts a ON a.organization_id=j.organization_id AND a.funding_credential_key=j.credential_key SET j.lease_token=NULL,j.lease_until=NULL,j.next_at=GREATEST(j.next_at,UTC_TIMESTAMP(6)) WHERE a.organization_id=? AND a.advertiser_id=?",s.organizationId(),advertiser);
    }

    /** Read contract for the separate account-funding reconciliation area. Never append these rows to operating spend. */
    static List<Map<String,Object>> sources(FinanceRepository repo,Scope s,Filter f){
        if(!f.platform().isBlank()&&!f.platform().equals("TIKTOK_SHOP"))return List.of();
        var args=new ArrayList<Object>(List.of(s.organizationId(),f.start(),f.end()));String where="b.organization_id=? AND b.record_date BETWEEN ? AND ?";
        if(!f.currency().isBlank()){where+=" AND b.currency=?";args.add(f.currency());}
        var saved=repo.jdbc().queryForList("SELECT b.*,a.shop_id,a.name FROM finance_ad_bc_costs b JOIN finance_ad_accounts a ON a.organization_id=b.organization_id AND a.advertiser_id=b.advertiser_id WHERE "+where+" AND "+FinanceAds.visibleAccount()+" ORDER BY b.record_date DESC,b.advertiser_id LIMIT 50001",args.toArray());
        if(saved.size()>50000)throw bad("BC 资金超过 50,000 条，请缩小日期范围");
        var result=new ArrayList<Map<String,Object>>();
        for(var record:saved){
            var row=new LinkedHashMap<String,Object>();for(String key:List.of("source_key","currency","amount","cash_amount","grant_amount","tax_amount","observed_at"))row.put(key,record.get(key));
            row.put("source","API_BC_COST");row.put("platform","TIKTOK_SHOP");row.put("record_date",text(record.get("record_date")));
            row.put("account_id",record.get("advertiser_id"));row.put("account_name",first(record,"advertiser_name","name"));row.put("campaign_id","");row.put("campaign_name","");
            row.put("shop_id",text(record.get("shop_id")).isBlank()?"UNASSIGNED":text(record.get("shop_id")));
            row.put("credit_amount",null);row.put("net_amount",null);row.put("reason_credit_amount","接口未拆分");row.put("reason_net_amount","接口未返回净消耗");
            for(String key:List.of("cash_amount","grant_amount","tax_amount"))if(row.get(key)==null)row.put("reason_"+key,"接口未返回");
            row.put("funding_basis","BC 账户日财务费用；税额独立核对，未拆授信与推广系列，不与投放总额叠加。");result.add(row);
        }
        return result;
    }
    void decorate(Scope s,List<Map<String,Object>> accounts,LocalDate from,LocalDate to){
        var states=new HashMap<String,Map<String,Object>>();
        repo.jdbc().queryForList("SELECT a.advertiser_id,j.status,j.error_message,j.last_success_at,j.next_at,j.bc_json FROM finance_ad_accounts a LEFT JOIN finance_ad_bc_sync j ON j.organization_id=a.organization_id AND j.credential_key=a.funding_credential_key WHERE a.organization_id=? AND "+FinanceAds.visibleAccount(),s.organizationId()).forEach(r->states.put(text(r.get("advertiser_id")),r));
        var args=new ArrayList<Object>();args.add(s.organizationId());String where="organization_id=?";
        if(from!=null&&to!=null){where+=" AND record_date BETWEEN ? AND ?";args.add(from);args.add(to);}
        var facts=new HashMap<String,Map<String,Object>>();
        repo.jdbc().queryForList("SELECT advertiser_id,COUNT(*) records,COUNT(DISTINCT record_date) returned_days,MIN(record_date) first_day,MAX(record_date) last_day FROM finance_ad_bc_costs WHERE "+where+" GROUP BY advertiser_id",args.toArray()).forEach(r->facts.put(text(r.get("advertiser_id")),r));
        for(var account:accounts){
            String id=text(account.get("advertiser_id"));var state=states.getOrDefault(id,Map.of());var item=new LinkedHashMap<String,Object>();
            String syncStatus=Boolean.FALSE.equals(account.get("enabled"))?"PAUSED":text(state.get("status"));if(syncStatus.isBlank())syncStatus="READY";
            var bcs=rows(read(state.get("bc_json")).get("list"));long readable=readableBcs(bcs).size();boolean missingRole=readable<bcs.size();
            var coverage=facts.getOrDefault(id,Map.of());boolean hasRecords=number(coverage.get("records"))>0;
            String status=syncStatus,message=text(state.get("error_message"));
            if(syncStatus.equals("SUCCESS")){
                if(missingRole){status="PARTIAL_ACCESS";message=roleWarning(bcs);}
                else if(!hasRecords)status="NO_DATA";
                if(!hasRecords)message=(message.isBlank()?"":message+" ")+"当前查询范围未取得该广告账户的资金费用记录；请核对实际付款 BC、账户访问关系及报表日期，空响应不代表零消耗。";
            }
            item.put("source","API_BC_COST");item.put("status",status);item.put("sync_status",syncStatus);item.put("error_message",message.isBlank()?null:message);item.put("last_success_at",state.get("last_success_at"));item.put("next_at",state.get("next_at"));
            item.put("permissionRequired",status.startsWith("NEEDS_")||missingRole);
            item.put("readable_bc_count",readable);item.put("missing_finance_role_bc_count",bcs.size()-readable);item.put("coverage_status",hasRecords?"HAS_RECORDS":"NO_RECORDS");
            item.put("records",number(coverage.get("records")));item.put("returned_days",number(coverage.get("returned_days")));item.put("first_day",coverage.get("first_day"));item.put("last_day",coverage.get("last_day"));
            item.put("coverage_basis","仅统计已返回账户费用记录的日期；空响应不表示零消耗或资金完整。");account.put("funding",item);
        }
    }
}
