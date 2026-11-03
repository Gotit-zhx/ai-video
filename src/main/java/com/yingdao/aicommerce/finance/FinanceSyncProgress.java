package com.yingdao.aicommerce.finance;

import java.time.Instant;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** A read-only description of the same eligibility and ordering used by the dispatcher. */
final class FinanceSyncProgress {
    private FinanceSyncProgress(){}
    static List<Map<String,Object>> describe(List<Map<String,Object>> states,List<Map<String,Object>> pacing,Instant now){
        var rows=states.stream().map(LinkedHashMap<String,Object>::new).toList();
        var busy=new HashMap<String,String>();
        var priorities=new HashMap<String,Instant>();
        var platforms=new HashMap<String,String>();
        var pending=new HashMap<String,Integer>();
        for(var row:rows){
            String shop=text(row.get("shop_id"));
            platforms.put(shop,text(row.get("platform")));
            if(!active(row))continue;
            if(after(row.get("lease_until"),now))busy.put(shop,text(row.get("channel")));
            if(row.get("priority_requested_at")!=null){
                priorities.merge(shop,instant(row.get("priority_requested_at")),(a,b)->a.isBefore(b)?a:b);
                pending.merge(shop,1,Integer::sum);
            }
        }
        var priorityPositions=new HashMap<String,Integer>();
        var positionsByPlatform=new HashMap<String,Integer>();
        priorities.keySet().stream().sorted(Comparator.<String,Instant>comparing(priorities::get).thenComparing(s->s))
            .forEach(shop->priorityPositions.put(shop,positionsByPlatform.merge(platforms.get(shop),1,Integer::sum)));
        var budgets=new HashMap<String,Instant>();
        for(var pace:pacing)if(pace.get("next_allowed_at")!=null)
            budgets.put(text(pace.get("platform"))+"/"+text(pace.get("channel")),instant(pace.get("next_allowed_at")));
        var eligible=new ArrayList<Map<String,Object>>();
        for(var row:rows){
            // MySQL DATETIME is UTC here; send an explicit offset to every browser.
            for(String key:List.of("next_at","last_success_at","lease_until","priority_requested_at","last_progress_at"))
                if(row.get(key)!=null)row.put(key,instant(row.get(key)));
            String shop=text(row.get("shop_id")),channel=text(row.get("channel")),status=text(row.get("status"));
            boolean failed=number(row.get("failures"))>0||Set.of("ERROR","RATE_LIMITED").contains(status);
            boolean scheduled=after(row.get("next_at"),now);
            Instant budget=budgets.get(text(row.get("platform"))+"/"+channel);
            if(budget!=null&&budget.isAfter(now))row.put("platform_retry_at",budget);
            String execution;
            if(!active(row))execution="DISABLED";
            else if(after(row.get("lease_until"),now))execution="RUNNING";
            else if("RATE_LIMITED".equals(status)&&(scheduled||budget!=null&&budget.isAfter(now)))execution="RATE_LIMITED";
            else if(failed&&scheduled)execution="RETRY_WAIT";
            else if(scheduled&&"SUCCESS".equals(status)&&row.get("priority_requested_at")==null)execution="SUCCESS";
            else if(scheduled)execution="SCHEDULED";
            else if(busy.containsKey(shop)){
                execution="WAITING_SHOP";
                row.put("blocking_channel",busy.get(shop));
            }else if(budget!=null&&budget.isAfter(now)){
                execution="API_WAIT";
                row.put("platform_retry_at",budget);
            }else{
                execution="QUEUED";
                eligible.add(row);
            }
            row.put("execution_state",execution);
            if(priorities.containsKey(shop)){
                row.put("shop_priority_requested_at",priorities.get(shop));
                row.put("priority_queue_position",priorityPositions.get(shop));
                row.put("priority_pending_channels",pending.get(shop));
            }
            // Only a complete initial scan establishes historical coverage.
            row.put("initial_sync_complete",row.get("last_success_at")!=null);
        }
        eligible.sort(Comparator.<Map<String,Object>,Instant>comparing(
            r->r.get("priority_requested_at")==null?null:instant(r.get("priority_requested_at")),
            Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(r->due(r))
            .thenComparing(r->text(r.get("shop_id")))
            .thenComparing(r->text(r.get("channel"))));
        positionsByPlatform.clear();
        for(var row:eligible)row.put("queue_position",positionsByPlatform.merge(text(row.get("platform")),1,Integer::sum));
        return new ArrayList<Map<String,Object>>(rows);
    }
    private static boolean active(Map<String,Object> row){
        return (Boolean.TRUE.equals(row.get("enabled"))||number(row.get("enabled"))==1)
            &&"ACTIVE".equals(row.get("connection_status"));
    }
    private static boolean after(Object value,Instant now){return value!=null&&instant(value).isAfter(now);}
    private static Instant due(Map<String,Object> row){
        return (row.get("next_at")==null?Instant.EPOCH:instant(row.get("next_at")))
            .minusSeconds("ORDER_LIVE".equals(row.get("channel"))?30:0);
    }
}
