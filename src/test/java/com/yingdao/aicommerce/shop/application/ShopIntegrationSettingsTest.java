package com.yingdao.aicommerce.shop.application;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShopIntegrationSettingsTest {
    @Test
    void copiedAuthorizationLinksDefaultToOneHour() throws Exception {
        assertThat(settings(Map.of())).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void deploymentCanExplicitlyOverrideAuthorizationLifetime() throws Exception {
        assertThat(settings(Map.of("APP_SHOP_AUTH_STATE_TTL", "45m")))
                .isEqualTo(Duration.ofMinutes(45));
    }

    private Duration settings(Map<String, Object> overrides) throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("deployment", overrides));
            for (var source : new YamlPropertySourceLoader().load("application",
                    new ClassPathResource("application.yml"))) {
                context.getEnvironment().getPropertySources().addLast(source);
            }
            context.getBeanFactory().setConversionService(
                    new org.springframework.boot.convert.ApplicationConversionService());
            context.register(ShopIntegrationSettings.class);
            context.refresh();
            return context.getBean(ShopIntegrationSettings.class).stateTtl();
        }
    }
}
