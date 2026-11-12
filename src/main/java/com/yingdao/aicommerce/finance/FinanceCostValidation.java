package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.*;

/** Deterministic candidate validation; callers supply server-verified source evidence and current month. */
public final class FinanceCostValidation {
    private FinanceCostValidation() {}
    private static final Map<String,BigDecimal> DEFAULT_POLICY=Map.of(
        "priceChangePercent",new BigDecimal("20"),"priceChangeCny",BigDecimal.ONE,
        "impactChangeCny",new BigDecimal("1000"),"impactChangePercent",new BigDecimal("5"));

    private record Identity(String sku,String warehouseKey,String type,String unit) {}
    private record Key(Identity identity,String month) {}
    private record Cell(Key key,String warehouse,String warehouseId,BigDecimal price,Object row) {}

    /**
     * current accepts the legacy sku/warehouse/prices shape; missing unit/type use batch metadata for comparison only.
     * A changed cell relying on such legacy evidence always requires review.
     * warehouseAliases is an alias -> stable warehouse ID map and is used only with sourceVerified=true.
     * risk is BLOCKED, HIGH, REVIEW or LOW. Counts are unique cells, never deletions inferred from absent rows.
     * Impact thresholds are validated here; order-impact evaluation belongs to the preview service.
     */
    public static Map<String,Object> analyze(List<Map<String,Object>> incoming,List<Map<String,Object>> current,
                                           Map<String,Object> metadata,Map<String,Object> policy) {
        var meta=metadata==null?Map.<String,Object>of():metadata;
        var issues=new ArrayList<Map<String,Object>>();
        var limits=new HashMap<>(DEFAULT_POLICY);
        if(policy!=null)for(String name:DEFAULT_POLICY.keySet())if(policy.containsKey(name)) {
            var value=decimal(policy.get(name));
            if(value==null||value.signum()<0)add(issues,"POLICY_INVALID","BLOCK",null,"");else limits.put(name,value);
        }
        boolean verified=Boolean.TRUE.equals(meta.get("sourceVerified"))&&!text(meta.get("sourceKey")).isBlank();
        if(!verified)add(issues,"SOURCE_UNVERIFIED","REVIEW",null,"");
        YearMonth now=month(meta.get("nowMonth")),from=month(meta.get("effectiveFrom")),to=month(meta.get("effectiveTo"));
        if(now==null)add(issues,"NOW_MONTH_UNKNOWN","REVIEW",null,"");
        if((present(meta,"effectiveFrom")&&from==null)||(present(meta,"effectiveTo")&&to==null)||
            (from!=null&&to!=null&&from.isAfter(to)))add(issues,"PERIOD_INVALID","BLOCK",null,"");
        var aliases=verified&&meta.get("warehouseAliases") instanceof Map<?,?> map?map:Map.of();
        var existing=new HashMap<Key,List<Cell>>();
        var legacyKeys=new HashSet<Key>();
        for(var row:current==null?List.<Map<String,Object>>of():current) {
            if(row==null||!(row.get("prices") instanceof Map<?,?> prices))continue;
            for(var entry:prices.entrySet()) {
                var cell=cell(row,meta,aliases,text(entry.getKey()),decimal(entry.getValue()));
                if(cell.price()!=null&&month(cell.key().month())!=null) {
                    existing.computeIfAbsent(cell.key(),k->new ArrayList<>()).add(cell);
                    if(text(row.get("unit")).isBlank()||!Set.of("SKU","SPU").contains(text(row.get("identifierType"))))legacyKeys.add(cell.key());
                }
            }
        }
        var candidates=new LinkedHashMap<Key,List<Cell>>();
        var semantic=new TreeSet<String>();
        for(var row:incoming==null?List.<Map<String,Object>>of():incoming) {
            if(row==null){add(issues,"IDENTIFIER_INVALID","BLOCK",null,"");semantic.add("null");continue;}
            String sku=identifier(row.get("sku")),warehouse=text(row.get("warehouse"));
            String type=text(value(row,meta,"identifierType")),unit=text(value(row,meta,"unit"));
            // Include invalid input evidence too, so blocked candidates do not all collapse to an empty hash.
            if(!(row.get("prices") instanceof Map<?,?> prices)||prices.isEmpty()) {
                add(issues,"EMPTY_COSTS","BLOCK",row,"");semantic.add(canonical(List.of(sku,warehouse,type,unit)));continue;
            }
            boolean validIdentity=validIdentifier(row.get("sku"));
            if(!validIdentity)add(issues,"IDENTIFIER_INVALID","BLOCK",row,"");
            if(!Set.of("SKU","SPU").contains(type))add(issues,"IDENTIFIER_TYPE_UNKNOWN","REVIEW",row,"");
            if(unit.isBlank())add(issues,"UNIT_UNKNOWN","REVIEW",row,"");
            if(warehouse.isBlank())add(issues,"WAREHOUSE_UNKNOWN","BLOCK",row,"");
            boolean validWarehouse=warehouse.length()<=120&&!formula(warehouse);
            if(!validWarehouse)add(issues,"WAREHOUSE_INVALID","BLOCK",row,"");
            if(type.equals("SPU")&&!Boolean.TRUE.equals(meta.get("spuUniform")))add(issues,"SPU_UNIFORM_UNCONFIRMED","REVIEW",row,"");
            String currency=text(value(row,meta,"currency"));
            if(!currency.isBlank()&&!currency.equals("CNY"))add(issues,"CURRENCY_UNSUPPORTED","BLOCK",row,"");
            for(var entry:prices.entrySet()) {
                String costMonth=text(entry.getKey());var price=decimal(entry.getValue());var cell=cell(row,meta,aliases,costMonth,price);
                semantic.add(canonical(List.of(sku,warehouse,cell.warehouseId(),type,unit,currency,costMonth,price==null?text(entry.getValue()):price,
                    validIdentity?"":"invalid-identifier:"+text(row.get("sku")))));
                YearMonth parsed=month(costMonth);
                if(parsed==null)add(issues,"MONTH_INVALID","BLOCK",row,costMonth);
                else {
                    if((from!=null&&parsed.isBefore(from))||(to!=null&&parsed.isAfter(to)))add(issues,"MONTH_OUT_OF_SCOPE","BLOCK",row,costMonth);
                    if(now!=null&&parsed.isAfter(now))add(issues,"FUTURE_MONTH","REVIEW",row,costMonth);
                }
                boolean validPrice=price!=null&&price.signum()>=0&&price.precision()-price.scale()<=18&&Math.max(0,price.stripTrailingZeros().scale())<=18;
                if(!validPrice)add(issues,"PRICE_INVALID","BLOCK",row,costMonth);
                else if(price.signum()==0)add(issues,"ZERO_PRICE","BLOCK",row,costMonth);
                if(validIdentity&&validWarehouse&&validPrice&&parsed!=null)candidates.computeIfAbsent(cell.key(),k->new ArrayList<>()).add(cell);
            }
        }
        var historyByIdentity=new HashMap<Identity,NavigableMap<String,List<Cell>>>();
        var knownSkus=new HashSet<String>();
        existing.forEach((key,cells)->{
            historyByIdentity.computeIfAbsent(key.identity(),k->new TreeMap<>()).put(key.month(),cells);
            knownSkus.add(key.identity().sku());
        });
        int changed=0,unchanged=0;
        var normalized=new TreeMap<String,Map<String,Object>>();
        for(var cells:candidates.values()) {
            Cell candidate=cells.getFirst();var row=issueRow(candidate);
            if(cells.stream().anyMatch(c->c.price().compareTo(candidate.price())!=0)) {
                for(var cell:cells)add(issues,"DUPLICATE_PRICE_CONFLICT","BLOCK",issueRow(cell),cell.key().month());
                changed++;continue;
            }
            List<Cell> prior=existing.getOrDefault(candidate.key(),List.of());
            boolean conflict=!prior.isEmpty()&&prior.stream().anyMatch(c->c.price().compareTo(prior.getFirst().price())!=0);
            if(conflict)add(issues,"CURRENT_PRICE_CONFLICT","BLOCK",row,candidate.key().month());
            boolean equal=!prior.isEmpty()&&!conflict&&candidate.price().compareTo(prior.getFirst().price())==0;
            if(equal)unchanged++;
            else {
                changed++;
                if(now!=null&&month(candidate.key().month()).isBefore(now))add(issues,"HISTORICAL_MONTH","REVIEW",row,candidate.key().month());
                if(!prior.isEmpty()) {
                    add(issues,"PRICE_OVERRIDE","REVIEW",row,candidate.key().month());
                    if(!Boolean.TRUE.equals(meta.get("revision")))add(issues,"PRICE_REVISION_REQUIRED","REVIEW",row,candidate.key().month());
                }
                var historyMonths=historyByIdentity.get(candidate.key().identity());
                var historyEntry=historyMonths==null?null:historyMonths.floorEntry(candidate.key().month());
                Cell history=historyEntry==null?null:historyEntry.getValue().getFirst();
                boolean known=historyMonths!=null;
                if(known&&history==null)add(issues,"PRICE_BASELINE_MISSING","REVIEW",row,candidate.key().month());
                if(!known) {
                    boolean sameSku=knownSkus.contains(candidate.key().identity().sku());
                    add(issues,sameSku?"IDENTITY_CHANGED":"NEW_IDENTIFIER","REVIEW",row,candidate.key().month());
                }
                if(history!=null) {
                    if(legacyKeys.contains(history.key()))add(issues,"LEGACY_UNVERIFIED","REVIEW",row,candidate.key().month());
                    var selected=existing.get(history.key());
                    boolean historyConflict=selected.stream().anyMatch(c->c.price().compareTo(selected.getFirst().price())!=0);
                    if(historyConflict&&!conflict)add(issues,"CURRENT_PRICE_CONFLICT","BLOCK",row,candidate.key().month());
                    conflict|=historyConflict;
                }
                if(!conflict&&history!=null) {
                    BigDecimal old=history.price(),delta=candidate.price().subtract(old).abs();
                    // Multiplication avoids percentage rounding and division by zero.
                    if(old.signum()<=0||delta.compareTo(limits.get("priceChangeCny"))>0&&
                        delta.multiply(new BigDecimal("100")).compareTo(old.multiply(limits.get("priceChangePercent")))>0)
                        add(issues,"PRICE_CHANGE_HIGH","REVIEW",row,candidate.key().month());
                }
            }
            String groupKey=canonical(List.of(candidate.key().identity().sku(),candidate.key().identity().warehouseKey(),
                candidate.key().identity().type(),candidate.key().identity().unit()));
            var target=normalized.computeIfAbsent(groupKey,k->{
                var result=new LinkedHashMap<String,Object>();result.put("sku",candidate.key().identity().sku());result.put("warehouse",candidate.warehouse());
                result.put("warehouseId",candidate.warehouseId());result.put("identifierType",candidate.key().identity().type());result.put("unit",candidate.key().identity().unit());
                result.put("prices",new TreeMap<String,BigDecimal>());result.put("row",candidate.row());return result;
            });
            for(var evidence:cells) {
                if(evidence.warehouse().compareTo(text(target.get("warehouse")))<0)target.put("warehouse",evidence.warehouse());
                target.put("row",earliestRow(target.get("row"),evidence.row()));
            }
            @SuppressWarnings("unchecked") var prices=(Map<String,BigDecimal>)target.get("prices");
            prices.put(candidate.key().month(),candidate.price().stripTrailingZeros());
        }
        if(semantic.isEmpty())add(issues,"EMPTY_COSTS","BLOCK",null,"");
        boolean blocked=issues.stream().anyMatch(i->"BLOCK".equals(i.get("severity")));
        boolean review=blocked||issues.stream().anyMatch(i->"REVIEW".equals(i.get("severity")));
        boolean high=issues.stream().anyMatch(i->Set.of("PRICE_CHANGE_HIGH","HISTORICAL_MONTH","IDENTITY_CHANGED").contains(i.get("code")));
        var result=new LinkedHashMap<String,Object>();result.put("issues",issues);
        result.put("semanticHash",hash(canonical(List.of(text(meta.get("effectiveFrom")),text(meta.get("effectiveTo")),semantic))));
        result.put("changedCells",changed);result.put("unchangedCells",unchanged);result.put("requiresReview",review);result.put("blocked",blocked);
        result.put("risk",blocked?"BLOCKED":high?"HIGH":review?"REVIEW":"LOW");result.put("normalizedCosts",new ArrayList<>(normalized.values()));return result;
    }

