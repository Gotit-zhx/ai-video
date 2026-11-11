package com.yingdao.aicommerce.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class MaintenanceGateTest {
    @TempDir Path temp;
    @Test void quiesceRejectsNewClaimsAndRetainsExistingWorkUntilFinished() throws Exception {
        Path marker = temp.resolve("maintenance.json"); var gate = new MaintenanceGate(marker);
        var existing = gate.enter(true); assertNotNull(existing);
        Files.writeString(marker, "{\"operation\":\"test\"}");
        assertNull(gate.enter(true)); assertNull(gate.enter(false)); assertEquals(1, gate.activeWorkers());
        existing.close(); existing.close(); assertEquals(0, gate.activeWorkers());
        Files.delete(marker); assertNotNull(gate.enter(true));
    }
    @Test void requestsCallbacksAndDirectAccessReceiveApiEnvelopeWhileHealthWorks() throws Exception {
        Path marker = temp.resolve("maintenance.json"); Files.writeString(marker, "{}");
        var filter = new MaintenanceFilter(new MaintenanceGate(marker), temp.resolve("token"));
        for (String path : new String[]{"/api/v1/finance/orders", "/callback/provider", "/api/v1/auth/login"}) {
            var request = new MockHttpServletRequest("POST", path); var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (a,b) -> fail("Business handler must not run"));
            assertEquals(503, response.getStatus()); assertEquals("30", response.getHeader("Retry-After"));
            assertTrue(response.getContentAsString().contains("\"success\":false"));
        }
        var health = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), health, (a,b) -> b.getWriter().write("UP"));
        assertEquals("UP", health.getContentAsString());
    }
    @Test void statusRequiresHostSecretAndDoesNotCountItself() throws Exception {
        Path token = temp.resolve("token"); Files.writeString(token, "a-test-token");
        var gate = new MaintenanceGate(temp.resolve("marker")); var filter = new MaintenanceFilter(gate, token);
        var request = new MockHttpServletRequest("GET", "/_heyi/ops/status");
        var denied = new MockHttpServletResponse(); filter.doFilter(request, denied, (a,b) -> fail());
        assertEquals(404, denied.getStatus());
        request.addHeader("X-Heyi-Ops-Token", "a-test-token"); var result = new MockHttpServletResponse();
        filter.doFilter(request, result, (a,b) -> fail());
        assertTrue(result.getContentAsString().contains("\"activeRequests\":0"));
    }
    @Test void statusIdentifiesTheSpecificRuntimeContext() throws Exception {
        Path token = temp.resolve("context-token"); Files.writeString(token, "test-token");
        var gate = new MaintenanceGate(temp.resolve("context-marker"));
        var first = new MaintenanceFilter(gate, token);
        var second = new MaintenanceFilter(gate, token);
        assertNotEquals(first.contextId(), second.contextId());
        var request = new MockHttpServletRequest("GET", "/_heyi/ops/status");
        request.addHeader("X-Heyi-Ops-Token", "test-token");
        var response = new MockHttpServletResponse();
        first.doFilter(request, response, (a,b) -> fail());
        assertTrue(response.getContentAsString().contains("\"contextId\":\"" + first.contextId() + "\""));
        assertTrue(response.getContentAsString().contains("\"protocol\":1"));
    }
}
