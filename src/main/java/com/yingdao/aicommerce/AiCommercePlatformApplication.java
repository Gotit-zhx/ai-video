package com.yingdao.aicommerce;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan({
        "com.yingdao.aicommerce.auth",
        "com.yingdao.aicommerce.workflow.mapper",
        "com.yingdao.aicommerce.saas.mapper",
        "com.yingdao.aicommerce.automation.mapper",
        "com.yingdao.aicommerce.task.mapper",
        "com.yingdao.aicommerce.video.mapper"
})
@SpringBootApplication
public class AiCommercePlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCommercePlatformApplication.class, args);
    }
}