    private static Cell cell(Map<String,Object> row,Map<String,Object> meta,Map<?,?> aliases,String month,BigDecimal price) {
        String warehouse=text(row.get("warehouse")),warehouseId=text(row.get("warehouseId"));
        if(warehouseId.isBlank())warehouseId=text(aliases.get(warehouse));
        var identity=new Identity(identifier(row.get("sku")),warehouseId.isBlank()?"name:"+warehouse:"id:"+warehouseId,
            text(value(row,meta,"identifierType")),text(value(row,meta,"unit")));
        return new Cell(new Key(identity,month),warehouse,warehouseId,price,row.getOrDefault("row",null));
    }
    private static Map<String,Object> issueRow(Cell cell) {
        var row=new LinkedHashMap<String,Object>();row.put("sku",cell.key().identity().sku());row.put("warehouse",cell.warehouse());row.put("row",cell.row());return row;
    }
    private static void add(List<Map<String,Object>> issues,String code,String severity,Map<String,Object> row,String month) {
        var issue=new LinkedHashMap<>(FinanceCostIssues.of(code));issue.put("severity",severity);
        issue.put("sku",row==null?"":identifier(row.get("sku")));issue.put("warehouse",row==null?"":text(row.get("warehouse")));
        issue.put("month",month);issue.put("row",row==null?null:row.get("row"));issues.add(issue);
    }
    private static Object earliestRow(Object left,Object right) {
        if(left==null)return right;if(right==null)return left;
        BigDecimal a=decimal(left),b=decimal(right);
        int order=a!=null&&b!=null?a.compareTo(b):text(left).compareTo(text(right));
        if(order==0)order=text(left).compareTo(text(right));
        if(order==0)order=left.getClass().getName().compareTo(right.getClass().getName());
        return order<=0?left:right;
    }
    private static Object value(Map<String,Object> row,Map<String,Object> meta,String name) {return row.containsKey(name)?row.get(name):meta.get(name);}
    private static boolean present(Map<String,Object> map,String name) {return !text(map.get(name)).isBlank();}
    private static String text(Object value) {return value==null?"":value.toString();}
    private static String identifier(Object value) {return text(value);}
    private static boolean formula(String value) {return value.stripLeading().startsWith("=")||value.equals("[FORMULA]");}
    private static boolean validIdentifier(Object value) {
        if(!(value instanceof CharSequence)&&!(value instanceof Number))return false;
        String raw=text(value),id=identifier(value);
        if(id.isBlank()||id.length()>255||formula(id)||raw.strip().matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)[eE][+-]?\\d+"))return false;
        if(value instanceof Number) {
            var number=decimal(value);
            return number!=null&&number.signum()>=0&&number.stripTrailingZeros().scale()<=0&&number.precision()-number.scale()<=15;
        }
        return true;
    }
    private static BigDecimal decimal(Object value) {
        if(value==null)return null;
        try{return new BigDecimal(value.toString());}catch(NumberFormatException e){return null;}
    }
    private static YearMonth month(Object value) {
        String text=text(value);if(!text.matches("\\d{4}-\\d{2}"))return null;
        try{return YearMonth.parse(text);}catch(java.time.DateTimeException e){return null;}
    }
    private static String canonical(Object value) {
        if(value instanceof Collection<?> values) {var result=new StringBuilder();for(var item:values){String encoded=canonical(item);result.append(encoded.length()).append(':').append(encoded);}return result.toString();}
        if(value instanceof BigDecimal number) {
            var normalized=number.stripTrailingZeros();
            // Preserve the existing encoding for ordinary costs; never expand an unbounded exponent.
            return normalized.scale()>=-18&&normalized.scale()<=18?normalized.toPlainString():normalized.toString();
        }
        return text(value);
    }
    private static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable",e);}
    }
}
