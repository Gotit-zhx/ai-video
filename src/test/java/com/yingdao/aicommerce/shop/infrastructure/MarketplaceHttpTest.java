package com.yingdao.aicommerce.shop.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class MarketplaceHttpTest {
    private final MarketplaceHttp http = new MarketplaceHttp(new ObjectMapper());

    @Test
    void springCanAutowireTheProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(MarketplaceHttp.class);
            context.refresh();

            assertThat(context.getBean(MarketplaceHttp.class)).isNotNull();
        }
    }

    @Test
    void redactsSecretsFromJsonErrorBodiesRecursively() {
        String safe = http.safeBody("""
                {"access_token":"access-secret","nested":{"refreshToken":"refresh-secret",\
                "app_secret":"app-secret","sign":"signature-secret"},"message":"invalid token"}
                """);

        assertThat(safe)
                .doesNotContain("access-secret", "refresh-secret", "app-secret", "signature-secret")
                .contains("REDACTED")
                .contains("invalid token");
    }

    @Test
    void redactsSecretsFromNonJsonErrorBodies() {
        String safe = http.safeBody(
                "access_token=access-secret&refresh_token=refresh-secret Authorization: Bearer auth-secret");

        assertThat(safe)
                .doesNotContain("access-secret", "refresh-secret", "auth-secret")
                .contains("access_token=REDACTED")
                .contains("refresh_token=REDACTED")
                .contains("Authorization: REDACTED");
    }
}
