package com.yingdao.aicommerce.saas.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.saas.domain.Department;
import com.yingdao.aicommerce.saas.domain.Organization;
import com.yingdao.aicommerce.saas.domain.PlatformUser;
import com.yingdao.aicommerce.saas.domain.Role;
import com.yingdao.aicommerce.saas.mapper.DepartmentMapper;
import com.yingdao.aicommerce.saas.mapper.OrganizationMapper;
import com.yingdao.aicommerce.saas.mapper.PermissionMapper;
import com.yingdao.aicommerce.saas.mapper.PlatformUserMapper;
import com.yingdao.aicommerce.saas.mapper.RoleMapper;
import com.yingdao.aicommerce.saas.mapper.RolePermissionMapper;
import com.yingdao.aicommerce.saas.mapper.UserRoleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SaasAdminCompanyManagementTest {

    @Test
    void platformAdminCanUpdateAUserInTheSelectedCompany() {
        TenantContext tenantContext = mock(TenantContext.class);
        OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
        DepartmentMapper departmentMapper = mock(DepartmentMapper.class);
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
        OrganizationAccessPolicy accessPolicy = new OrganizationAccessPolicy(
                tenantContext, organizationMapper, "heyi"
        );
        SaasAdminService service = new SaasAdminService(
                tenantContext, organizationMapper, departmentMapper, userMapper, roleMapper,
                userRoleMapper, mock(PermissionMapper.class), mock(RolePermissionMapper.class),
                mock(PasswordEncoder.class), accessPolicy
        );
        PlatformPrincipal principal = new PlatformPrincipal(
                "platform-user", "org-platform", "heyi", "13800000000", "平台管理员",
                "password", true, List.of("ADMIN"), List.of("system:user:manage")
        );
        Department department = new Department("org-client", null, "公司总部", "HQ", 0);
        Role role = new Role("org-client", "OPERATOR", "内容运营", null, "ALL", true);
        PlatformUser user = new PlatformUser(
                "org-client", department.getId(), "15559196698", "客户人员",
                null, null, "15559196698", null, "password-hash"
        );
        Organization client = new Organization("client", "客户公司", null);

        when(tenantContext.currentPrincipal()).thenReturn(principal);
        when(tenantContext.organizationId()).thenReturn("org-platform");
        when(organizationMapper.selectById("org-client")).thenReturn(client);
        when(departmentMapper.selectById(department.getId())).thenReturn(department);
        when(roleMapper.selectById(role.getId())).thenReturn(role);
        when(userMapper.selectById(user.getId())).thenReturn(user);
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(userRoleMapper.selectRoleIds(user.getId())).thenReturn(List.of(role.getId()));
        when(userMapper.selectRoleNames(user.getId())).thenReturn(List.of(role.getName()));

        SaasAdminService.UserOverview updated = service.updateUser(
                user.getId(), department.getId(), "客户人员二", null,
                "15559196698", "运营", List.of(role.getId())
        );

        assertThat(updated.user().getOrganizationId()).isEqualTo("org-client");
        assertThat(updated.user().getDisplayName()).isEqualTo("客户人员二");
        verify(userMapper).updateById(user);
    }
}
