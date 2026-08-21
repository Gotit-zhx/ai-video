package com.yingdao.aicommerce.saas.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.domain.ModelConfig;
import com.yingdao.aicommerce.saas.domain.Organization;
import com.yingdao.aicommerce.saas.mapper.ModelConfigMapper;
import com.yingdao.aicommerce.saas.mapper.OrganizationMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelConfigCompanyIsolationTest {

    private final TenantContext tenantContext = mock(TenantContext.class);
    private final OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
    private final ModelConfigMapper modelConfigMapper = mock(ModelConfigMapper.class);
    private final OrganizationAccessPolicy accessPolicy = new OrganizationAccessPolicy(
            tenantContext, organizationMapper, "heyi"
    );
    private final ModelConfigService service = new ModelConfigService(
            tenantContext, modelConfigMapper, mock(ModelSecretCipher.class), accessPolicy
    );

    @Test
    void companyAdminCannotReadAnotherCompanyModels() {
        when(tenantContext.currentPrincipal()).thenReturn(principal("org-client", "client"));
        when(tenantContext.organizationId()).thenReturn("org-client");

        assertThatThrownBy(() -> service.list("org-other"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有平台管理员");
        verifyNoInteractions(modelConfigMapper);
    }

    @Test
    void platformAdminCanReadSelectedCompanyModels() {
        when(tenantContext.currentPrincipal()).thenReturn(principal("org-platform", "heyi"));
        when(tenantContext.organizationId()).thenReturn("org-platform");
        when(organizationMapper.selectById("org-client")).thenReturn(
                new Organization("client", "客户公司", null)
        );
        when(modelConfigMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThat(service.list("org-client")).isEmpty();
    }

    private PlatformPrincipal principal(String organizationId, String organizationCode) {
        return new PlatformPrincipal(
                "user-1", organizationId, organizationCode, "13800000000", "测试用户",
                "password", true, List.of("ADMIN"), List.of("system:model:manage")
        );
    }
}
