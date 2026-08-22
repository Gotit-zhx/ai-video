package com.yingdao.aicommerce.video.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiVideoTaskCompanyAccessTest {

    @Test
    void companyAdministratorCanAccessCompanyTaskData() {
        assertTrue(AiVideoTaskService.isCompanyAdministrator(principal(List.of("ADMIN"))));
        assertFalse(AiVideoTaskService.isCompanyAdministrator(principal(List.of("OPERATOR"))));
    }

    private PlatformPrincipal principal(List<String> roles) {
        return new PlatformPrincipal("user-1", "org-1", "company", "13800000000",
                "测试账号", "encoded", true, roles, List.of());
    }
}
