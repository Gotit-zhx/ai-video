package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Existing batches keep their versions and results, but no longer read editable collection rows. */
public class V114__freeze_legacy_commerce_sources extends BaseJavaMigration {
    private final ObjectMapper json=new ObjectMapper();

    @Override public void migrate(Context context) {
        var db=new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(),true));
        for(var batch:db.queryForList("SELECT id,organization_id FROM commerce_batches ORDER BY id")) {
            String org=text(batch.get("organization_id")),id=text(batch.get("id"));
            var remapping=new HashMap<String,String>();
            for(var source:db.queryForList("""
                    SELECT DISTINCT c.* FROM product_captures c
                    JOIN commerce_batch_items i ON i.capture_id=c.id AND i.organization_id=c.organization_id
                    WHERE i.organization_id=? AND i.batch_id=? AND c.data_module<>'FISSION'
                    """,org,id)) {
                String old=text(source.get("id"));
                var cloned=cloneSource(db,source,id);
                remapping.putAll(cloned);
                db.update("UPDATE commerce_batch_items SET capture_id=? WHERE organization_id=? AND batch_id=? AND capture_id=?",
                        cloned.get(old),org,id,old);
                db.update("""
                        UPDATE commerce_batch_artwork_jobs j
                        JOIN commerce_batch_executions e ON e.id=j.execution_id AND e.organization_id=j.organization_id
                        SET j.capture_id=? WHERE j.organization_id=? AND e.batch_id=? AND j.capture_id=?
                        """,cloned.get(old),org,id,old);
            }
            rewrite(db,"commerce_batch_creative_plans","plan_json","organization_id=? AND batch_id=?",remapping,org,id);
            rewrite(db,"commerce_batch_item_publish_options","request_json","organization_id=? AND batch_id=?",remapping,org,id);
        }
        for(var group:db.queryForList("""
                SELECT MIN(d.id) AS group_id,d.organization_id,d.capture_id,d.request_key
                FROM commerce_design_drafts d
                JOIN product_captures c ON c.id=d.capture_id AND c.organization_id=d.organization_id
                WHERE c.data_module='CAPTURE' GROUP BY d.organization_id,d.capture_id,d.request_key
                """)) {
            String org=text(group.get("organization_id")),old=text(group.get("capture_id")),key=text(group.get("request_key"));
            var source=db.queryForMap("SELECT * FROM product_captures WHERE id=? AND organization_id=?",old,org);
            var remapping=cloneSource(db,source,text(group.get("group_id")));
            db.update("UPDATE product_captures SET data_module='ASSETS' WHERE id=? AND organization_id=?",remapping.get(old),org);
            rewrite(db,"commerce_design_drafts","source_images_json","organization_id=? AND request_key=?",remapping,org,key);
            db.update("UPDATE commerce_design_drafts SET capture_id=? WHERE organization_id=? AND request_key=?",
                    remapping.get(old),org,key);
        }
    }

    private Map<String,String> cloneSource(JdbcTemplate db,Map<String,Object> source,String group) {
        String org=text(source.get("organization_id")),old=text(source.get("id")),id=UUID.randomUUID().toString();
        var ids=new HashMap<String,String>();ids.put(old,id);
        var pictures=db.queryForList("SELECT * FROM product_capture_images WHERE organization_id=? AND capture_id=?",org,old);
        for(var picture:pictures) ids.put(text(picture.get("id")),UUID.randomUUID().toString());
        var copy=new LinkedHashMap<>(source);
        copy.put("id",id);copy.put("data_module","FISSION");
        copy.put("idempotency_key","module-source-"+id);copy.put("deleted_at",null);
        copy.put("normalized_data",remapJson(source.get("normalized_data"),ids));
        insert(db,"product_captures",copy);
        for(var picture:pictures) {
            var row=new LinkedHashMap<>(picture);
            row.put("id",ids.get(text(picture.get("id"))));row.put("capture_id",id);
            String processed=text(picture.get("processed_url"));
            String prefix="/api/v1/product-watermark/assets/";
            if(processed!=null && processed.startsWith(prefix)) {
                var output=db.queryForList("""
                        SELECT output_key,JSON_UNQUOTE(JSON_EXTRACT(detection_json,'$.imageType')) AS image_type
                        FROM product_watermark_checks
                        WHERE id=? AND organization_id=? AND capture_id=? AND image_id=? AND status='READY'
                        """,processed.substring(prefix.length()),org,old,picture.get("id"));
                if(output.size()!=1)throw new IllegalStateException("Legacy processed image is not verified: "+picture.get("id"));
                row.put("processed_url","/api/v1/commerce-publish-images/"+output.getFirst().get("output_key"));
                if("SIZE_CHART".equals(output.getFirst().get("image_type")))row.put("role_name","SIZE_CHART");
            }
            insert(db,"product_capture_images",row);
            db.update("INSERT INTO commerce_source_copy_images(organization_id,capture_id,source_image_id,image_id) VALUES(?,?,?,?)",
                    org,id,picture.get("id"),row.get("id"));
        }
        for(String table:List.of("product_capture_skus","product_capture_field_sources","product_capture_videos")) {
            for(var child:db.queryForList("SELECT * FROM "+table+" WHERE organization_id=? AND capture_id=?",org,old)) {
                var row=new LinkedHashMap<>(child);row.put("id",UUID.randomUUID().toString());row.put("capture_id",id);
                insert(db,table,row);
            }
        }
        for(var child:db.queryForList("SELECT * FROM commerce_capture_source_archives WHERE organization_id=? AND capture_id=?",org,old)) {
            var row=new LinkedHashMap<>(child);row.put("capture_id",id);
            String image=ids.get(text(row.get("image_id")));
            if(image!=null) {row.put("image_id",image);insert(db,"commerce_capture_source_archives",row);}
        }
        db.update("INSERT INTO commerce_source_copies(organization_id,batch_id,source_capture_id,capture_id) VALUES(?,?,?,?)",org,group,old,id);
        return ids;
    }

    private void rewrite(JdbcTemplate db,String table,String column,String where,Map<String,String> ids,Object... arguments) {
        if(ids.isEmpty())return;
        // Some scoped tables have composite keys, so update through their complete row identity.
        for(var row:db.queryForList("SELECT * FROM "+table+" WHERE "+where,arguments)) {
            Object value=row.get(column);
            if(value==null)continue;
            String rewritten=remapJson(value,ids);
            if(rewritten.equals(text(value)))continue;
            var keys=table.equals("commerce_batch_item_publish_options")?List.of("organization_id","batch_id","item_id"):List.of("id");
            var parameters=new ArrayList<Object>();parameters.add(rewritten);
            keys.forEach(key->parameters.add(row.get(key)));
            db.update("UPDATE "+table+" SET "+column+"=? WHERE "+String.join(" AND ",keys.stream().map(key->key+"=?").toList()),parameters.toArray());
        }
    }

    private void insert(JdbcTemplate db,String table,Map<String,Object> row) {
        var columns=new ArrayList<>(row.keySet());
        if(columns.stream().anyMatch(column->!column.matches("[a-z_][a-z_0-9]*")))throw new IllegalArgumentException("Invalid database column");
        db.update("INSERT INTO "+table+" ("+String.join(",",columns)+") VALUES ("+
                String.join(",",Collections.nCopies(columns.size(),"?"))+")",columns.stream().map(row::get).toArray());
    }

    private String remapJson(Object value,Map<String,String> ids) {
        return json.writeValueAsString(remap(json.readValue(text(value),Object.class),ids));
    }

    private static Object remap(Object value,Map<String,String> ids) {
        if(value instanceof String string)return ids.getOrDefault(string,string);
        if(value instanceof List<?> list)return list.stream().map(item->remap(item,ids)).toList();
        if(value instanceof Map<?,?> map) {
            var result=new LinkedHashMap<String,Object>();
            map.forEach((key,item)->result.put(String.valueOf(key),remap(item,ids)));
            return result;
        }
        return value;
    }

    private static String text(Object value) {
        return value instanceof byte[] bytes?new String(bytes,StandardCharsets.UTF_8):value==null?null:String.valueOf(value);
    }
}
