package com.yingdao.aicommerce.finance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

@Service
public class FinanceImport {
    final FinanceService service;final FinanceRepository repo;
    public FinanceImport(FinanceService service,FinanceRepository repo){this.service=service;this.repo=repo;}
    public Map<String,Object> preview(byte[] bytes,String filename){
        var table=FinanceWorkbook.read(bytes,filename);
        if(table.size()<2)throw bad("文件需要标题行和至少一行数据");
        return Map.of("columns",table.getFirst(),"sample",table.subList(1,Math.min(table.size(),7)),"rows",table.size()-1);
    }
    @Transactional
    public Map<String,Object> commit(Scope s,String shopId,String kind,Map<String,Object> mapping,byte[] bytes,String filename){
        if(!Set.of("BILL","COST","AD").contains(kind))throw bad("支持导入结算账单、物流贴单成本和广告费用");
        var shop=service.ownedShop(s,shopId);
        s=service.shopScope(s,shopId);
        if(kind.equals("AD")&&repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_ad_accounts WHERE organization_id=? AND shop_id=? AND enabled=TRUE",
                Integer.class,s.organizationId(),shopId)>0)throw bad("该店铺已启用广告接口同步，请勿重复导入广告费用");
        String digest=hash(Base64.getEncoder().encodeToString(bytes)+repo.write(new TreeMap<>(mapping)));
        var old=repo.jdbc().queryForList("SELECT row_count FROM finance_imports WHERE organization_id=? AND user_id=? AND shop_id=? AND kind=? AND content_hash=?",
                s.organizationId(),s.userId(),shopId,kind,digest);
        if(!old.isEmpty())return Map.of("duplicate",true,"rows",old.getFirst().get("row_count"));
        var data=FinanceWorkbook.read(bytes,filename);if(data.size()<2)throw bad("文件没有数据");
        List<Entry> entries=new ArrayList<>();Set<String> unique=new HashSet<>();
        for(int i=1;i<data.size();i++){
            var row=data.get(i);String id=value(row,mapping,"external_id"),currency=value(row,mapping,"currency").toUpperCase(Locale.ROOT);
            String day=value(row,mapping,"date"),amount=value(row,mapping,"amount");
            BigDecimal number=decimal(amount.replace(",",""));if(number==null)throw bad("第 "+(i+1)+" 行金额无法识别");
            LocalDate date;try{date=LocalDate.parse(day);}catch(Exception e){throw bad("第 "+(i+1)+" 行日期请使用 YYYY-MM-DD 文本格式");}
            String order=value(row,mapping,"order_id"),statement=value(row,mapping,"statement_id");
            if(kind.equals("BILL")&&!statement.isBlank())id=statement;
            if(!unique.add(id))throw bad("第 "+(i+1)+" 行编号重复，请先合并同一账单或补充唯一记录编号");
            Entry entry=new Entry(shopId,text(shop.get("platform")),kind,id,order,kind.equals("BILL")?id:statement,
                    value(row,mapping,"category"),"IMPORTED",date,currency,number,null,null,null,null,"IMPORT",
                    Map.of("file",filename,"row",i+1,"reference",id));
            validate(entry);entries.add(entry);
        }
        for(var entry:entries)repo.save(s,entry);
        repo.jdbc().update("INSERT INTO finance_imports(id,organization_id,user_id,shop_id,file_name,content_hash,row_count,kind) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),s.organizationId(),s.userId(),shopId,filename.substring(0,Math.min(250,filename.length())),digest,entries.size(),kind);
        return Map.of("duplicate",false,"rows",entries.size());
    }
    static String value(List<String> row,Map<String,Object> mapping,String key){
        Object raw=mapping.get(key);if(raw==null||text(raw).isBlank())return "";
        int n;try{n=Integer.parseInt(text(raw));}catch(Exception e){throw bad("列映射不正确");}
        return n>=0&&n<row.size()?row.get(n).trim():"";
    }
}
