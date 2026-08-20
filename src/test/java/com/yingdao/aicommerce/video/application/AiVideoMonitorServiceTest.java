package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiVideoMonitorServiceTest {

    @Test
    void administratorSnapshotIncludesEveryCreatorInTheOrganization() {
        PlatformPrincipal principal = principal("admin-user", List.of("ADMIN"));
        assertTrue(AiVideoMonitorService.canViewOrganizationWide(principal));
        assertNull(AiVideoMonitorService.ownerUserIdScope(principal));
    }

    @Test
    void ordinaryMonitorUserRemainsScopedToOwnTasks() {
        PlatformPrincipal principal = principal("operator-user", List.of("OPERATOR"));
        assertFalse(AiVideoMonitorService.canViewOrganizationWide(principal));
        assertEquals("operator-user", AiVideoMonitorService.ownerUserIdScope(principal));
    }

    private static PlatformPrincipal principal(String userId, List<String> roles) {
        return new PlatformPrincipal(userId, "organization-1", "heyi", "user", "User", "password",
                true, roles, List.of("video:monitor:view"));
    }
}
