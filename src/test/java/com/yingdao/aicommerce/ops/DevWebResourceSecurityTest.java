package com.yingdao.aicommerce.ops;

import jakarta.servlet.Filter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Actual dev security, default firewall and filesystem MVC resolution, without application services. */
class DevWebResourceSecurityTest {
    private static final String PRIVATE_SOURCE = "private-source-regression-sentinel";
    private static final String PUBLIC_ASSET = "export const publicAsset = true;";

    @TempDir Path frontend;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach void startMvc() throws Exception {
        for (String name : List.of("modern/src/app/mount.tsx", "modern/node_modules/vite/package.json", "modern-build.mjs")) {
            Path source = frontend.resolve(name);
            Files.createDirectories(source.getParent());
            Files.writeString(source, PRIVATE_SOURCE);
        }
        Path asset = frontend.resolve("assets/finance-modern/assets/entry-hash.js");
        Files.createDirectories(asset.getParent());
        Files.writeString(asset, PUBLIC_ASSET);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("dev");
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "test.frontend-location=" + frontend.toUri());
        context.register(DevWebConfiguration.class, MvcConfiguration.class, TestApi.class);
        context.refresh();
        mvc = webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach void closeMvc() {
        if (context != null) context.close();
    }

    @ParameterizedTest(name = "{0} {1} under context [{2}]")
    @CsvSource(textBlock = """
            GET, /modern/src/app/mount.tsx, ''
            HEAD, /modern/src/app/mount.tsx, ''
            GET, /modern-build.mjs, ''
            HEAD, /modern-build.mjs, ''
            GET, /%6Dodern/src/app/mount.tsx, ''
            HEAD, /%6Dodern/src/app/mount.tsx, ''
            GET, /%6Dodern-build.mjs, ''
            HEAD, /%6Dodern-build.mjs, ''
            GET, /mo%64ern/node_modules/vite/package.json, ''
            GET, /modern-b%75ild.mjs, ''
            GET, /MODERN/src/app/mount.tsx, ''
            GET, /MODERN-BUILD.MJS, ''
            GET, /modern/src/app/mount.tsx, /portal
            HEAD, /modern-build.mjs, /portal
            GET, /%6Dodern/src/app/mount.tsx, /portal
            HEAD, /%6Dodern-build.mjs, /portal
            GET, /modern-build.mjs/, ''
            HEAD, /modern-build.mjs/, ''
            GET, /%6Dodern-build.mjs/, ''
            HEAD, /%6Dodern-build.mjs/, ''
            GET, /MODERN-BUILD.MJS/, ''
            HEAD, /MODERN-BUILD.MJS/, ''
            GET, /modern-build.mjs/, /portal
            HEAD, /modern-build.mjs/, /portal
            GET, /%6Dodern-build.mjs/, /portal
            HEAD, /%6Dodern-build.mjs/, /portal
            GET, /MODERN-BUILD.MJS/, /portal
            HEAD, /MODERN-BUILD.MJS/, /portal
            """)
    void deniesPrivateSourceForAuthenticatedUser(String method, String path, String contextPath) throws Exception {
        MvcResult result = mvc.perform(authenticatedRequest(method, path, contextPath)).andReturn();
        assertAll(
                () -> assertEquals(403, result.getResponse().getStatus(), method + " " + path),
                () -> assertFalse(result.getResponse().getContentAsString().contains(PRIVATE_SOURCE),
                        "MVC must not return the private source file"));
    }

    @Test void deniesPrivateSourceWithoutAuthentication() throws Exception {
        for (String path : List.of("/modern/src/app/mount.tsx", "/%6Dodern-build.mjs")) {
            assertEquals(403, mvc.perform(request(HttpMethod.GET, URI.create(path)))
                    .andReturn().getResponse().getStatus(), path);
        }
    }

    @Test void servesPublishedAssetWithoutAuthenticationForGetAndHead() throws Exception {
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
            MvcResult result = mvc.perform(request(method,
                    URI.create("/assets/finance-modern/assets/entry-hash.js"))).andReturn();
            assertInstanceOf(ResourceHttpRequestHandler.class, result.getHandler());
            assertEquals(200, result.getResponse().getStatus());
            assertEquals(method == HttpMethod.GET ? PUBLIC_ASSET : "", result.getResponse().getContentAsString());
        }
    }

    @Test void keepsDefaultFirewallEncodedSlashAndPeriodRejection() throws Exception {
        for (String path : List.of("/modern%2Fsrc/app/mount.tsx", "/modern-build%2Emjs", "/modern-build.mjs%2F", "/%6Dodern-build.mjs%2F")) {
            for (String method : List.of("GET", "HEAD")) {
                MvcResult result = mvc.perform(authenticatedRequest(method, path, "")).andReturn();
                assertEquals(400, result.getResponse().getStatus(), method + " " + path);
                assertFalse(result.getResponse().getContentAsString().contains(PRIVATE_SOURCE));
            }
        }
    }

    @Test void retainsAuthenticationForOrdinaryApi() throws Exception {
        assertEquals(401, mvc.perform(request(HttpMethod.GET, URI.create("/api/test")))
                .andReturn().getResponse().getStatus());
        MvcResult authenticated = mvc.perform(authenticatedRequest("GET", "/api/test", "")).andReturn();
        assertEquals(200, authenticated.getResponse().getStatus());
        assertEquals("authenticated-api", authenticated.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder authenticatedRequest(String method, String path, String contextPath) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated("regular-user", "unused", List.of())));
        return request(HttpMethod.valueOf(method), URI.create(contextPath + path)).contextPath(contextPath).session(session);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class MvcConfiguration implements WebMvcConfigurer {
        private final Environment environment;

        MvcConfiguration(Environment environment) {
            this.environment = environment;
        }

        @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
            // Mirrors application-dev.yml's file:frontend/ resource root.
            registry.addResourceHandler("/**").addResourceLocations(environment.getRequiredProperty("test.frontend-location"));
        }

        @Bean @Order(1)
        SecurityFilterChain authenticatedFallback(HttpSecurity http) throws Exception {
            // SecurityConfig's relevant catch-all, without database or business services.
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> response.setStatus(401)))
                    .build();
        }
    }

    @RestController
    static class TestApi {
        @GetMapping("/api/test") String read() { return "authenticated-api"; }
    }
}
