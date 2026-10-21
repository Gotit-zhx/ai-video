package com.yingdao.aicommerce.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Allowlisted financial columns. Raw responses, credentials and buyer details never leave the server. */
public final class FinanceFields {
    private final FinanceRepository repo;
    public FinanceFields(FinanceRepository repo){this.repo=repo;}
    public record Field(String id,String label,String type,String group,boolean cny,String views,String path){}
    public record Preferences(List<String> visible,List<String> exported){}
    private static Field core(String id,String label,String type){return new Field(id,label,type,"主要字段",false,"*","");}
    private static Field fx(String id,String label,String type){return new Field(id,label,type,"人民币折算",true,"*","");}
    private static Field extra(String id,String label,String type,String views,String path){return new Field(id,label,type,"接口扩展",false,views,path);}
    static final List<Field> CATALOG=List.of(
        core("record_date","业务日期","date"),core("platform","平台","text"),core("shop_id","店铺ID","text"),
        core("kind","类型","text"),core("external_id","记录编号","text"),core("order_id","订单编号","text"),
        core("statement_id","账单编号","text"),core("category","费用类别","text"),core("status","状态","text"),
        core("currency","币种","text"),core("amount","原币金额","money"),core("revenue","归因收入","money"),core("source","来源","text"),
        core("updated_at","最后同步时间","datetime"),
        new Field("detail_amount","结算明细金额","money","对账字段",false,"RECONCILIATION",""),
        new Field("detail_count","明细笔数","number","对账字段",false,"RECONCILIATION",""),
        new Field("difference","差异","money","对账字段",false,"RECONCILIATION",""),
        new Field("result","核对结果","text","对账字段",false,"RECONCILIATION",""),
        fx("cny_amount","折算人民币","money"),fx("cny_revenue","人民币归因收入","money"),
        fx("cny_rate","参考汇率","number"),fx("fx_time","汇率时间","datetime"),fx("fx_provider","汇率来源","text"),
        new Field("cny_detail_amount","人民币明细金额","money","人民币折算",true,"RECONCILIATION",""),
        new Field("cny_difference","人民币差异","money","人民币折算",true,"RECONCILIATION",""),
        extra("payment_method","支付方式","text","ORDER","payment_method|payment.payment_method_name"),
        extra("create_time","平台创建时间（UTC）","datetime","ORDER REFUND","create_time"),
        extra("update_time","平台更新时间（UTC）","datetime","ORDER REFUND","update_time"),
        extra("paid_time","支付时间（UTC）","datetime","ORDER","pay_time|paid_time"),
        extra("payout_time","收入释放时间（UTC）","datetime","BILL RECONCILIATION","actual_payout_time"),
        extra("item_count","商品行数","number","ORDER",""),
        extra("item_names","商品名称","text","ORDER",""),
        extra("actual_shipping","实际运费（原币）","money","ORDER","actual_shipping_fee"),
        extra("estimated_shipping","预计运费（原币）","money","ORDER","estimated_shipping_fee"),
        extra("tk_subtotal","TK 商品小计（原币）","money","ORDER","payment.sub_total"),
        extra("tk_shipping","TK 运费（原币）","money","ORDER","payment.shipping_fee"),
        extra("tk_seller_discount","TK 商家折扣（原币）","money","ORDER","payment.seller_discount"),
        extra("tk_platform_discount","TK 平台折扣（原币）","money","ORDER","payment.platform_discount"),
        extra("tk_tax","TK 税费（原币）","money","ORDER","payment.tax"),
        extra("refund_reason","退款原因","text","REFUND","reason|return_reason"),
        extra("refund_type","退款类型","text","REFUND","return_type"),
        extra("shopee_original","Shopee 商品原价（原币）","money","SETTLEMENT","order_income.original_price"),
        extra("shopee_seller_discount","Shopee 商家折扣（原币）","money","SETTLEMENT","order_income.seller_discount"),
        extra("shopee_voucher","Shopee 商家优惠券（原币）","money","SETTLEMENT","order_income.voucher_from_seller"),
        extra("shopee_platform_voucher","Shopee 平台优惠券（原币）","money","SETTLEMENT","order_income.voucher_from_shopee"),
        extra("shopee_buyer_paid","Shopee 买家支付（原币）","money","SETTLEMENT","order_income.buyer_total_amount"),
        extra("shopee_commission","Shopee 佣金（原币）","money","SETTLEMENT","order_income.commission_fee"),
        extra("shopee_service","Shopee 服务费（原币）","money","SETTLEMENT","order_income.service_fee"),
        extra("shopee_transaction","Shopee 支付手续费（原币）","money","SETTLEMENT","order_income.seller_transaction_fee"),
        extra("shopee_shipping","Shopee 最终运费（原币）","money","SETTLEMENT","order_income.final_shipping_fee"),
        extra("shopee_reverse_shipping","Shopee 逆向物流费（原币）","money","SETTLEMENT","order_income.reverse_shipping_fee"),
        extra("shopee_ams","Shopee 达人佣金（原币）","money","SETTLEMENT","order_income.order_ams_commission_fee"),
        extra("shopee_campaign","Shopee 活动费用（原币）","money","SETTLEMENT","order_income.campaign_fee"),
        extra("shopee_tax","Shopee 代扣税（原币）","money","SETTLEMENT","order_income.withholding_tax"),
        extra("shopee_fbs","Shopee 履约费用（原币）","money","SETTLEMENT","order_income.fbs_fee"),
        extra("shopee_processing","Shopee 订单处理费（原币）","money","SETTLEMENT","order_income.seller_order_processing_fee"),
        extra("shopee_adjustment","Shopee 调整后结算净额（原币）","money","SETTLEMENT","order_income.escrow_amount_after_adjustment"),
        extra("tk_revenue","TK 交易收入（原币）","money","SETTLEMENT","revenue_amount"),
        extra("tk_fee_tax","TK 费用及税（原币，平台符号）","money","SETTLEMENT","fee_tax_amount"),
        extra("tk_shipping_cost","TK 物流费用（原币，平台符号）","money","SETTLEMENT","shipping_cost_amount"),
        extra("tk_adjustment","TK 调整金额（原币，平台符号）","money","SETTLEMENT","adjustment_amount"),
        extra("advertiser_id","广告账户ID","text","AD AD_GMV","advertiser_id"),
        extra("impressions","曝光量","number","AD",""),extra("clicks","点击量","number","AD",""),
        extra("conversions","转化 / SKU 订单数","number","AD AD_GMV",""),
        extra("net_cost","GMV Max 净消耗（原币）","money","AD_GMV","metrics.net_cost")
    );
    static final List<String> DEFAULT_EXPORT=List.of("record_date","platform","shop_id","kind","external_id","order_id","statement_id","category","status","currency","amount","revenue","source","cny_amount","cny_revenue","cny_rate","fx_time","fx_provider");
    static final List<String> DEFAULT_VISIBLE=List.of("record_date","shop_id","external_id","order_id","category","status","currency","amount","cny_amount");
    static final List<String> RECON_VISIBLE=List.of("record_date","shop_id","external_id","source","currency","amount","detail_amount","difference","cny_difference","result");
    static final List<String> RECON_EXPORT=List.of("record_date","shop_id","external_id","source","currency","amount","detail_amount","detail_count","difference","result","cny_amount","cny_detail_amount","cny_difference","cny_rate");
    public static String view(String key){
        key=text(key).toUpperCase(Locale.ROOT);if(key.isBlank())return "ALL";
        if(!KINDS.contains(key)&&!Set.of("ALL","RECONCILIATION").contains(key))throw bad("字段设置的数据类型不正确");return key;
    }
    public static List<Field> catalog(String key){
        String v=view(key);return CATALOG.stream().filter(f->f.views.equals("*")||!f.views.equals("RECONCILIATION")&&v.equals("ALL")
                ||Arrays.asList(f.views.split(" ")).contains(v)).toList();
    }
    static Preferences defaults(String key){
        return key.equals("RECONCILIATION")?new Preferences(RECON_VISIBLE,RECON_EXPORT):new Preferences(DEFAULT_VISIBLE,DEFAULT_EXPORT);
    }
    public Preferences get(Scope s,String key){
        key=view(key);
        var saved=repo.jdbc().queryForList("SELECT preferences_json FROM finance_view_preferences WHERE organization_id=? AND user_id=? AND view_key=?",s.organizationId(),s.userId(),key);
        if(saved.isEmpty())return defaults(key);
        var value=repo.read(text(saved.getFirst().get("preferences_json")));
        return validate(key,strings(value.get("visible")),strings(value.get("exported")));
    }
    public Map<String,Object> settings(Scope s,String key){
        key=view(key);var prefs=get(s,key);
        return Map.of("view",key,"fields",catalog(key),"visible",prefs.visible,"exported",prefs.exported,"defaults",defaults(key));
    }
    public void save(Scope s,String key,List<String> visible,List<String> exported){
        key=view(key);var prefs=validate(key,visible,exported);
        repo.jdbc().update("INSERT INTO finance_view_preferences(organization_id,user_id,view_key,preferences_json) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE preferences_json=VALUES(preferences_json),updated_at=UTC_TIMESTAMP(6)",
                s.organizationId(),s.userId(),key,repo.write(prefs));
    }
    static Preferences validate(String key,List<String> visible,List<String> exported){
        Set<String> allowed=new HashSet<>(catalog(key).stream().map(Field::id).toList());
        for(var selection:Arrays.asList(visible,exported)){
            if(selection==null||selection.isEmpty()||selection.size()>CATALOG.size()||new HashSet<>(selection).size()!=selection.size()||!allowed.containsAll(selection))
                throw bad("显示和导出各需至少选择一个有效字段，字段不能重复");
            if(selection.stream().allMatch(id->id.startsWith("cny_")||id.startsWith("fx_")))throw bad("请至少选择一个非人民币字段");
        }
        return new Preferences(List.copyOf(visible),List.copyOf(exported));
    }
    static List<String> strings(Object value){return value instanceof List<?> l?l.stream().map(FinanceData::text).toList():List.of();}
    public List<Field> selected(Scope s,String key,boolean exported,boolean cny){
        var prefs=get(s,view(key));var catalog=catalog(key);var ids=exported?prefs.exported:prefs.visible;
        return ids.stream().map(id->catalog.stream().filter(f->f.id.equals(id)).findFirst().orElseThrow()).filter(f->cny||!f.cny).toList();
    }
    static Set<String> numericLabels(){
        var labels=new HashSet<>(List.of("金额","账单金额","人民币账单金额"));
        CATALOG.stream().filter(f->Set.of("money","number").contains(f.type)).map(Field::label).forEach(labels::add);return labels;
    }
    static void enrich(Map<String,Object> row,Map<String,Object> raw){
        if(!"API".equals(row.get("source")))return;
        String kind=text(row.get("kind")),platform=text(row.get("platform"));
        for(var field:catalog(kind)){
            if(field.path.isEmpty()||field.id.startsWith("shopee_")&&!platform.equals("SHOPEE")
                    ||field.id.startsWith("tk_")&&!platform.equals("TIKTOK_SHOP"))continue;
            Object value=null;
            for(String path:field.path.split("\\|")){value=at(raw,path);if(value!=null)break;}
            if(Set.of("money","number").contains(field.type))value=decimal(value);
            else if(value instanceof Map<?,?>||value instanceof Collection<?>)continue;
            else if(field.type.equals("datetime")){
                String t=text(value);try{value=t.matches("\\d{10,13}")?Instant.ofEpochSecond(Long.parseLong(t)/(t.length()>10?1000:1)).toString():null;}
                catch(RuntimeException e){value=null;}
            }else if(value!=null)value=text(value).substring(0,Math.min(2000,text(value).length()));
            if(value!=null)row.put(field.id,value);
        }
        if(kind.equals("ORDER")){
            Object items=raw.get(platform.equals("SHOPEE")?"item_list":"line_items");
            if(items instanceof List<?> list){
                row.put("item_count",list.size());
                String names=String.join(" / ",rows(items).stream().map(r->first(r,"item_name","product_name")).filter(n->!n.isBlank()).distinct().toList());
                row.put("item_names",names.substring(0,Math.min(6000,names.length())));
            }
        }
    }
    static String cell(Map<String,Object> row,Field field){
        Object value=switch(field.id){case "fx_time"->at(row,"fx.time");case "fx_provider"->at(row,"fx.provider");default->row.get(field.id);};
        return value instanceof BigDecimal decimal?decimal.toPlainString():text(value);
    }
}
