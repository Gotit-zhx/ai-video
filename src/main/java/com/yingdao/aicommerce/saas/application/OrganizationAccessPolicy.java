package com.yingdao.aicommerce.saas.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.domain.Organization;
import com.yingdao.aicommerce.saas.mapper.OrganizationMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class OrganizationAccessPolicy {

    private final TenantContext tenantContext;
    private final OrganizationMapper organizationMapper;
    private final String platformOrganizationCode;

    public OrganizationAccessPolicy(
            TenantContext tenantContext,
            OrganizationMapper organizationMapper,
            @Value("${app.default-organization-code:heyi}") String platformOrganizationCode
    ) {
        this.tenantContext = tenantContext;
        this.organizationMapper = organizationMapper;
        this.platformOrganizationCode = platformOrganizationCode.trim().toLowerCase();
    }

    public boolean isPlatformAdmin() {
        return isPlatformAdmin(tenantContext.currentPrincipal());
    }

    public boolean isPlatformAdmin(PlatformPrincipal principal) {
        return principal != null
                && platformOrganizationCode.equalsIgnoreCase(principal.organizationCode())
                && principal.roleCodes().contains("ADMIN");
    }

    public void requirePlatformAdmin() {
        if (!isPlatformAdmin()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只有平台管理员可以跨公司管理");
        }
    }

    public String resolveManagedOrganizationId(String requestedOrganizationId) {
        String currentOrganizationId = tenantContext.organizationId();
        String requested = requestedOrganizationId == null ? "" : requestedOrganizationId.trim();
        if (requested.isEmpty() || currentOrganizationId.equals(requested)) {
            return currentOrganizationId;
        }
        requirePlatformAdmin();
        requireOrganization(requested);
        return requested;
    }

    public Organization requireOrganization(String organizationId) {
        Organization organization = organizationMapper.selectById(organizationId);
        if (organization == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "公司不存在");
        }
        return organization;
    }
}
