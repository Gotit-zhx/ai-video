package com.yingdao.aicommerce.finance;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** SELECT-only checks of real cross-month examples; no seller login or business writes. */
public final class FinanceSettlementExportVerify {
    static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    static Set<String> exportedOrders(byte[] bytes)throws Exception{
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null)if(entry.getName().equals("xl/worksheets/sheet2.xml")){
                var factory=DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes()));
                var cells=document.getElementsByTagName("c");var orders=new LinkedHashSet<String>();boolean dateHeader=false;
                for(int i=0;i<cells.getLength();i++){
                    var cell=(Element)cells.item(i);String ref=cell.getAttribute("r");
                    if(ref.equals("G1"))dateHeader="结算归属日期".equals(cell.getTextContent());
                    if(ref.matches("C[0-9]+")&&!ref.equals("C1"))orders.add(cell.getTextContent());
                }
                require(dateHeader,"Missing settlement reporting date column");return orders;
            }
        }
        throw new IllegalStateException("Missing order-cost worksheet");
    }
    public static void main(String[] args)throws Exception{
        var json=new ObjectMapper();var specification=map(json.readValue(Files.readString(Path.of(args[0])),Map.class));
        var scope=companyScope(text(specification.get("organization")));String shop=text(specification.get("shop"));
        var october=new LinkedHashSet<>(((List<?>)specification.get("orders")).stream().map(FinanceData::text).toList());
        var september=new LinkedHashSet<>(((List<?>)specification.get("septemberControls")).stream().map(FinanceData::text).toList());
        var all=new ArrayList<>(october);all.addAll(september);String numbers=String.join("\n",all);
        try(var ds=new com.zaxxer.hikari.HikariDataSource()){
            ds.setJdbcUrl(System.getenv("BACKFILL_JDBC"));ds.setUsername(System.getenv("BACKFILL_USER"));ds.setPassword(System.getenv("BACKFILL_PASSWORD"));ds.setMaximumPoolSize(2);
            var repo=new FinanceRepository(new JdbcTemplate(ds),json);repo.jdbc().setQueryTimeout(30);
            var report=new FinanceCostReport(repo);var export=new FinanceCostExport(repo);var result=new LinkedHashMap<String,Object>();
            for(String source:List.of("ORDER","BILL","RECONCILIATION")){
                var counts=new LinkedHashMap<String,Object>();
                for(var month:List.of(YearMonth.of(2026,9),YearMonth.of(2026,10))){
                    var filter=new Filter(month.atDay(1),month.atEndOfMonth(),shop,"","","day");
                    var expected=month.getMonthValue()==9?september:october;
                    var page=report.orders(scope,filter,source,"",1,"ALL",numbers);
                    var actual=new LinkedHashSet<>(rows(page.get("items")).stream().map(row->text(row.get("order_id"))).toList());
                    require(number(page.get("total"))==expected.size()&&actual.equals(expected),source+" page period mismatch: "+month);
                    var workbook=export.export(scope,filter,source,"","ALL",numbers);
                    require(exportedOrders(workbook).equals(expected),source+" exported period mismatch: "+month);
                    counts.put(month.toString(),expected.size());
                }
                result.put(source,counts);
            }
            System.out.println(json.writeValueAsString(result));
        }
    }
}
