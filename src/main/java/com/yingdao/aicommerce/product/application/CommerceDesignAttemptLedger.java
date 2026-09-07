package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.domain.CommerceDesignAttempt;
import com.yingdao.aicommerce.product.mapper.CommerceDesignAttemptMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** Internal worker ledger. Each public mutation commits separately from slow external calls. */
@Service
public class CommerceDesignAttemptLedger {
    public record ReferenceManifest(String imageId,String contentSha256,String storageKey) {}
    private final CommerceDesignAttemptMapper mapper;private final ObjectMapper json;
    public CommerceDesignAttemptLedger(CommerceDesignAttemptMapper mapper,ObjectMapper json){this.mapper=mapper;this.json=json;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public CommerceDesignAttempt prepare(String org,String design,List<ReferenceManifest> references){
        if(org==null||org.isBlank()||design==null||design.isBlank()||references==null||references.isEmpty()||references.size()>4)throw new IllegalArgumentException("需要设计与1至4张已归档参考图");
        var ids=new HashSet<String>();
        for(var r:references)if(r==null||r.imageId()==null||r.imageId().isBlank()||!ids.add(r.imageId())||r.contentSha256()==null||!r.contentSha256().matches("[a-f0-9]{64}")||r.storageKey()==null||r.storageKey().isBlank())throw new IllegalArgumentException("参考图须包含唯一图片ID、内容哈希及归档键");
        String manifest=json.writeValueAsString(references);var existing=mapper.byDesign(org,design);
        if(existing!=null){if(!json.readTree(manifest).equals(json.readTree(existing.referenceManifestJson())))throw new IllegalStateException("设计已绑定不同参考图，不能重用生成尝试");return existing;}
        var now=Instant.now();var row=new CommerceDesignAttempt(UUID.randomUUID().toString(),org,design,"PREPARED",manifest,null,null,now,now);mapper.insert(row);return row;
    }
    /** Only the winner may call the model. A crash after this commit needs reconciliation, never automatic resend. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean claim(String org,String id){return mapper.transition(org,id,"PREPARED","SUBMITTING",null,null)==1;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void received(String org,String id,CommerceDesignImageGateway.Response result){
        if(result==null||((result.artifact()==null||result.artifact().dataBase64()==null||result.artifact().dataBase64().isBlank())&&(result.result()==null||result.result().url()==null||result.result().url().isBlank())))throw new IllegalArgumentException("缺少可保存的生成结果");
        if(mapper.transition(org,id,"SUBMITTING","RESULT_RECEIVED",json.writeValueAsString(result),null)!=1)throw new IllegalStateException("尝试状态已变化，不能覆盖已有结果");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void uncertain(String org,String id){
        mapper.transition(org,id,"SUBMITTING","UNKNOWN",null,"PROVIDER_OUTCOME_UNKNOWN");
    }
}
