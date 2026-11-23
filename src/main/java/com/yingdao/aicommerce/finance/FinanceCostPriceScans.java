package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Compares immutable cost books and queues only orders whose standard cost result changes. */
final class FinanceCostPriceScans {
    private record PriceKey(String warehouse,String sku) {}
    private record Change(boolean different,LocalDate firstDay) {}
    private record BookKey(String organization,String group,String id) {}
    private final FinanceRepository repo;
    private final LinkedHashMap<BookKey,List<Map<String,Object>>> books=new LinkedHashMap<>();

    FinanceCostPriceScans(FinanceRepository repo){this.repo=repo;}

    boolean request(Scope scope,String group,Map<String,Object> before,Map<String,Object> after){
        var change=change(before,after);
        if(!change.different())return false;
        String oldId=text(before.get("bookId"));
        LocalDate first=change.firstDay();
        if(first!=null&&first.getYear()<1000)first=null;
        return repo.jdbc().update("""
            INSERT INTO finance_cost_price_scans(organization_id,group_id,shop_id,old_book_id,new_book_id,first_day)
            SELECT organization_id,group_id,id,?,?,? FROM finance_connections
            WHERE organization_id=? AND group_id=? AND status<>'REMOVED'
            """,oldId.isBlank()?null:oldId,text(after.get("bookId")),first,scope.organizationId(),group)>0;
    }

    private static Change change(Map<String,Object> before,Map<String,Object> after){
        var old=prices(before);var newer=prices(after);
        // Adding or removing a code can change the SKU chosen for an earlier
        // order; changing the available warehouses can change its default warehouse.
        if(!old.keySet().equals(newer.keySet()))return new Change(true,null);
        LocalDate first=null;
        for(var entry:old.entrySet()){
            var left=entry.getValue();var right=newer.get(entry.getKey());
            var months=new TreeSet<>(left.keySet());months.addAll(right.keySet());
            for(String month:months){
                var previous=decimal(left.get(month));var current=decimal(right.get(month));
                if(previous==null?current==null:current!=null&&previous.compareTo(current)==0)continue;
                var day=LocalDate.parse(month+"-01");
                if(first==null||day.isBefore(first))first=day;
                break;
            }
        }
        return new Change(first!=null,first);
    }

    private static Map<PriceKey,Map<String,Object>> prices(Map<String,Object> book){
        var result=new HashMap<PriceKey,Map<String,Object>>();
        for(var row:rows(book.get("costs")))result.put(new PriceKey(text(row.get("warehouse")),text(row.get("sku"))),map(row.get("prices")));
        return result;
    }

