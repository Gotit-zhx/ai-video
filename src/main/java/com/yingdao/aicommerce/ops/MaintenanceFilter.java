package com.yingdao.aicommerce.ops;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs before authentication, including callbacks and direct backend traffic. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class MaintenanceFilter extends OncePerRequestFilter {
    private final MaintenanceGate gate;
    private final Path tokenFile;
    public MaintenanceFilter() {
        this(MaintenanceGate.instance(), Path.of(System.getenv().getOrDefault(
                "HEYI_OPS_TOKEN_FILE", "/run/heyi-ops/status-token")));
    }
    MaintenanceFilter(MaintenanceGate gate, Path tokenFile) { this.gate = gate; this.tokenFile = tokenFile; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals("/_heyi/ops/status")) {
            String token = request.getHeader("X-Heyi-Ops-Token");
            if (!request.getMethod().equals("GET") || token == null || !Files.isReadable(tokenFile)
                    || !MessageDigest.isEqual(Files.readString(tokenFile).strip().getBytes(StandardCharsets.UTF_8),
                                              token.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(404); return;
            }
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"protocol\":1,\"maintenance\":" + gate.closed()
                    + ",\"activeRequests\":" + gate.activeRequests() + ",\"activeWorkers\":" + gate.activeWorkers() + "}");
            return;
        }
        if ((path.equals("/actuator/health") || path.startsWith("/actuator/health/"))
                && (request.getMethod().equals("GET") || request.getMethod().equals("HEAD"))) {
            chain.doFilter(request, response); return;
        }
        MaintenanceGate.Permit permit = gate.enter(false);
        if (permit == null) {
            response.setStatus(503); response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.setHeader("Retry-After", "30"); response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"success\":false,\"data\":null,\"message\":\"系统正在维护，请稍后重试\",\"timestamp\":\"" + Instant.now() + "\"}");
            return;
        }
        try { chain.doFilter(request, response); }
        finally {
            if (request.isAsyncStarted()) {
                AsyncListener listener = new AsyncListener() {
                    public void onComplete(AsyncEvent event) { permit.close(); }
                    // Timeout/error callbacks may still dispatch error handling;
                    // count until onComplete rather than claiming an early safe point.
                    public void onTimeout(AsyncEvent event) { }
                    public void onError(AsyncEvent event) { }
                    public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                };
                try { request.getAsyncContext().addListener(listener); }
                catch (IllegalStateException alreadyCompleted) { permit.close(); }
            } else permit.close();
        }
    }
}
