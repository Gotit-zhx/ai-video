package com.yingdao.aicommerce.config;

import com.yingdao.aicommerce.auth.ExtensionBearerAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableRedisIndexedHttpSession(
        redisNamespace = "heyi:session",
        maxInactiveIntervalInSeconds = 28800
)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ExtensionBearerAuthenticationFilter extensionBearerFilter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST,"/api/v1/commerce-batches/*/quota-reservations", "/api/v1/commerce-batches/*/quota-reservations/**").hasAuthority("product:publish")
                        .requestMatchers(HttpMethod.POST,"/api/v1/commerce-batches/**").hasAuthority("product:edit")
                        .requestMatchers("/api/v1/commerce-batches/**").hasAnyAuthority("product:view","product:publish")
                        .requestMatchers(HttpMethod.POST, "/api/v1/commerce-templates/**").hasAuthority("product:edit")
                        .requestMatchers("/api/v1/commerce-templates/**").hasAnyAuthority("product:view", "product:publish")
                        .requestMatchers("/api/v1/commerce-plans/**")
                            .hasAnyAuthority("product:view", "product:publish")
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/public/v1/video-references/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/social/**").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/shop-authorizations/callback/**").permitAll()
                        .requestMatchers("/api/v1/automation-agent/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS,
                                "/api/v1/product-captures/**", "/api/v1/extension-auth/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/extension-auth/exchange").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/product-captures/extension/pairing-code")
                            .hasAuthority("product:collect")
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/product-captures/*/images/process")
                            .hasAuthority("asset:watermark")
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/product-captures/*/publish-workflows")
                            .hasAuthority("product:publish")
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/product-captures/*/publish-workflows",
                                "/api/v1/product-captures/*/publish-workflows/*")
                            .hasAnyAuthority("product:view", "product:publish")
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/product-captures/*/approve")
                            .hasAuthority("product:edit")
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/product-captures/*/publish")
                            .hasAuthority("product:publish")
                        .requestMatchers(HttpMethod.GET, "/api/v1/shops/**")
                            .hasAnyAuthority("product:view", "product:publish")
                        .requestMatchers("/api/v1/shops/**", "/api/v1/shop-authorizations/**")
                            .hasAuthority("product:publish")
                        .requestMatchers(HttpMethod.POST, "/api/v1/product-captures")
                            .hasAuthority("product:collect")
                        .requestMatchers(HttpMethod.GET, "/api/v1/product-captures/**")
                            .hasAuthority("product:view")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/product-captures/*")
                            .hasAuthority("product:edit")
                        .requestMatchers("/api/v1/product-captures/**").denyAll()
                        .requestMatchers("/api/v1/extension-auth/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/assistant-test/**")
                            .hasAnyAuthority("assistant:view", "assistant:use", "ROLE_ADMIN")
                        .requestMatchers("/api/v1/assistant-test/**")
                            .hasAnyAuthority("assistant:use", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/automations/**")
                            .hasAnyAuthority("automation:view", "automation:execute", "ROLE_ADMIN")
                        .requestMatchers("/api/v1/automations/**")
                            .hasAnyAuthority("automation:execute", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/system/automation-skills/**",
                                "/api/v1/system/automation-agents/**",
                                "/api/v1/system/users/*/automation-access")
                            .hasAnyAuthority("system:automation:view", "system:automation:manage", "ROLE_ADMIN")
                        .requestMatchers("/api/v1/system/automation-skills/**",
                                "/api/v1/system/automation-agents/**",
                                "/api/v1/system/users/*/automation-access")
                            .hasAnyAuthority("system:automation:manage", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/video-scripts/**")
                            .hasAnyAuthority("video:script:view", "video:script:manage", "ROLE_ADMIN")
                        .requestMatchers("/api/v1/video-scripts/**")
                            .hasAnyAuthority("video:script:manage", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/ai-video-tasks/**")
                            .hasAnyAuthority("video:task:view", "video:task:manage", "ROLE_ADMIN")
                        .requestMatchers("/api/v1/ai-video-tasks/**")
                            .hasAnyAuthority("video:task:manage", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/general-video-tasks/**")
                            .hasAnyAuthority("video:task:view", "video:task:manage", "ROLE_ADMIN")
                        .requestMatchers("/api/v1/general-video-tasks/**")
                            .hasAnyAuthority("video:task:manage", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/ai-video-monitor/**")
                            .hasAnyAuthority("video:monitor:view", "video:task:view", "video:task:manage", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/tasks/current")
                            .hasAnyAuthority(
                                    "dashboard:view",
                                    "workflow:view",
                                    "workflow:manage",
                                    "workflow:review",
                                    "ROLE_ADMIN"
                            )
                        .requestMatchers(HttpMethod.GET, "/api/v1/workflow-jobs/**")
                            .hasAnyAuthority(
                                    "dashboard:view",
                                    "workflow:view",
                                    "workflow:manage",
                                    "workflow:review",
                                    "ROLE_ADMIN"
                            )
                        .requestMatchers(HttpMethod.POST, "/api/v1/workflow-jobs/**")
                            .hasAnyAuthority("workflow:manage", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/workflow-jobs/**")
                            .hasAnyAuthority("workflow:manage", "workflow:review", "ROLE_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/roles/**")
                            .hasAnyAuthority(
                                    "system:permission:view",
                                    "system:role:manage",
                                    "system:user:manage"
                            )
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/permissions/**")
                            .hasAnyAuthority("system:permission:view", "system:role:manage")
                        .requestMatchers("/api/v1/system/roles/**", "/api/v1/system/permissions/**")
                            .hasAuthority("system:role:manage")
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/system/users/**",
                                "/api/v1/system/departments/**")
                            .hasAnyAuthority("system:user:view", "system:user:manage")
                        .requestMatchers("/api/v1/system/users/**", "/api/v1/system/departments/**")
                            .hasAuthority("system:user:manage")
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/organization/**")
                            .hasAnyAuthority(
                                    "system:organization:view",
                                    "system:organization:manage"
                            )
                        .requestMatchers("/api/v1/system/organization/**")
                            .hasAuthority("system:organization:manage")
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/model-configs/**")
                            .hasAnyAuthority("system:model:view", "system:model:manage")
                        .requestMatchers("/api/v1/system/model-configs/**")
                            .hasAuthority("system:model:manage")
                        .requestMatchers("/api/v1/system/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository())
                        .requireExplicitSave(true)
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeUnauthorized(response, exception))
                        .accessDeniedHandler((request, response, exception) ->
                                writeForbidden(response))
                )
                .addFilterBefore(extensionBearerFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeUnauthorized(
            jakarta.servlet.http.HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        response.setStatus(401);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"登录已失效，请重新登录\"}"
        );
    }

    private void writeForbidden(jakarta.servlet.http.HttpServletResponse response) throws IOException {
        response.setStatus(403);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"当前账号没有权限执行此操作\"}"
        );
    }

    @Bean
    AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration
    ) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("chrome-extension://*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "Idempotency-Key"
        ));
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/product-captures/**", configuration);
        source.registerCorsConfiguration("/api/v1/extension-auth/**", configuration);
        return source;
    }
}