    boolean scanBatch(FinanceOrderCosts costs){
        var scans=repo.jdbc().queryForList("SELECT * FROM finance_cost_price_scans ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if(scans.isEmpty())return false;
        var scan=scans.getFirst();var id=scan.get("id");
        String organization=text(scan.get("organization_id")),shop=text(scan.get("shop_id")),group=text(scan.get("group_id"));
        var emptyCatalog=new FinanceOrderCosts.Catalog("",List.of());
        var current=costs.context(organization,shop,new HashMap<>(Map.of(new FinanceOrderCosts.CatalogKey(organization,group),emptyCatalog)));
        if(current==null||!group.equals(current.groupId())||!repo.writableShop(organization,shop)){
            repo.jdbc().update("DELETE FROM finance_cost_price_scans WHERE id=?",id);
            return true;
        }
        var before=snapshot(costs,organization,current,text(scan.get("old_book_id")));
        var after=snapshot(costs,organization,current,text(scan.get("new_book_id")));
        var oldLookup=FinanceOrderCosts.lookup(before);var newLookup=FinanceOrderCosts.lookup(after);
        boolean shipment="shipment".equals(map(current.settings().get("rules")).get("basis"));
        Object first=shipment?null:scan.get("first_day");
        var candidates=first==null
            ?repo.jdbc().queryForList("""
                SELECT id,record_date,platform,status,raw_json FROM finance_records FORCE INDEX(idx_finance_cost_backfill)
                WHERE organization_id=? AND shop_id=? AND kind='ORDER' AND id>?
                ORDER BY id LIMIT 200
                """,organization,shop,scan.get("cursor_id"))
            :repo.jdbc().queryForList("""
                SELECT o.id,o.record_date,o.platform,o.status,o.raw_json
                FROM (
                    SELECT id,record_date FROM finance_records FORCE INDEX(idx_finance_company_kind_date)
                    WHERE organization_id=? AND shop_id=? AND kind='ORDER' AND record_date>=?
                      AND (record_date<? OR (record_date=? AND id>?))
                    ORDER BY record_date DESC,id LIMIT 200
                ) candidates JOIN finance_records o ON o.id=candidates.id
                ORDER BY candidates.record_date DESC,candidates.id
                """,organization,shop,first,scan.get("cursor_day"),scan.get("cursor_day"),scan.get("cursor_id"));
        var affected=new ArrayList<Object[]>();
        var actual=new FinanceActualCosts(repo).forRecords(candidates.stream().map(row->text(row.get("id"))).toList());
        for(var order:candidates){
            String orderId=text(order.get("id"));
            if(actual.containsKey(orderId)||Set.of("UNPAID","CANCELLED","CANCELED").contains(text(order.get("status"))))continue;
            var raw=costs.decode(order.get("raw_json"));
            var previous=FinanceOrderCosts.calculate(order,raw,before,oldLookup);
            var updated=FinanceOrderCosts.calculate(order,raw,after,newLookup);
            if(!comparable(previous).equals(comparable(updated)))affected.add(new Object[]{orderId,organization,shop});
        }
        if(!affected.isEmpty())costs.insertRows("""
            INSERT INTO finance_order_cost_jobs(order_record_id,organization_id,shop_id,priority) VALUES
            ""","(?,?,?,1)"," ON DUPLICATE KEY UPDATE priority=LEAST(priority,VALUES(priority))",affected);
        if(candidates.size()<200)repo.jdbc().update("DELETE FROM finance_cost_price_scans WHERE id=?",id);
        else{
            var last=candidates.getLast();
            repo.jdbc().update("UPDATE finance_cost_price_scans SET cursor_day=?,cursor_id=? WHERE id=?",
                last.get("record_date"),last.get("id"),id);
        }
        return true;
    }

    private FinanceOrderCosts.Context snapshot(FinanceOrderCosts costs,String organization,FinanceOrderCosts.Context shop,String bookId){
        List<Map<String,Object>> catalog=List.of();
        if(!bookId.isBlank()){
            var key=new BookKey(organization,shop.groupId(),bookId);
            catalog=books.computeIfAbsent(key,id->{
                var book=repo.jdbc().queryForMap("SELECT data_json FROM finance_cost_books WHERE organization_id=? AND group_id=? AND id=?",
                    id.organization(),id.group(),id.id());
                return FinanceCostSources.allCosts(costs.decode(book.get("data_json")));
            });
            if(books.size()>4)books.pollFirstEntry();
        }
        return new FinanceOrderCosts.Context(shop.shopId(),shop.groupId(),shop.region(),shop.timezone(),
            bookId,catalog,shop.settings(),shop.mappings());
    }

    // A new filename/book alone does not change an order's costing basis or amount.
    private static Map<String,Object> comparable(Map<String,Object> result){
        var content=new LinkedHashMap<>(result);
        content.remove("bookId");
        content.put("lines",rows(content.get("lines")).stream().map(line->{
            var changed=new LinkedHashMap<>(line);
            changed.remove("bookId");changed.remove("priceSource");
            changed.replaceAll((key,value)->value instanceof BigDecimal amount?amount.stripTrailingZeros():value);
            return changed;
        }).toList());
        content.replaceAll((key,value)->value instanceof BigDecimal amount?amount.stripTrailingZeros():value);
        return content;
    }

    boolean pending(Scope scope,Filter filter){
        return Boolean.TRUE.equals(repo.jdbc().queryForObject("""
            SELECT EXISTS(SELECT 1 FROM finance_cost_price_scans p JOIN finance_connections c
              ON c.organization_id=p.organization_id AND c.id=p.shop_id AND c.group_id=p.group_id
              WHERE p.organization_id=? AND c.status<>'REMOVED' AND (?='' OR c.id=?)
              AND (?='' OR (?='UNGROUPED' AND c.group_id IS NULL) OR c.group_id=?)
              AND (?='' OR c.platform=?))
            """,Boolean.class,scope.organizationId(),filter.shopId(),filter.shopId(),
            filter.groupId(),filter.groupId(),filter.groupId(),filter.platform(),filter.platform()));
    }
}
