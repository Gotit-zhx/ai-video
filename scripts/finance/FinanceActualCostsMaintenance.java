package com.yingdao.aicommerce.finance;

import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Scoped, resumable maintenance using workbook evidence; never writes platform financial records. */
public final class FinanceActualCostsMaintenance {
    static void require(boolean b,String message){if(!b)throw new IllegalStateException(message);}
    static void equal(Object actual,Object expected,String message){
        require(actual!=null&&expected!=null&&decimal(actual).compareTo(decimal(expected))==0,message+": "+actual+" != "+expected);
    }
    static Map<String,Object> load(ObjectMapper json,Path path)throws IOException{return map(json.readValue(Files.readString(path),Map.class));}
    public static void main(String[] args)throws Exception{
        var json=new ObjectMapper();Path root=Path.of(args[0]);var expected=load(json,root.resolve("expected.json"));
        var scope=companyScope(text(expected.get("organizationId")));String shop=text(expected.get("shopId"));
        require("fecf7801-0929-4076-bbaa-ee42f4dce87b".equals(shop),"This evidence is scoped to the reconciled YUXI-PH shop");
        try(var ds=new com.zaxxer.hikari.HikariDataSource()){
            ds.setJdbcUrl(System.getenv("BACKFILL_JDBC"));ds.setUsername(System.getenv("BACKFILL_USER"));ds.setPassword(System.getenv("BACKFILL_PASSWORD"));
            ds.setMaximumPoolSize(2);ds.setMinimumIdle(1);ds.setReadOnly(args[1].equals("verify"));
            var repo=new FinanceRepository(new JdbcTemplate(ds),json);repo.jdbc().setQueryTimeout(30);
            if(args[1].equals("import"))importEvidence(repo,root,scope,shop,expected);
            else if(args[1].equals("verify"))verify(repo,root,scope,shop,expected);
            else throw new IllegalArgumentException("Expected import or verify");
        }
    }
    static void importEvidence(FinanceRepository repo,Path root,Scope scope,String shop,Map<String,Object> expected)throws Exception{
        var payload=load(new ObjectMapper(),root.resolve("evidence.json"));var orders=rows(payload.get("orders"));
        require(orders.size()==number(expected.get("orders")),"Incomplete source payload");
        require(expected.get("sourceHash").equals(payload.get("sourceHash")),"Source hash mismatch");
        var importer=new FinanceActualCosts(repo);int changed=0,returned=0;
        for(int start=0;start<orders.size();start+=50){
            var batch=orders.subList(start,Math.min(start+50,orders.size()));
            var input=new LinkedHashMap<>(payload);input.put("orders",batch);input.put("returns",List.of());
            Path before=root.resolve("before-"+start+".json.gz");
            var result=retry(()->repo.transaction(()->{
                if(!Files.exists(before)){
                    var ids=batch.stream().map(o->o.get("orderId")).toList();
                    String slots=String.join(",",Collections.nCopies(ids.size(),"?"));
                    var parameters=new ArrayList<Object>(List.of(scope.organizationId(),shop));parameters.addAll(ids);
                    var archive=new LinkedHashMap<String,Object>();
                    for(String table:List.of("finance_actual_order_costs","finance_order_costs","finance_return_cost_receipts"))
                        archive.put(table,repo.jdbc().queryForList("SELECT * FROM "+table+" WHERE organization_id=? AND shop_id=? AND order_id IN ("+slots+")",parameters.toArray()));
                    try{
                        Path temp=Path.of(before+".tmp");
                        try(var out=new GZIPOutputStream(Files.newOutputStream(temp))){out.write(repo.write(archive).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                        Files.move(temp,before,StandardCopyOption.ATOMIC_MOVE);
                    }catch(IOException e){throw new IllegalStateException("Cannot retain before-image; no cost evidence changed",e);}
                }
                return importer.importEvidence(scope,"ERP_COST_CORRECTION_20261029",shop,input);
            }));
            changed+=number(result.get("orders"));
            System.out.println("Validated shipment snapshots: "+Math.min(start+50,orders.size())+"/"+orders.size());
        }
        var input=new LinkedHashMap<>(payload);input.put("orders",List.of());
        returned=(int)number(retry(()->repo.transaction(()->importer.importEvidence(scope,"ERP_COST_CORRECTION_20261029",shop,input))).get("receipts"));
        Files.writeString(root.resolve("import-result.json"),repo.write(Map.of("changedOrders",changed,"changedReceipts",returned,"at",Instant.now().toString())));
        System.out.println(repo.write(Map.of("changedOrders",changed,"changedReceipts",returned)));
    }
    static <T> T retry(java.util.function.Supplier<T> task){
        for(int i=0;;i++)try{return task.get();}catch(org.springframework.dao.TransientDataAccessException e){
            if(i==3)throw e;
            try{Thread.sleep(250L*(i+1));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
        }
    }
    static void verify(FinanceRepository repo,Path root,Scope scope,String shop,Map<String,Object> expected)throws Exception{
        var filter=new Filter(LocalDate.of(2026,9,1),LocalDate.of(2026,9,30),shop,"","PHP","day");
        var costs=new FinanceCostReport(repo);var summary=costs.summary(scope,filter,"SETTLED_ORDER");var total=map(summary.get("total"));
        equal(total.get("amount_cny"),expected.get("productCny"),"Product cost");
        equal(total.get("label_cost_cny"),expected.get("labelCny"),"Label cost");
        equal(total.get("return_cost_cny"),expected.get("returnCny"),"Received inventory credit");
        equal(total.get("actual_count"),expected.get("actualSeptemberOrders"),"Actual shipment cost count");
        require(number(total.get("pending_count"))==0&&number(total.get("queued_count"))==0,"Cost jobs still pending");
        var dashboard=costs.dashboardSummary(scope,filter,costs.dashboardFacts(scope,filter));
        for(String field:List.of("amount_cny","label_cost_cny","return_cost_cny","actual_count","order_count"))
            equal(map(dashboard.get("total")).get(field),total.get(field),"Dashboard "+field);
        var page=costs.orders(scope,filter,"SETTLED_ORDER","",1);
        for(String field:List.of("amount_cny","label_cost_cny","return_cost_cny"))
            equal(map(map(page.get("productCosts")).get("total")).get(field),total.get(field),"Cost page "+field);
        var source=load(new ObjectMapper(),root.resolve("excel-specification.json"));var official=map(source.get("orders"));
        var actual=repo.jdbc().queryForList("""
            SELECT order_id,SUM(revenue) income,SUM(amount) net FROM finance_records
            WHERE organization_id=? AND shop_id=? AND kind='SETTLEMENT' AND source='API' AND category='ORDER'
              AND record_date>='2026-09-01' AND record_date<'2026-10-01' GROUP BY order_id
            """,scope.organizationId(),shop);
        require(actual.size()==official.size(),"Official settled order count changed");
        for(var row:actual){var match=map(official.get(text(row.get("order_id"))));equal(row.get("income"),match.get("income"),"Official income");equal(row.get("net"),match.get("net"),"Official net settlement");}
        var export=new FinanceCostExport(repo).export(scope,filter,"SETTLED_ORDER","");
        var sheets=readSheets(export);var product=BigDecimal.ZERO;var lines=BigDecimal.ZERO;var credits=BigDecimal.ZERO;
        for(var r:sheets.get(2))if(r.containsKey("N")&&!r.get("N").isBlank())product=product.add(decimal(r.get("N")));
        for(var r:sheets.get(3))if(r.containsKey("T")&&!r.get("T").isBlank())lines=lines.add(decimal(r.get("T")));
        for(var r:sheets.get(4))if(r.containsKey("F")&&!r.get("F").isBlank())credits=credits.add(decimal(r.get("F")));
        equal(product,total.get("amount_cny"),"Export order cost");equal(lines,product,"Export SKU cost");equal(credits,total.get("return_cost_cny"),"Export received inventory");
        Files.write(root.resolve("verified-cost-export.xlsx"),export);
        var profit=FinanceProfitReport.calculate(List.of(new LinkedHashMap<>(Map.of("kind","SETTLEMENT","records",1,"known_amounts",1,"known_revenues",1,"revenue",decimal("100000"),"amount",decimal("90000")))),summary,"CNY",null);
        equal(profit.get("operatingCost"),expected.get("operatingCny"),"Operating cost formula");
        var result=new LinkedHashMap<String,Object>();result.put("summary",summary);result.put("expected",expected);
        result.put("officialOrdersUnchanged",actual.size());result.put("pageDashboardExportConsistent",true);result.put("at",Instant.now().toString());
        Files.writeString(root.resolve("verification.json"),repo.write(result));
        System.out.println(repo.write(Map.of("productCny",total.get("amount_cny"),"labelCny",total.get("label_cost_cny"),"returnCny",total.get("return_cost_cny"),"operatingCny",profit.get("operatingCost"),"officialOrdersUnchanged",actual.size(),"pageDashboardExportConsistent",true)));
    }
    static Map<Integer,List<Map<String,String>>> readSheets(byte[] bytes)throws Exception{
        var sheets=new HashMap<Integer,List<Map<String,String>>>();
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                if(!entry.getName().matches("xl/worksheets/sheet[234]\\.xml"))continue;
                int id=Integer.parseInt(entry.getName().replaceAll("\\D",""));var rows=new ArrayList<Map<String,String>>();
                var doc=factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes()));var nodes=doc.getElementsByTagName("row");
                for(int i=1;i<nodes.getLength();i++){
                    var cells=((org.w3c.dom.Element)nodes.item(i)).getElementsByTagName("c");var row=new HashMap<String,String>();
                    for(int j=0;j<cells.getLength();j++){var c=(org.w3c.dom.Element)cells.item(j);row.put(c.getAttribute("r").replaceAll("\\d",""),c.getTextContent());}
                    rows.add(row);
                }
                sheets.put(id,rows);
            }
        }
        return sheets;
    }
}
