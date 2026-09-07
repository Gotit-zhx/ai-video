package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.domain.CommerceDesignDraft;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
/** Internal operation; caller must verify draft access before fetching its snapshot URLs. */
@Service
public class CommerceDesignReferencePreparer {
    private final MarketplaceImageDownloader downloader;private final CommerceImageArchive archive;private final ObjectMapper json;
    public CommerceDesignReferencePreparer(MarketplaceImageDownloader downloader,CommerceImageArchive archive,ObjectMapper json){this.downloader=downloader;this.archive=archive;this.json=json;}
    public List<CommerceDesignGenerationCoordinator.PreparedReference> prepare(CommerceDesignDraft draft,List<String> imageIds){
        if(draft==null||imageIds==null||imageIds.isEmpty()||imageIds.size()>4||new HashSet<>(imageIds).size()!=imageIds.size())throw new IllegalArgumentException("请明确选择1至4张不重复的来源参考图");
        Map<String,String> urls=new HashMap<>();for(var image:json.readTree(draft.sourceImagesJson()))urls.put(image.path("imageId").asText(),image.path("originalUrl").asText());
        if(imageIds.stream().anyMatch(id->!urls.containsKey(id)))throw new IllegalArgumentException("图片不属于草稿来源");
        var result=new ArrayList<CommerceDesignGenerationCoordinator.PreparedReference>();
        for(String id:imageIds){var binary=downloader.download(urls.get(id));var stored=archive.store(draft.organizationId(),binary.bytes());result.add(new CommerceDesignGenerationCoordinator.PreparedReference(id,stored.storageKey()));}
        return List.copyOf(result);
    }
}
