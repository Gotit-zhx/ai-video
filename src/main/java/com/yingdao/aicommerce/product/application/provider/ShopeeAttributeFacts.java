package com.yingdao.aicommerce.product.application.provider;
import java.util.*;

/** Fast exact multilingual source matching; absence never implies a negative fact. */
final class ShopeeAttributeFacts {
    static List<?> resolve(List<?> existing,List<?> definitions,Map<String,Object> source,String title) {
        Map<String,List<String>> facts=new HashMap<>();
        for(String key:List.of("attributes","productAttributes","product_attributes","productProperties","product_properties"))
            collect(source.get(key),facts);
        List<Object> result=new ArrayList<>(existing);
        for(Object raw:definitions) {
            if(!(raw instanceof Map<?,?> d)||ShopeeAttributeResolver.has(result,d.get("attribute_id")))continue;
            String property=property(String.valueOf(d.get("name")));
            List<String> candidates=facts.getOrDefault(property,List.of());
            if(candidates.isEmpty() && property.equals("plussize") && facts.containsKey("sizetype")) {
                var sizes=facts.get("sizetype");
                if(sizes.stream().allMatch(v->Set.of("regular","biasa","常规").contains(norm(v))))candidates=List.of("No");
                else if(sizes.stream().allMatch(v->Set.of("plussize","大码").contains(norm(v))))candidates=List.of("Yes");
            }
            if(candidates.isEmpty() && (property.equals("neckline")||property.equals("sleevelength"))) {
                String t=norm(title);
                List<String> names=new ArrayList<>();
                List<String> choices=property.equals("neckline")?List.of("Round Neck","V Neck","Turtle Neck"):
                        List.of("Short Sleeves","Long Sleeves","Sleeveless");
                for(String choice:choices) {
                    String n=norm(choice);
                    if(t.contains(n)||t.contains(n.replace("sleeves","sleeve")))names.add(choice);
                }
                candidates=names;
            }
            if(candidates.isEmpty() || !(d.get("attribute_value_list") instanceof List<?> options))continue;
            Set<String> normalized=new LinkedHashSet<>();for(String c:candidates)normalized.add(value(property,c));
            if(normalized.size()!=1)continue; // Conflicting source fields require review, not arbitrary selection.
            List<Map<?,?>> matches=new ArrayList<>();
            for(Object o:options)if(o instanceof Map<?,?> option && normalized.contains(value(property,String.valueOf(option.get("name")))))matches.add(option);
            if(matches.size()!=1)continue;
            var option=matches.getFirst();
            result.removeIf(v->v instanceof Map<?,?> m && String.valueOf(d.get("attribute_id")).equals(String.valueOf(m.get("attribute_id"))));
            result.add(Map.of("attribute_id",d.get("attribute_id"),"attribute_value_list",
                    List.of(Map.of("value_id",option.get("value_id"),"original_value_name",option.get("name")))));
        }
        return result;
    }
    private static void collect(Object raw,Map<String,List<String>> out) {
        if(raw instanceof List<?> l){l.forEach(v->collect(v,out));return;}
        if(!(raw instanceof Map<?,?> m))return;
        Object label=m.get("name");if(label==null)label=m.get("attribute_name");
        if(label!=null){Object v=m.containsKey("value")?m.get("value"):m.get("values");add(out,String.valueOf(label),v);return;}
        m.forEach((k,v)->add(out,String.valueOf(k),v));
    }
    private static void add(Map<String,List<String>> out,String key,Object raw) {
        if(raw instanceof List<?> l){l.forEach(v->add(out,key,v));return;}
        if(raw instanceof Map<?,?> m){for(String field:List.of("name","value_name","value","values"))if(m.containsKey(field)){add(out,key,m.get(field));return;}return;}
        if(raw instanceof String s&&!s.isBlank())out.computeIfAbsent(property(key),k->new ArrayList<>()).add(s);
    }
    private static String property(String s) {
        return switch(norm(s)){
            case "garisleher","领型","领口" -> "neckline";
            case "panjanglenganbaju","panjanglengan","袖长" -> "sleevelength";
            case "jenissaiz","尺码类型" -> "sizetype";
            case "bahan","fabric","材质","面料" -> "material";
            case "是否短款","短款上衣" -> "croppedtop";
            case "是否大码" -> "plussize";
            default -> norm(s);
        };
    }
    private static String value(String p,String s) {
        String v=norm(s);
        if(p.equals("neckline"))return switch(v){case "leherkrew","crewneck","圆领"->"roundneck";case "v领","vneck"->"vneck";default->v;};
        if(p.equals("sleevelength"))return switch(v){case "lenganpendek","shortsleeve","短袖"->"shortsleeves";case "lenganpanjang","longsleeve","长袖"->"longsleeves";case "tanpalengan","无袖"->"sleeveless";default->v;};
        if(p.equals("material"))return switch(v){case "poliester","涤纶","聚酯纤维"->"polyester";case "kapas","棉"->"cotton";default->v;};
        if(p.equals("croppedtop")||p.equals("plussize"))return switch(v){case "是","ya"->"yes";case "否","tidak"->"no";default->v;};
        return v;
    }
    private static String norm(String s){return (s==null?"":s).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
}
