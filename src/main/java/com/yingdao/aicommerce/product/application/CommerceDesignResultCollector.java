package com.yingdao.aicommerce.product.application;
import com.yingdao.aicommerce.product.domain.CommerceDesignOutput;
import com.yingdao.aicommerce.product.mapper.*;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.Base64;
/** Downloads existing provider results only. Never calls image generation. */
@Service
public class CommerceDesignResultCollector {
    private final CommerceDesignAttemptMapper attempts;private final CommerceDesignOutputMapper outputs;
    private final MarketplaceImageDownloader downloader;private final CommerceImageArchive archive;
    private final CommerceDesignOutputLedger ledger;private final ObjectMapper json;
    public CommerceDesignResultCollector(CommerceDesignAttemptMapper attempts,CommerceDesignOutputMapper outputs,MarketplaceImageDownloader downloader,CommerceImageArchive archive,CommerceDesignOutputLedger ledger,ObjectMapper json){this.attempts=attempts;this.outputs=outputs;this.downloader=downloader;this.archive=archive;this.ledger=ledger;this.json=json;}
    public CommerceDesignOutput collect(String org,String attemptId){
        var attempt=attempts.get(org,attemptId);if(attempt==null)throw new IllegalStateException("生成尝试不存在");
        var existing=outputs.byAttempt(org,attemptId);
        if(existing!=null){archive.read(org,existing.storageKey());return existing;}
        if(!"RESULT_RECEIVED".equals(attempt.status()))throw new IllegalStateException("仅已收到结果的尝试可以下载");
        var response=json.readValue(attempt.resultJson(),CommerceDesignImageGateway.Response.class);byte[] bytes;
        if(response.artifact()!=null&&response.artifact().dataBase64()!=null&&!response.artifact().dataBase64().isBlank()){
            String data=response.artifact().dataBase64();if(data.length()>12_000_000)throw new IllegalArgumentException("生成结果过大");bytes=Base64.getDecoder().decode(data);
        }else{
            if(response.result()==null||response.result().url()==null||response.result().url().isBlank())throw new IllegalStateException("缺少已保存的下载地址");
            bytes=downloader.download(response.result().url()).bytes();
        }
        var stored=archive.store(org,bytes);
        return ledger.attach(org,attemptId,stored);
    }
}
