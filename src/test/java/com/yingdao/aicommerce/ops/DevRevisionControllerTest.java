package com.yingdao.aicommerce.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DevRevisionControllerTest {
    @TempDir Path temp;
    @Test void exposesOnlyValidatedRevisionAndContextAndNeverConfiguration() throws Exception {
        Path state = temp.resolve("source-state.json");
        Files.writeString(state, "{\"frontendRevision\":\"" + "a".repeat(64) + "\",\"secret\":\"do-not-expose\"}");
        var filter = new MaintenanceFilter(new MaintenanceGate(temp.resolve("marker")), temp.resolve("token"));
        var controller = new DevRevisionController(state.toString(), filter);
        var response = controller.revision();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
        assertEquals(2, response.getBody().size());
        assertEquals("a".repeat(64), response.getBody().get("frontendRevision"));
        assertEquals(filter.contextId(), response.getBody().get("contextId"));
    }
    @Test void missingOrMalformedStateReturnsTemporaryUnavailabilityWithoutPaths() throws Exception {
        Path state = temp.resolve("source-state.json");
        var filter = new MaintenanceFilter(new MaintenanceGate(temp.resolve("marker")), temp.resolve("token"));
        var controller = new DevRevisionController(state.toString(), filter);
        assertEquals(503, controller.revision().getStatusCode().value());
        Files.writeString(state, "{\"frontendRevision\":\"private-value\"}");
        var response = controller.revision();
        assertEquals(503, response.getStatusCode().value());
        assertNull(response.getBody());
    }
}
