package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.domain.CommerceDesignDraft;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.*;

/** Internal orchestration, not a public API. Caller supplies authorized, archived source images. */
@Service
public class CommerceDesignGenerationCoordinator {
    public record PreparedReference(String imageId,String storageKey) {}
    private final CommerceDesignAttemptLedger ledger;private final CommerceDesignImageGateway gateway;private final ObjectMapper json;
    private final CommerceImageArchive archive;
    public CommerceDesignGenerationCoordinator(CommerceDesignAttemptLedger ledger,CommerceDesignImageGateway gateway,ObjectMapper json,CommerceImageArchive archive){this.ledger=ledger;this.gateway=gateway;this.json=json;this.archive=archive;}
    public String execute(CommerceDesignDraft draft,List<PreparedReference> references,ModelConfigService.RuntimeModelConnection model){
        if(model==null)throw new IllegalArgumentException("缺少已确认的图片模型配置");
        if(draft==null||!"DRAFT".equals(draft.status())||references==null||references.isEmpty()||references.size()>4)throw new IllegalArgumentException("需要草稿与1至4张归档参考图");
        var sourceIds=new HashSet<String>();for(var node:json.readTree(draft.sourceImagesJson()))sourceIds.add(node.path("imageId").asText());
        var manifest=new ArrayList<CommerceDesignAttemptLedger.ReferenceManifest>();
        var images=new ArrayList<CommerceDesignImageGateway.Reference>();
        for(var reference:references){
            if(reference==null||!sourceIds.contains(reference.imageId()))throw new IllegalArgumentException("参考图片不属于当前草稿");
            var image=archive.read(draft.organizationId(),reference.storageKey());images.add(image);
            try{var bytes=Base64.getDecoder().decode(image.dataBase64());if(bytes.length==0)throw new IllegalArgumentException("参考图为空");
                manifest.add(new CommerceDesignAttemptLedger.ReferenceManifest(reference.imageId(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),reference.storageKey()));
            }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        }
        var attempt=ledger.prepare(draft.organizationId(),draft.id(),manifest);
        if(!ledger.claim(draft.organizationId(),attempt.id()))return attempt.id();
        try{
            var response=gateway.generate(draft,List.copyOf(images),attempt.id(),model);
            ledger.received(draft.organizationId(),attempt.id(),response);
        }catch(RuntimeException failure){
            ledger.uncertain(draft.organizationId(),attempt.id());
            throw new IllegalStateException("生成结果需要核查，请勿自动重试该设计",failure);
        }
        return attempt.id();
    }
}
