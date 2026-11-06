package com.yingdao.aicommerce.product.application.provider;

import com.yingdao.aicommerce.product.application.ProductPublishWorkflowService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded recovery of four verified pre-create failures. Old workflows/audits remain immutable. */
public final class ProductSizeChartRecovery {
    static final String ORG="6a61f788-6685-4b64-82b1-eec97ed8cf90";
    static final String SHOP="c4b49298-51b7-481e-adac-435498350642";
    static final Map<String,String> TARGETS=Map.of(
        "ae44751b-edb2-4030-a3d7-edb294fa2ff6","213e0be7-0023-4e10-af4b-2b5745722ea1",
        "fca9e899-98d9-4eb0-a244-ec0e5554da56","8442d59a-4a14-474b-88e8-c68e3595d305",
        "2fadb032-abbb-4242-a583-0fdae54966be","78cbbdf9-f6bf-4056-a6aa-de967611f6f1",
        "a6703c4f-6564-4ff1-94f0-a380004024ca","1020a452-9b4c-4b9c-a604-dca8e64e4079");
    static final ObjectMapper JSON=new ObjectMapper();
    static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    static String text(Object value){return value==null?"":value.toString();}
    static long number(Object value){return value instanceof Number n?n.longValue():Long.parseLong(text(value));}
    static boolean disabled(Object value){return Boolean.FALSE.equals(value)||value instanceof Number n&&n.intValue()==0;}
    static Map<String,Object> parse(Object value){return JSON.readValue(text(value),new TypeReference<LinkedHashMap<String,Object>>(){});}
    static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    static String fingerprint(String capture,long version,Map<String,Object> body)throws Exception{
        var service=new ProductPublishWorkflowService(null,null,null,null,null,null,null,JSON);
        var method=ProductPublishWorkflowService.class.getDeclaredMethod("fingerprint",String.class,long.class,Map.class);
        method.setAccessible(true);return (String)method.invoke(service,capture,version,body);
    }
    public static void main(String[] args)throws Exception{
        boolean apply=args.length==1&&"apply".equals(args[0]);
        require(apply||args.length==1&&"verify".equals(args[0]),"Use verify or apply");
        try(var ds=new com.zaxxer.hikari.HikariDataSource()){
            ds.setJdbcUrl(System.getenv("BACKFILL_JDBC"));ds.setUsername(System.getenv("BACKFILL_USER"));
            ds.setPassword(System.getenv("BACKFILL_PASSWORD"));ds.setMaximumPoolSize(1);
            ds.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout=5");
            var db=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setTimeout(30);
            tx.executeWithoutResult(status->{
                try{
                    require(db.queryForObject("SELECT COUNT(*) FROM shops WHERE id=? AND organization_id=? AND status='ACTIVE' AND platform='TIKTOK_SHOP'",Integer.class,SHOP,ORG)==1,"Original shop unavailable");
                    for(var target:new TreeMap<>(TARGETS).entrySet()){
                        String captureId=target.getKey(),oldId=target.getValue(),key="size-chart-repair-20261029:"+oldId;
                        var capture=db.queryForMap("SELECT status,version,normalized_data FROM product_captures WHERE organization_id=? AND id=? AND deleted_at IS NULL FOR UPDATE",ORG,captureId);
                        var already=db.queryForList("SELECT id,status FROM product_publish_workflows WHERE organization_id=? AND idempotency_key=?",ORG,key);
                        if(!already.isEmpty()){System.out.println(captureId+" ALREADY_RECOVERED "+already.getFirst().get("id")+" "+already.getFirst().get("status"));continue;}
                        var old=db.queryForMap("SELECT * FROM product_publish_workflows WHERE organization_id=? AND capture_id=? AND id=? AND shop_id=? FOR UPDATE",ORG,captureId,oldId,SHOP);
                        require("APPROVED".equals(capture.get("status"))&&number(capture.get("version"))==number(old.get("expected_capture_version")),"Capture changed: "+captureId);
                        require("FAILED".equals(old.get("status"))&&"PUBLISH".equals(old.get("current_step"))
                            &&disabled(old.get("publication_attempted"))&&old.get("publish_audit_id")==null
                            &&old.get("publication_target_key")==null&&text(old.get("error_message")).contains("尺码表"),"Unsafe old workflow: "+oldId);
                        require(db.queryForObject("""
                            SELECT COUNT(*) FROM product_publish_workflows WHERE organization_id=? AND capture_id=? AND shop_id=?
                              AND (status<>'FAILED' OR publication_attempted=TRUE OR publish_audit_id IS NOT NULL
                                OR publication_target_key IS NOT NULL OR error_message NOT LIKE '%尺码表%')
                            """,Integer.class,ORG,captureId,SHOP)==0,"Pair has another active/created publication");
                        var audits=db.queryForList("SELECT status,dry_run,external_id,error_message FROM product_publish_audits WHERE organization_id=? AND capture_id=? AND shop_id=?",ORG,captureId,SHOP);
                        require(!audits.isEmpty(),"Missing original audit");
                        for(var audit:audits)require("FAILED".equals(audit.get("status"))&&disabled(audit.get("dry_run"))
                            &&text(audit.get("external_id")).isBlank()&&text(audit.get("error_message")).contains("尺码表"),"Existing uncertain/successful audit");
                        require(db.queryForObject("""
                            SELECT COUNT(*) FROM product_image_processing_jobs WHERE organization_id=? AND capture_id=? AND id=? AND status='SUCCEEDED'
                            """,Integer.class,ORG,captureId,old.get("image_job_id"))==1,"Original image processing incomplete");
                        var lines=TikTokProductSizeChart.sourceAttributeLines(parse(capture.get("normalized_data")).get("attributes"));
                        require(lines.size()==6,"Missing verified source measurements");
                        var request=parse(old.get("request_json"));
                        require("LIVE".equals(request.get("executionMode"))&&SHOP.equals(request.get("shopId"))
                            &&"TIKTOK".equals(request.get("destinationPlatform"))&&"TIKTOK".equals(old.get("destination_platform"))
                            &&Boolean.TRUE.equals(request.get("confirmed")),"Original request mismatch");
                        request.put("idempotencyKey",key);
                        String id=UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
                        long version=number(capture.get("version"));
                        db.update("""
                            INSERT INTO product_publish_workflows(id,organization_id,capture_id,created_by,idempotency_key,
                              request_fingerprint,publication_target_key,destination_platform,shop_id,capture_version,
                              expected_capture_version,request_json,status,current_step,image_job_id,
                              publication_attempted,attempt_count,retry_count,next_attempt_at,created_at,updated_at)
                            VALUES(?,?,?,?,?,?,?,'TIKTOK',?,?,?,?,'PENDING','PUBLISH',?,FALSE,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                            """,id,ORG,captureId,old.get("created_by"),key,fingerprint(captureId,version,request),
                                hash(ORG+":"+captureId+":TIKTOK:"+SHOP),SHOP,version,version,JSON.writeValueAsString(request),old.get("image_job_id"));
                        System.out.println(captureId+" "+(apply?"QUEUED":"VERIFIED_ROLLBACK")+" "+id+" SOURCE_SIZE_ROWS="+(lines.size()-1));
                    }
                    if(!apply)status.setRollbackOnly();
                }catch(Exception e){throw new IllegalStateException("Recovery stopped: "+e.getMessage(),e);}
            });
        }
    }
}
