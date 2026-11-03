package com.yingdao.aicommerce.finance;

import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Calendar-month priority without discarding an existing page, bill or history cursor. */
final class FinanceSyncPlan {
    private static final long WEEK=7*86400L;
    private static final List<String> META=List.of("recentFirst","recentWindowDays","priorityMonth",
            "completedMonthFrom","completedMonthUntil","previousMonthScan","missingSettlementPass",
            "missingSettlementMonth","missingPassStartedAt","missingRowsChecked","missingRowsFetched");
    private FinanceSyncPlan(){}

    static LocalDate start(LocalDate today,boolean initial,boolean nightly,boolean manual,String channel){
        LocalDate usual=initial?today.minusDays(89):nightly?today.minusDays(30):today.minusDays(1);
        if(Set.of("ORDER","SETTLEMENT").contains(channel)&&(initial||nightly||manual)){
            LocalDate month=today.withDayOfMonth(1).minusMonths(1);
            if(month.isBefore(usual))usual=month;
        }
        return usual;
    }

    static Map<String,Object> previousMonthFirst(Map<String,Object> checkpoint,String channel,ZoneId zone,Instant now){
        return previousMonthFirst(checkpoint,channel,zone,now,false);
    }
    static Map<String,Object> previousMonthFirst(Map<String,Object> checkpoint,String channel,ZoneId zone,Instant now,boolean requireMonth){
        if(!Set.of("ORDER","SETTLEMENT").contains(channel))return checkpoint;
        LocalDate end=now.atZone(zone).toLocalDate().withDayOfMonth(1),start=end.minusMonths(1);
        String month=YearMonth.from(start).toString();
        if(month.equals(checkpoint.get("priorityMonth")))return checkpoint;
        long from=start.atStartOfDay(zone).toEpochSecond(),until=end.atStartOfDay(zone).toEpochSecond();
        var nodes=new ArrayList<Map<String,Object>>();
        var node=checkpoint;
        while(true){
            long a=number(node.get("from")),b=number(node.get("to")),c=number(node.get("until"));
            // Unknown legacy ranges are left intact; they must not silently become a new scan.
            if(a<946684800L||b<=a||c<b||c>=4102444800L||nodes.size()>=16)return checkpoint;
            nodes.add(node);
            if(!(node.get("backfillResume") instanceof Map<?,?>))break;
            node=map(node.get("backfillResume"));
        }
        if(!requireMonth&&nodes.stream().noneMatch(n->number(n.get("from"))<until))return checkpoint;
        Map<String,Object> resume=null;
        for(int i=nodes.size()-1;i>=0;i--){
            var saved=new LinkedHashMap<>(nodes.get(i));saved.remove("backfillResume");
            saved.remove("previousMonthScan");
            saved.put("priorityMonth",month);saved.put("completedMonthFrom",from);saved.put("completedMonthUntil",until);
            if(resume!=null)saved.put("backfillResume",resume);
            resume=saved;
        }
        var priority=new LinkedHashMap<String,Object>();
        priority.put("from",from);priority.put("to",Math.min(from+WEEK,until));priority.put("until",until);
        if(checkpoint.containsKey("coverage"))priority.put("coverage",checkpoint.get("coverage"));
        priority.put("run",UUID.randomUUID().toString());priority.put("priorityMonth",month);
        priority.put("previousMonthScan",true);
        priority.put("backfillResume",resume);
        return priority;
    }

    static LocalDate completedMonth(Map<String,Object> cp,FinanceMarketplace.Step page,ZoneId zone){
        if(!page.done()||!Boolean.TRUE.equals(cp.get("previousMonthScan")))return null;
        var month=YearMonth.parse(text(cp.get("priorityMonth")));
        long end=month.plusMonths(1).atDay(1).atStartOfDay(zone).toEpochSecond();
        return number(cp.get("until"))==end&&number(cp.get("to"))==end?month.atDay(1):null;
    }

    /** Fill absent payouts before the heavier refresh, retaining its exact pagination chain. */
    static Map<String,Object> missingSettlementsFirst(Map<String,Object> cp,String platform,String channel,
                                                     ZoneId zone,Instant now,boolean needsMonth){
        if(!needsMonth||!"SHOPEE".equals(platform)||!"SETTLEMENT".equals(channel))return cp;
        LocalDate end=now.atZone(zone).toLocalDate().withDayOfMonth(1),start=end.minusMonths(1);
        String month=YearMonth.from(start).toString();
        if(month.equals(cp.get("missingSettlementMonth"))||!month.equals(cp.get("priorityMonth")))return cp;
        var saved=new LinkedHashMap<>(cp);saved.put("missingSettlementMonth",month);
        var pass=new LinkedHashMap<String,Object>();
        long from=start.atStartOfDay(zone).toEpochSecond(),until=end.atStartOfDay(zone).toEpochSecond();
        pass.put("from",from);pass.put("to",Math.min(from+WEEK,until));pass.put("until",until);
        if(cp.containsKey("coverage"))pass.put("coverage",cp.get("coverage"));
        pass.put("run",UUID.randomUUID().toString());pass.put("priorityMonth",month);
        pass.put("missingSettlementPass",true);pass.put("missingSettlementMonth",month);
        pass.put("missingPassStartedAt",now.getEpochSecond());pass.put("backfillResume",saved);
        return pass;
    }

    static FinanceMarketplace.Step nextWindow(Map<String,Object> cp,FinanceMarketplace.Step step){
        if(!step.done())return step;
        Map<String,Object> next=window(cp,number(cp.get("to")));
        if(next!=null)return new FinanceMarketplace.Step(next,false);
        var resume=cp.get("backfillResume");
        while(resume instanceof Map<?,?>){
            var saved=new LinkedHashMap<>(map(resume));
            // Cursors belong to the exact original API window. Finish that page chain
            // unchanged, even if it overlaps the month just fetched (upserts are idempotent).
            if(!saved.containsKey("completedMonthFrom")||hasPage(saved))
                return new FinanceMarketplace.Step(saved,false);
            next=window(saved,number(saved.get("from")));
            if(next!=null)return new FinanceMarketplace.Step(next,false);
            resume=saved.get("backfillResume");
        }
        return step;
    }

    private static boolean hasPage(Map<String,Object> cp){
        return List.of("cursor","transactionCursor","transactionCount","bills","lastBillPage","page")
                .stream().anyMatch(cp::containsKey);
    }
    private static Map<String,Object> window(Map<String,Object> cp,long from){
        long until=number(cp.get("until")),skipFrom=number(cp.get("completedMonthFrom")),
                skipUntil=number(cp.get("completedMonthUntil"));
        boolean skip=skipUntil>skipFrom&&skipFrom>=946684800L;
        if(skip&&from>=skipFrom&&from<skipUntil)from=skipUntil;
        if(from>=until)return null;
        long to=Math.min(from+WEEK,until);
        if(skip&&from<skipFrom)to=Math.min(to,skipFrom);
        var next=new LinkedHashMap<String,Object>();
        next.put("from",from);next.put("to",to);next.put("until",until);
        if(cp.containsKey("coverage"))next.put("coverage",cp.get("coverage"));
        next.put("run",UUID.randomUUID().toString());
        for(String key:META)if(cp.containsKey(key))next.put(key,cp.get(key));
        if(cp.containsKey("backfillResume"))next.put("backfillResume",cp.get("backfillResume"));
        return next;
    }
}
