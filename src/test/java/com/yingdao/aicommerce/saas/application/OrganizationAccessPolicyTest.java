package com.yingdao.aicommerce.saas.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.domain.Organization;
import com.yingdao.aicommerce.saas.mapper.OrganizationMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrganizationAccessPolicyTest {

    private final TenantContext tenantContext = mock(TenantContext.class);
    private final OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
    private final OrganizationAccessPolicy policy = new OrganizationAccessPolicy(
            tenantContext, organizationMapper, "heyi"
    );

    @Test
    void platformAdminCanSelectAnotherOrganization() {
        PlatformPrincipal principal = principal("org-platform", "heyi", List.of("ADMIN"));
        when(tenantContext.currentPrincipal()).thenReturn(principal);
        when(tenantContext.organizationId()).thenReturn("org-platform");
        when(organizationMapper.selectById("org-client")).thenReturn(
                new Organization("client", "客户公司", null)
        );

        assertThat(policy.resolveManagedOrganizationId("org-client")).isEqualTo("org-client");
    }

    @Test
    void companyAdminCannotSelectAnotherOrganization() {
        PlatformPrincipal principal = principal("org-client", "client", List.of("ADMIN"));
        when(tenantContext.currentPrincipal()).thenReturn(principal);
        when(tenantContext.organizationId()).thenReturn("org-client");

        assertThatThrownBy(() -> policy.resolveManagedOrganizationId("org-other"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有平台管理员");
    }

    @Test
    void ordinaryUserStaysInsideOwnOrganization() {
        PlatformPrincipal principal = principal("org-client", "client", List.of("OPERATOR"));
        when(tenantContext.currentPrincipal()).thenReturn(principal);
        when(tenantContext.organizationId()).thenReturn("org-client");

        assertThat(policy.resolveManagedOrganizationId(null)).isEqualTo("org-client");
        assertThat(policy.resolveManagedOrganizationId("org-client")).isEqualTo("org-client");
    }

    private PlatformPrincipal principal(String organizationId, String organizationCode, List<String> roles) {
        return new PlatformPrincipal(
                "user-1", organizationId, organizationCode, "13800000000", "测试用户",
                "password", true, roles, List.of()
        );
    }
}
