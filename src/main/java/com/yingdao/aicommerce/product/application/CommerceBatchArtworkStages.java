package com.yingdao.aicommerce.product.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;

@Configuration
public class CommerceBatchArtworkStages {
    @Bean CommerceBatchStageHandler extractArtwork(CommerceBatchArtworkService service) {return handler(CommerceBatchExecution.Stage.EXTRACT,service);}
    @Bean CommerceBatchStageHandler varyArtwork(CommerceBatchArtworkService service) {return handler(CommerceBatchExecution.Stage.VARIATE,service);}
    @Bean CommerceBatchStageHandler mockupArtwork(CommerceBatchArtworkService service) {return handler(CommerceBatchExecution.Stage.MOCKUP,service);}
    @Bean CommerceBatchStageHandler checkArtwork(CommerceBatchArtworkService service) {return handler(CommerceBatchExecution.Stage.REVIEW,service);}
    private CommerceBatchStageHandler handler(CommerceBatchExecution.Stage stage,CommerceBatchArtworkService service) {
        return new CommerceBatchStageHandler() {
            public CommerceBatchExecution.Stage stage(){return stage;}
            public Result execute(CommerceBatchExecution run,PlatformPrincipal user){return service.execute(run,user);}
        };
    }
}
