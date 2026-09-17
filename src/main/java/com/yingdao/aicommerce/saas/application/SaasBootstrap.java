package com.yingdao.aicommerce.saas.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.saas.domain.Department;
import com.yingdao.aicommerce.saas.domain.Organization;
import com.yingdao.aicommerce.saas.domain.PlatformUser;
import com.yingdao.aicommerce.saas.domain.Role;
import com.yingdao.aicommerce.saas.mapper.DepartmentMapper;
import com.yingdao.aicommerce.saas.mapper.OrganizationMapper;
import com.yingdao.aicommerce.saas.mapper.PlatformUserMapper;
import com.yingdao.aicommerce.saas.mapper.RoleMapper;
import com.yingdao.aicommerce.saas.mapper.UserRoleMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Component
public class SaasBootstrap implements ApplicationRunner {

    private final OrganizationMapper organizationMapper;
    private final DepartmentMapper departmentMapper;
    private final PlatformUserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;
    private final String organizationCode;
    private final String organizationName;
    private final String adminPhone;
    private final String adminPassword;

    public SaasBootstrap(
            OrganizationMapper organizationMapper,
            DepartmentMapper departmentMapper,
            PlatformUserMapper userMapper,
            RoleMapper roleMapper,
            UserRoleMapper userRoleMapper,
            PasswordEncoder passwordEncoder,
            JdbcTemplate jdbcTemplate,
            @Value("${app.default-organization-code:heyi}") String organizationCode,
            @Value("${app.default-organization-name:赫逸智能化}") String organizationName,
            @Value("${APP_ADMIN_PHONE:13800000000}") String adminPhone,
            @Value("${APP_ADMIN_PASSWORD:admin123}") String adminPassword
    ) {
        this.organizationMapper = organizationMapper;
        this.departmentMapper = departmentMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
        this.organizationCode = organizationCode;
        this.organizationName = organizationName;
        this.adminPhone = adminPhone;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var organization = organizationMapper.selectOne(
                new LambdaQueryWrapper<Organization>().eq(Organization::getCode, organizationCode)
        );
        if (organization == null) {
            organization = new Organization(
                    organizationCode,
                    organizationName,
                    "连接内容创作、商品运营与平台发布的智能化生产平台。"
            );
            organizationMapper.insert(organization);
        }

        var department = departmentMapper.selectOne(
                new LambdaQueryWrapper<Department>()
                        .eq(Department::getOrganizationId, organization.getId())
                        .eq(Department::getCode, "HQ")
        );
        if (department == null) {
            department = new Department(
                    organization.getId(), null, "公司总部", "HQ", 0
            );
            departmentMapper.insert(department);
        }

        List<Role> roles = ensureRoles(organization.getId());
        Role administratorRole = roles.stream()
                .filter(role -> "ADMIN".equals(role.getCode()))
                .findFirst()
                .orElseThrow();

        var administrator = userMapper.selectForLogin(organizationCode, adminPhone);
        if (administrator == null) {
            administrator = new PlatformUser(
                    organization.getId(),
                    department.getId(),
                    adminPhone,
                    "平台管理员",
                    null,
                    null,
                    adminPhone,
                    "系统管理员",
                    passwordEncoder.encode(adminPassword)
            );
            userMapper.insert(administrator);
            userRoleMapper.insert(administrator.getId(), administratorRole.getId());
        }

        jdbcTemplate.update("""
                INSERT IGNORE INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions
                """, administratorRole.getId());
    }

    private List<Role> ensureRoles(String organizationId) {
        List<String> createdRoles = new ArrayList<>();
        if (ensureRole(organizationId, "ADMIN", "企业管理员", "管理企业配置、人员、模型、自动化与全部业务数据", "ALL")) {
            createdRoles.add("ADMIN");
        }
        if (ensureRole(organizationId, "OPERATOR", "内容运营", "仅使用视频工作台：任务监控、视频脚本、AI 视频任务与通用生成", "DEPARTMENT_AND_CHILDREN")) {
            createdRoles.add("OPERATOR");
        }
        if (ensureRole(organizationId, "DESIGNER", "视觉设计", "处理商品素材、视频脚本、分镜和视觉生成任务", "DEPARTMENT")) {
            createdRoles.add("DESIGNER");
        }
        if (ensureRole(organizationId, "REVIEWER", "内容审核", "查看视频生产过程并审核内容与生成结果", "ALL")) {
            createdRoles.add("REVIEWER");
        }
        if (ensureRole(organizationId, "PUBLISHER", "发布专员", "查看视频成片并管理店铺发布与促销任务", "DEPARTMENT")) {
            createdRoles.add("PUBLISHER");
        }
        createdRoles.forEach(code -> grantDefaultPermissions(organizationId, code));
        return roleMapper.selectList(
                new LambdaQueryWrapper<Role>().eq(Role::getOrganizationId, organizationId)
        );
    }

    private boolean ensureRole(
            String organizationId,
            String code,
            String name,
            String description,
            String dataScope
    ) {
        Long count = roleMapper.selectCount(
                new LambdaQueryWrapper<Role>()
                        .eq(Role::getOrganizationId, organizationId)
                        .eq(Role::getCode, code)
        );
        if (count == 0) {
            roleMapper.insert(new Role(
                    organizationId, code, name, description, dataScope, true
            ));
            return true;
        }
        return false;
    }

    private void grantDefaultPermissions(String organizationId, String roleCode) {
        List<String> codes = switch (roleCode) {
            case "OPERATOR" -> List.of(
                    "video:monitor:view", "video:script:view", "video:script:manage",
                    "video:task:view", "video:task:manage"
            );
            case "DESIGNER" -> List.of(
                    "dashboard:view", "workflow:view", "asset:view", "asset:manage",
                    "video:script:view", "video:script:manage",
                    "video:task:view", "video:task:manage",
                    "product:collect", "product:view", "product:edit", "asset:watermark"
            );
            case "REVIEWER" -> List.of(
                    "dashboard:view", "workflow:view", "workflow:review", "asset:view",
                    "video:script:view", "video:task:view", "product:view", "product:edit"
            );
            case "PUBLISHER" -> List.of(
                    "dashboard:view", "workflow:view", "publish:view", "publish:manage",
                    "video:task:view", "product:view", "product:publish"
            );
            default -> List.of();
        };
        for (String permissionCode : codes) {
            jdbcTemplate.update("""
                    INSERT IGNORE INTO role_permissions (role_id, permission_id)
                    SELECT r.id, p.id
                    FROM roles r
                    JOIN permissions p ON p.code = ?
                    WHERE r.organization_id = ? AND r.code = ?
                    """, permissionCode, organizationId, roleCode);
        }
    }
}
