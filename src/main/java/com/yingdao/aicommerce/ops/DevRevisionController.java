package com.yingdao.aicommerce.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only, bounded metadata for source refresh; never serves runtime configuration. */
@RestController
@Profile("dev")
public class DevRevisionController {
    private final Path stateFile;
    private final MaintenanceFilter maintenance;
    private final JsonMapper mapper = new JsonMapper();

    public DevRevisionController(@Value("${heyi.dev.state-file:.dev/source-state.json}") String stateFile,
                                 MaintenanceFilter maintenance) {
        this.stateFile = Path.of(stateFile);
        this.maintenance = maintenance;
    }

    @GetMapping("/_dev/revision")
    public ResponseEntity<Map<String, String>> revision() {
        try {
            if (Files.size(stateFile) > 4096) return unavailable();
            JsonNode node = mapper.readTree(Files.readString(stateFile));
            String revision = node.path("frontendRevision").asText("");
            if (!revision.matches("[a-f0-9]{64}")) return unavailable();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(Map.of("frontendRevision", revision, "contextId", maintenance.contextId()));
        } catch (Exception unavailable) {
            return unavailable();
        }
    }

    private ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();
    }
}
