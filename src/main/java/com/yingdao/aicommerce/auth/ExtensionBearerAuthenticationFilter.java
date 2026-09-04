package com.yingdao.aicommerce.auth;

import com.yingdao.aicommerce.common.exception.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class ExtensionBearerAuthenticationFilter extends OncePerRequestFilter {
    private final ExtensionAuthService authService;

    public ExtensionBearerAuthenticationFilter(ExtensionAuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String rawToken = bearerToken(request);
        if (rawToken == null || !rawToken.startsWith("hxt_")) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!extensionPath(request.getRequestURI())) {
            writeUnauthorized(response, "插件凭证只能访问商品采集接口");
            return;
        }

        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            ExtensionAuthService.AuthenticatedToken result = authService.authenticate(rawToken);
            var authentication = new UsernamePasswordAuthenticationToken(
                    result.principal(), null, result.principal().getAuthorities()
            );
            authentication.setDetails(result);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            filterChain.doFilter(request, response);
        } catch (BusinessException exception) {
            writeUnauthorized(response, exception.getMessage());
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    public static String bearerToken(HttpServletRequest request) {
        String value = request.getHeader("Authorization");
        if (value == null || !value.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = value.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private boolean extensionPath(String uri) {
        return uri.equals("/api/v1/product-captures")
                || uri.startsWith("/api/v1/product-captures/")
                || uri.equals("/api/v1/extension-auth")
                || uri.startsWith("/api/v1/extension-auth/");
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String safe = message == null ? "插件登录已失效，请重新配对"
                : message.replace("\\", "\\\\").replace("\"", "\\\"");
        response.getWriter().write("{\"success\":false,\"message\":\"" + safe + "\"}");
    }
}
