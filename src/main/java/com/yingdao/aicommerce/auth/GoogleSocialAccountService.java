package com.yingdao.aicommerce.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.domain.Department;
import com.yingdao.aicommerce.saas.domain.Organization;
import com.yingdao.aicommerce.saas.domain.PlatformUser;
import com.yingdao.aicommerce.saas.domain.Role;
import com.yingdao.aicommerce.saas.mapper.DepartmentMapper;
import com.yingdao.aicommerce.saas.mapper.OrganizationMapper;
import com.yingdao.aicommerce.saas.mapper.PlatformUserMapper;
import com.yingdao.aicommerce.saas.mapper.RoleMapper;
import com.yingdao.aicommerce.saas.mapper.UserRoleMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class GoogleSocialAccountService {

    private static final String DEFAULT_DEPARTMENT_CODE = "HQ";
    private static final String DEFAULT_ROLE_CODE = "OPERATOR";

    private final OrganizationMapper organizationMapper;
    private final DepartmentMapper departmentMapper;
    private final PlatformUserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final SocialLoginIdentityMapper identityMapper;

    public GoogleSocialAccountService(
            OrganizationMapper organizationMapper,
            DepartmentMapper departmentMapper,
            PlatformUserMapper userMapper,
            RoleMapper roleMapper,
            UserRoleMapper userRoleMapper,
            SocialLoginIdentityMapper identityMapper
    ) {
        this.organizationMapper = organizationMapper;
        this.departmentMapper = departmentMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.identityMapper = identityMapper;
    }

    @Transactional
    public String resolveOrCreate(
            String organizationCode,
            SocialLoginService.ExternalProfile profile
    ) {
        String linkedUserId = identityMapper.selectUserId(profile.provider(), profile.subject());
        if (linkedUserId != null) return linkedUserId;

        Organization organization = organizationMapper.selectOne(
                new LambdaQueryWrapper<Organization>()
                        .eq(Organization::getCode, organizationCode)
                        .eq(Organization::getStatus, "ACTIVE")
        );
        if (organization == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "企业不存在或已停用");
        }

        String email = normalizeEmail(profile.email());
        PlatformUser user = userMapper.selectOne(
                new LambdaQueryWrapper<PlatformUser>()
                        .eq(PlatformUser::getOrganizationId, organization.getId())
                        .eq(PlatformUser::getEmail, email)
                        .last("LIMIT 1")
        );
        if (user == null) {
            user = createUser(organization, profile, email);
        } else if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "该企业账号已停用");
        }

        identityMapper.insertIdentity(
                UUID.randomUUID().toString(),
                profile.provider(),
                profile.subject(),
                user.getId(),
                email,
                profile.displayName()
        );
        return user.getId();
    }

    private PlatformUser createUser(
            Organization organization,
            SocialLoginService.ExternalProfile profile,
            String email
    ) {
        Department department = departmentMapper.selectOne(
                new LambdaQueryWrapper<Department>()
                        .eq(Department::getOrganizationId, organization.getId())
                        .eq(Department::getCode, DEFAULT_DEPARTMENT_CODE)
                        .eq(Department::getStatus, "ACTIVE")
                        .last("LIMIT 1")
        );
        Role role = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>()
                        .eq(Role::getOrganizationId, organization.getId())
                        .eq(Role::getCode, DEFAULT_ROLE_CODE)
                        .eq(Role::getStatus, "ACTIVE")
                        .last("LIMIT 1")
        );
        if (department == null || role == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "企业缺少 Google 登录默认部门或角色");
        }

        String displayName = trimToLength(profile.displayName(), 120);
        if (displayName == null) displayName = trimToLength(email.substring(0, email.indexOf('@')), 120);
        String subject = profile.subject().replaceAll("[^A-Za-z0-9_-]", "");
        String username = trimToLength("google_" + subject, 80);
        PlatformUser user = new PlatformUser(
                organization.getId(),
                department.getId(),
                username,
                displayName,
                null,
                email,
                null,
                "Google 用户",
                null
        );
        user.markExternalRegistration("GOOGLE");
        userMapper.insert(user);
        userRoleMapper.insert(user.getId(), role.getId());
        return user;
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "Google 未返回可用邮箱");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 160 || !normalized.contains("@")) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "Google 返回的邮箱格式不可用");
        }
        return normalized;
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
