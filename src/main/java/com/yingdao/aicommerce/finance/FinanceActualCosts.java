package com.yingdao.aicommerce.finance;

import java.math.*;
import java.time.*;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Actual warehouse evidence is independent of catalog prices and immutable platform order facts. */
public final class FinanceActualCosts {
    final FinanceRepository repo;
    public FinanceActualCosts(FinanceRepository repo){this.repo=repo;}
    static BigDecimal money(Object value){
        var n=decimal(value);
        if(n==null||n.signum()<0||n.precision()-n.scale()>18)throw bad("实际成本金额缺失或不正确");
        return n.setScale(6,RoundingMode.HALF_UP);
    }
    static BigDecimal quantity(Object value){
        var n=decimal(value);
        if(n==null||n.signum()<=0||n.stripTrailingZeros().scale()>0||n.compareTo(new BigDecimal("1000000"))>0)throw bad("实际出入库数量不正确");
        return n;
    }
    static String required(Map<String,Object> value,String key,int max){
        String s=text(value.get(key)).trim();
        if(s.isBlank()||s.length()>max)throw bad("实际成本缺少有效的 "+key);
        return s;
    }
    static Map<String,Object> normalize(Map<String,Object> input,String name,String digest){
        String order=required(input,"orderId",200);
        var lines=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();BigDecimal total=BigDecimal.ZERO;
        for(var line:rows(input.get("lines"))){
            String sourceRow=required(line,"sourceRow",100),reference=required(line,"shipmentId",200);
            if(!seen.add(sourceRow))throw bad("同一出库来源行重复");
            if(!"已出库".equals(line.get("status")))throw bad("仅接受已确认出库记录");
            var q=quantity(line.get("quantity"));var unit=money(line.get("unitPriceCny"));var amount=money(line.get("amountCny"));
            if(unit.multiply(q).subtract(amount).abs().compareTo(new BigDecimal("0.005001"))>0)throw bad("出库单价、数量与总成本不一致");
            var copy=new LinkedHashMap<String,Object>();
            copy.put("sourceRow",sourceRow);copy.put("shipmentId",reference);
            copy.put("costCode",required(line,"sku",255));copy.put("warehouse",required(line,"warehouse",120));
            copy.put("name",text(line.get("name")));copy.put("quantity",q);copy.put("factor",BigDecimal.ONE);
            copy.put("unitPriceCny",unit);copy.put("amountCny",amount);
            copy.put("sellerSku","");copy.put("lineId","ERP:"+reference+":"+sourceRow);
            copy.put("productId","");copy.put("skuId","");copy.put("previousPrice",false);
            copy.put("priceMonth","实际出库");copy.put("costSource",name);lines.add(copy);total=total.add(amount);
        }
        if(lines.isEmpty()||lines.size()>1000)throw bad("实际出库订单需要完整的商品明细");
        money(total);
        var out=new LinkedHashMap<String,Object>();
        out.put("orderId",order);out.put("currency","CNY");out.put("basis","ERP_ACTUAL");out.put("status","READY");
        out.put("actualShipped",true);out.put("sourceName",name);out.put("sourceHash",digest);
        out.put("dateBasis","actual");out.put("costMonth","实际出库");
        out.put("warehouse",String.join("、",lines.stream().map(l->text(l.get("warehouse"))).distinct().toList()));
        out.put("lines",lines);out.put("lineCount",lines.size());out.put("matchedLines",lines.size());out.put("fallbackLines",0);
        out.put("amountCny",total);out.put("knownAmountCny",total);
        out.put("note","按 ERP 已出库型号、数量和人民币成本计价；已出库后取消仍保留成本，确认退回入库另行冲回；首次结算仅计一次。");
        return out;
    }
    Map<String,Map<String,Object>> forRecords(Collection<String> ids){
        if(ids.isEmpty())return Map.of();
        String slots=String.join(",",Collections.nCopies(ids.size(),"?"));
        var out=new HashMap<String,Map<String,Object>>();
        for(var r:repo.jdbc().queryForList("SELECT order_record_id,data_json FROM finance_actual_order_costs WHERE order_record_id IN ("+slots+")",ids.toArray()))
            out.put(text(r.get("order_record_id")),new FinanceOrderCosts(repo).decode(r.get("data_json")));
        return out;
    }
    /**
     * Maintenance/import entry point. Caller must hold one transaction and invalidate company caches after commit.
     * Sources are complete per-order shipment snapshots. Reimport replaces a snapshot, never adds its amount twice.
     */
    public Map<String,Object> importEvidence(Scope scope,String actor,String shop,Map<String,Object> input){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Actual cost evidence must be imported in one transaction");
        var shops=repo.jdbc().queryForList("SELECT id FROM finance_connections WHERE organization_id=? AND id=? AND status<>'REMOVED' FOR UPDATE",scope.organizationId(),shop);
        if(shops.isEmpty())throw bad("店铺不存在或已移除");
        String name=required(input,"sourceName",250),digest=required(input,"sourceHash",64);
        if(!digest.matches("[a-f0-9]{64}"))throw bad("来源文件校验值不正确");
        var orders=rows(input.get("orders"));var receipts=rows(input.get("returns"));
        if(orders.size()>500||receipts.size()>1000)throw bad("请分批导入实际成本");
        int changed=0,returned=0;var seen=new HashSet<String>();
        for(var order:orders){
            var value=normalize(order,name,digest);String id=text(value.get("orderId"));
            if(!seen.add(id))throw bad("导入包含重复订单快照");
            var record=order(scope,shop,id);
            String recordId=text(record.get("id")),serialized=repo.write(value),evidenceHash=hash(serialized);
            var previous=repo.jdbc().queryForList("SELECT evidence_hash FROM finance_actual_order_costs WHERE order_record_id=?",recordId);
            if(!previous.isEmpty()&&evidenceHash.equals(previous.getFirst().get("evidence_hash")))continue;
            repo.jdbc().update("""
                INSERT INTO finance_actual_order_costs(order_record_id,organization_id,shop_id,order_id,amount_cny,source_name,source_hash,evidence_hash,data_json)
                VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE amount_cny=VALUES(amount_cny),source_name=VALUES(source_name),
                source_hash=VALUES(source_hash),evidence_hash=VALUES(evidence_hash),data_json=VALUES(data_json),version=version+1,updated_at=UTC_TIMESTAMP(6)
                """,recordId,scope.organizationId(),shop,id,value.get("amountCny"),name,digest,evidenceHash,serialized);
            repo.jdbc().update("INSERT INTO finance_order_cost_jobs(order_record_id,organization_id,shop_id,priority) VALUES(?,?,?,0) ON DUPLICATE KEY UPDATE priority=0",recordId,scope.organizationId(),shop);
            changed++;
        }
        seen.clear();
        for(var receipt:receipts){
            String order=required(receipt,"orderId",200),reference=required(receipt,"receiptId",200),
                sku=required(receipt,"sku",255),warehouse=required(receipt,"warehouse",120),time=required(receipt,"receiptTime",40);
            if(!"已完成".equals(receipt.get("status"))||!"退回已签收".equals(receipt.get("logisticsStatus")))throw bad("退货成本必须有完成入库的凭据");
            LocalDate date;
            try{date=LocalDateTime.parse(time.replace(' ','T')).toLocalDate();}catch(RuntimeException e){throw bad("退货入库时间不正确");}
            quantity(receipt.get("quantity"));var amount=money(receipt.get("amountCny"));
            var record=order(scope,shop,order);
            if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_actual_order_costs WHERE order_record_id=?",Long.class,record.get("id"))==0)
                throw bad("退货入库记录缺少对应的实际出库凭据");
            String identity=hash(scope.organizationId()+"\0"+shop+"\0"+order+"\0"+reference+"\0"+time+"\0"+sku+"\0"+warehouse);
            if(!seen.add(identity))throw bad("同一退货入库凭据重复");
            var value=new LinkedHashMap<>(receipt);value.put("sourceName",name);value.put("sourceHash",digest);value.put("amountCny",amount);
            String serialized=repo.write(value),evidenceHash=hash(serialized);
            var previous=repo.jdbc().queryForList("SELECT evidence_hash FROM finance_return_cost_receipts WHERE id=?",identity);
            if(!previous.isEmpty()&&evidenceHash.equals(previous.getFirst().get("evidence_hash")))continue;
            repo.jdbc().update("""
                INSERT INTO finance_return_cost_receipts(id,order_record_id,organization_id,shop_id,order_id,receipt_date,native_currency,amount_cny,source_name,source_hash,evidence_hash,data_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE amount_cny=VALUES(amount_cny),source_name=VALUES(source_name),
                source_hash=VALUES(source_hash),evidence_hash=VALUES(evidence_hash),data_json=VALUES(data_json),version=version+1,updated_at=UTC_TIMESTAMP(6)
                """,identity,record.get("id"),scope.organizationId(),shop,order,date,record.get("currency"),amount,name,digest,evidenceHash,serialized);
            returned++;
        }
        if(changed+returned>0)repo.jdbc().update("""
            INSERT INTO finance_workspace_audit(id,organization_id,entity_type,entity_id,after_json,reason,actor_id)
            VALUES(?,?,'ACTUAL_COST_IMPORT',?,?,?,?)
            """,UUID.randomUUID().toString(),scope.organizationId(),shop,repo.write(Map.of("file",name,"hash",digest,"orders",changed,"receipts",returned)),"按已确认出库及退货入库凭据修正订单成本",actor);
        return Map.of("orders",changed,"receipts",returned);
    }
    Map<String,Object> order(Scope s,String shop,String order){
        var rows=repo.jdbc().queryForList("SELECT id,currency FROM finance_records FORCE INDEX(idx_finance_settled_order) WHERE organization_id=? AND shop_id=? AND kind='ORDER' AND source='API' AND order_id=?",s.organizationId(),shop,order);
        if(rows.size()!=1)throw bad("实际成本订单未唯一匹配到指定店铺："+order);
        return rows.getFirst();
    }
    FinanceRepository.Query receiptQuery(Scope s,Filter f,String numbers){
        String where="r.organization_id=? AND r.receipt_date>=? AND r.receipt_date<=? AND c.status<>'REMOVED' AND "+FinanceOrderCharges.normalOrder("r");
        var args=new ArrayList<Object>(List.of(s.organizationId(),f.start(),f.end()));
        if("UNGROUPED".equals(f.groupId()))where+=" AND (c.group_id IS NULL OR c.group_id='')";
        for(var field:List.of(new String[]{"r.shop_id",f.shopId()},new String[]{"c.group_id","UNGROUPED".equals(f.groupId())?"":f.groupId()},new String[]{"c.platform",f.platform()},new String[]{"r.native_currency",f.currency()}))
            if(!field[1].isBlank()){where+=" AND "+field[0]+"=?";args.add(field[1]);}
        return FinanceRecordSearch.orders(new FinanceRepository.Query(where,args),"r.order_id",numbers);
    }
    static final String RECEIPTS=" FROM finance_return_cost_receipts r JOIN finance_connections c ON c.id=r.shop_id AND c.organization_id=r.organization_id WHERE ";
    static final String QIANYI_RECEIPTS=" FROM finance_qianyi_return_costs r JOIN finance_connections c ON c.id=r.shop_id AND c.organization_id=r.organization_id WHERE ";
    List<Map<String,Object>> returnTotals(Scope s,Filter f){
        var q=receiptQuery(s,f,"");
        return repo.jdbc().queryForList("SELECT r.shop_id,COUNT(DISTINCT r.return_number) return_receipt_count,SUM(r.amount_cny) return_cost_cny"+QIANYI_RECEIPTS+q.where()+" GROUP BY r.shop_id",q.args().toArray());
    }
    public List<Map<String,Object>> returns(Scope s,Filter f,String numbers){
        var q=receiptQuery(s,f,numbers);
        String columns="SELECT r.id,r.shop_id,r.order_id,r.receipt_date,r.amount_cny,r.source_name,r.data_json,COALESCE(NULLIF(c.display_name,''),c.name) shop_name";
        var values=new ArrayList<>(repo.jdbc().queryForList(columns+QIANYI_RECEIPTS+q.where()+" ORDER BY r.receipt_date,r.order_id,r.id LIMIT 20001",q.args().toArray()));
        if(values.size()>20000)throw bad("退货成本明细超过 20,000 行，请缩小范围");
        values.sort(Comparator.comparing((Map<String,Object> r)->text(r.get("receipt_date"))).thenComparing(r->text(r.get("order_id"))).thenComparing(r->text(r.get("id"))));
        for(var row:values)row.put("evidence",new FinanceOrderCosts(repo).decode(row.remove("data_json")));
        return values;
    }
    void attachReturns(Scope s,Filter f,Map<String,Object> summary){
        // Preserve response compatibility without scanning or publishing inventory credits.
        var total=map(summary.get("total"));
        total.put("return_cost_cny",BigDecimal.ZERO);total.put("return_receipt_count",0L);total.put("return_pending_count",0L);
        summary.put("returnProcessing",false);summary.put("returnIntegrationPending",false);
        summary.put("returnSource","QIANYI");summary.put("returnCostReversalEnabled",false);
        summary.put("returnBasis","售后退款数据来自千易，仅用于核对，不重复扣减营业成本或利润。");
    }
    static Map<String,Object> returnShop(List<Map<String,Object>> shops,String shop){
        for(var row:shops)if(shop.equals(row.get("shop_id")))return row;
        var row=new LinkedHashMap<String,Object>();row.put("shop_id",shop);
        for(String key:FinanceCostReport.COST_SUMS)row.put(key,BigDecimal.ZERO);
        row.put("amount_cny",BigDecimal.ZERO);shops.add(row);return row;
    }
}
