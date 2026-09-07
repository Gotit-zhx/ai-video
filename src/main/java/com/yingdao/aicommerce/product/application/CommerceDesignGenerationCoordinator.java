package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.domain.CommerceDesignDraft;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.*;

/** Internal orchestration, not a public API. Caller supplies authorized, archived source images. */
@Service
public class CommerceDesignGenerationCoordinator {
    public record PreparedReference(String imageId,String storageKey,CommerceDesignImageGateway.Reference image) {}
    private final CommerceDesignAttemptLedger ledger;private final CommerceDesignImageGateway gateway;private final ObjectMapper json;
    public CommerceDesignGenerationCoordinator(CommerceDesignAttemptLedger ledger,CommerceDesignImageGateway gateway,ObjectMapper json){this.ledger=ledger;this.gateway=gateway;this.json=json;}
    public String execute(CommerceDesignDraft draft,List<PreparedReference> references){
        if(draft==null||!"DRAFT".equals(draft.status())||references==null||references.isEmpty()||references.size()>4)throw new IllegalArgumentException("需要草稿与1至4张归档参考图");
        var sourceIds=new HashSet<String>();for(var node:json.readTree(draft.sourceImagesJson()))sourceIds.add(node.path("imageId").asText());
        var manifest=new ArrayList<CommerceDesignAttemptLedger.ReferenceManifest>();
        for(var reference:references){
            if(reference==null||!sourceIds.contains(reference.imageId())||reference.image()==null||reference.image().dataBase64()==null||reference.image().dataBase64().length()>12_000_000)throw new IllegalArgumentException("参考图片不属于当前草稿或内容无效");
            try{var bytes=Base64.getDecoder().decode(reference.image().dataBase64());if(bytes.length==0)throw new IllegalArgumentException("参考图为空");
                manifest.add(new CommerceDesignAttemptLedger.ReferenceManifest(reference.imageId(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),reference.storageKey()));
            }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        }
        var attempt=ledger.prepare(draft.organizationId(),draft.id(),manifest);
        if(!ledger.claim(draft.organizationId(),attempt.id()))return attempt.id();
        try{
            var response=gateway.generate(draft,references.stream().map(PreparedReference::image).toList(),attempt.id());
            ledger.received(draft.organizationId(),attempt.id(),response);
        }catch(RuntimeException failure){
            ledger.uncertain(draft.organizationId(),attempt.id());
            throw new IllegalStateException("生成结果需要核查，请勿自动重试该设计",failure);
        }
        return attempt.id();
    }
}
