package com.yingdao.aicommerce.finance;

import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Receipt qualification uses an explicit reverse-delivery event, never forward DELIVERED/CANCEL. */
final class FinanceQianyiReceipt {
    static final String FIELD="_returnReceipt";
    private FinanceQianyiReceipt(){}

    static boolean candidate(Map<String,Object> source){
        return "SALES_RETURN".equals(source.get("type"))&&!FinanceQianyiEvidence.deleted(source.get("isDeleted"))
            &&"FINISH".equals(first(source,"returnStatus","status"))&&received(source.get("receiveTime"))>0;
    }
    static long received(Object value){
        try{
            long millis=new java.math.BigDecimal(text(value)).longValueExact();
            return millis>=946684800000L&&millis<4102444800000L?millis:0;
        }catch(RuntimeException invalid){return 0;}
    }
    static String signature(Map<String,Object> source){
        return hash(String.join("\0",List.of(text(source.get("returnNumber")),text(source.get("orderNumber")),
            text(source.get("onlineOrderNumber")),text(source.get("customNumber")),text(source.get("receiveTime")),
            first(source,"returnStatus","status"),text(source.get("updateTime")))));
    }
    static Map<String,Object> evidence(Map<String,Object> source,List<Map<String,Object>> parcels){
        String tracking=text(source.get("customNumber"));
        var result=new LinkedHashMap<String,Object>();
        result.put("source","QIANYI_TRACKING");result.put("sourceSignature",signature(source));
        result.put("state","UNKNOWN");result.put("trackingNumber",tracking);
        if(tracking.isBlank())return result;
        long latest=0;String terminal=null,eventText="";boolean ambiguous=false;
        for(var parcel:parcels){
            if(!tracking.equals(text(parcel.get("trackingNumber"))))continue;
            String order=text(parcel.get("orderNumber")),online=text(parcel.get("onlineOrderId"));
            boolean orderMatches=!order.isBlank()&&(order.equals(text(source.get("orderNumber")))||order.equals(text(source.get("returnNumber"))));
            boolean onlineMatches=!online.isBlank()&&online.equals(text(source.get("onlineOrderNumber")));
            if(!orderMatches&&!onlineMatches||!order.isBlank()&&!orderMatches||!online.isBlank()&&!onlineMatches)continue;
            for(var event:rows(parcel.get("eventList"))){
                long at=received(event.get("eventDate"));String message=text(event.get("event")).trim();
                if(at==0||message.length()>512||!message.toLowerCase(Locale.ROOT).contains("return"))continue;
                String state=message.matches("(?i)^Your package has been returned to the seller(?: in .+)?\\.?$")?"SIGNED"
                    :message.matches("(?i)^(Your package is being returned to the seller.*|Return package (arrived|out for delivery).*)$")?"IN_TRANSIT":"UNKNOWN";
                if(at>latest){latest=at;terminal=state;eventText=message;ambiguous=false;}
                else if(at==latest&&!Objects.equals(terminal,state))ambiguous=true;
            }
        }
        if(latest>0){
            result.put("state",ambiguous?"UNKNOWN":terminal);result.put("eventDate",latest);result.put("event",eventText);
        }
        return result;
    }
    static FinanceQianyiClient.Page enrich(FinanceQianyi integration,Map<String,Object> config,FinanceQianyiClient.Page page){
        var repo=integration.repo;var previous=new HashMap<String,Map<String,Object>>();
        var ids=page.items().stream().map(v->text(v.get("returnNumber"))).filter(v->!v.isBlank()).distinct().toList();
        if(!ids.isEmpty()){
            var args=new ArrayList<Object>(List.of(config.get("organization_id"),config.get("shop_id")));args.addAll(ids);
            for(var row:repo.jdbc().queryForList("SELECT return_number,data_json FROM finance_qianyi_returns WHERE organization_id=? AND shop_id=? AND return_number IN ("+
                    String.join(",",Collections.nCopies(ids.size(),"?"))+")",args.toArray()))
                previous.put(text(row.get("return_number")),map(repo.read(text(row.get("data_json"))).get(FIELD)));
        }
        var output=new ArrayList<Map<String,Object>>();var pending=new ArrayList<Map<String,Object>>();
        for(var original:page.items()){
            var source=new LinkedHashMap<>(original);source.remove(FIELD);output.add(source);
            if(!candidate(source))continue;
            var old=previous.getOrDefault(text(source.get("returnNumber")),Map.of());
            if("SIGNED".equals(old.get("state"))&&signature(source).equals(old.get("sourceSignature"))){source.put(FIELD,old);continue;}
            source.put(FIELD,evidence(source,List.of()));
            if(!text(source.get("customNumber")).isBlank())pending.add(source);
        }
        var tracking=pending.stream().map(v->text(v.get("customNumber"))).distinct().toList();
        for(int offset=0;offset<tracking.size();offset+=20){
            var batch=tracking.subList(offset,Math.min(offset+20,tracking.size()));
            var parcels=new ArrayList<Map<String,Object>>();boolean complete=false;
            try{
                for(int n=1;n<=3;n++){
                    var response=integration.client.query(integration.credentials(config),"QUERY_FIRST_LRG_TRACKING_PACKAGE",
                        Map.of("trackingNumbers",batch,"returnDetails",true,"page",n,"pageSize",100));
                    if(response.items().isEmpty()&&(n-1L)*100<response.total())break;
                    parcels.addAll(response.items());
                    if(n*100L>=response.total()){complete=true;break;}
                }
            }catch(com.yingdao.aicommerce.common.exception.BusinessException unavailable){
                // Source pagination remains independent. A later receipt scan retries unknown evidence.
                break;
            }
            if(complete)for(var source:pending)if(batch.contains(text(source.get("customNumber"))))
                source.put(FIELD,evidence(source,parcels));
        }
        return new FinanceQianyiClient.Page(output,page.total());
    }

    /** Replay missing fields with exact old cursors nested underneath; daily receipt checks cover two calendar months. */
    static Map<String,Object> plan(Map<String,Object> config,Map<String,Object> checkpoint,LocalDate today){
        if(Boolean.TRUE.equals(checkpoint.get("receiptScan"))||number(checkpoint.get("sourceVersion"))>=3)return checkpoint;
        boolean upgrade=number(config.get("source_version"))<3;
        var last=config.get("last_receipt_scan_at")==null?null:instant(config.get("last_receipt_scan_at"));
        if(!upgrade&&last!=null&&!last.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().isBefore(today))return checkpoint;
        Map<String,Object> resume=new LinkedHashMap<>(checkpoint);
        resume.put("sourceVersion",3);
        if(upgrade&&number(config.get("source_version"))>=2){
            var replay=new LinkedHashMap<String,Object>();
            replay.put("from",text(config.get("sync_start")));replay.put("until",today.toString());
            replay.put("page",1);replay.put("sourceVersion",3);replay.put("backfillResume",resume);resume=replay;
        }
        var scan=new LinkedHashMap<String,Object>();
        scan.put("from",today.withDayOfMonth(1).minusMonths(1).toString());scan.put("until",today.toString());
        scan.put("page",1);scan.put("sourceVersion",3);scan.put("receiptScan",true);scan.put("backfillResume",resume);
        return scan;
    }
}
