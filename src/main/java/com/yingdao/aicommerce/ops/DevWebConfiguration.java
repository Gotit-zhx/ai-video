package com.yingdao.aicommerce.ops;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.server.PathContainer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ResourceHandlerUtils;
import org.springframework.web.util.ServletRequestPathUtils;

/** Source frontend and revision access only; all business routes retain the existing chain. */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
public class DevWebConfiguration implements WebMvcConfigurer {
    @Bean
    @Order(0)
    SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(request -> publicRequest(request) || privateFinanceSource(request))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(DevWebConfiguration::privateFinanceSource).denyAll()
                        .anyRequest().permitAll());
        return http.build();
    }

    static boolean privateFinanceSource(HttpServletRequest request) {
        // Use MVC's parsed, decoded path, including context/servlet prefix removal.
        // Comparing the raw URI lets encoded letters reach the authenticated fallback.
        StringBuilder decoded = new StringBuilder();
        for (PathContainer.Element element : ServletRequestPathUtils.parse(request).pathWithinApplication().elements()) {
            decoded.append(element instanceof PathContainer.PathSegment segment ? segment.valueToMatch() : element.value());
        }
        String path = ResourceHandlerUtils.normalizeInputPath(decoded.toString());
        // Filesystem resources can resolve a trailing slash to the same file.
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        // Development checkouts can live on case-insensitive filesystems.
        return path.equalsIgnoreCase("/modern") || path.regionMatches(true, 0, "/modern/", 0, 8)
                || path.equalsIgnoreCase("/modern-build.mjs");
    }

    static boolean publicRequest(HttpServletRequest request) {
        if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")) return false;
        String path = request.getRequestURI();
        if (privateFinanceSource(request)) return false;
        if (path.equals("/_dev/revision")) return request.getMethod().equals("GET");
        if (path.equals("/") || path.equals("/index.html")) return true;
        if (path.contains("..") || path.contains("%") || path.contains("\\") || path.contains("/.")
                || path.startsWith("/api/") || path.startsWith("/_heyi/") || path.startsWith("/actuator/")) return false;
        return path.matches("/[A-Za-z0-9_-]+\\.(?:html|js|css|svg|ico|png)")
                || path.matches("/(?:assets|vendor|i18n|plugin-downloads)/[A-Za-z0-9_./-]+\\.(?:js|mjs|css|json|svg|png|jpe?g|gif|webp|ico|woff2?|ttf|zip|txt)");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/plugin-downloads/**")
                .addResourceLocations("file:frontend/downloads/")
                .setCacheControl(CacheControl.noStore());
    }
}
