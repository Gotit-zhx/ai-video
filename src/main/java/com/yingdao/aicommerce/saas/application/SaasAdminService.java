package com.yingdao.aicommerce.saas.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.domain.Department;
import com.yingdao.aicommerce.saas.domain.Organization;
import com.yingdao.aicommerce.saas.domain.Permission;
import com.yingdao.aicommerce.saas.domain.PlatformUser;
import com.yingdao.aicommerce.saas.domain.Role;
import com.yingdao.aicommerce.saas.mapper.DepartmentMapper;
import com.yingdao.aicommerce.saas.mapper.OrganizationMapper;
import com.yingdao.aicommerce.saas.mapper.PermissionMapper;
import com.yingdao.aicommerce.saas.mapper.PlatformUserMapper;
import com.yingdao.aicommerce.saas.mapper.RolePermissionMapper;
import com.yingdao.aicommerce.saas.mapper.RoleMapper;
import com.yingdao.aicommerce.saas.mapper.UserRoleMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SaasAdminService {

    private final TenantContext tenantContext;
    private final OrganizationMapper organizationMapper;
    private final DepartmentMapper departmentMapper;
    private final PlatformUserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PasswordEncoder passwordEncoder;
    private final OrganizationAccessPolicy organizationAccessPolicy;

    public SaasAdminService(
            TenantContext tenantContext,
            OrganizationMapper organizationMapper,
            DepartmentMapper departmentMapper,
            PlatformUserMapper userMapper,
            RoleMapper roleMapper,
            UserRoleMapper userRoleMapper,
            PermissionMapper permissionMapper,
            RolePermissionMapper rolePermissionMapper,
            PasswordEncoder passwordEncoder,
            OrganizationAccessPolicy organizationAccessPolicy
    ) {
        this.tenantContext = tenantContext;
        this.organizationMapper = organizationMapper;
        this.departmentMapper = departmentMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.passwordEncoder = passwordEncoder;
        this.organizationAccessPolicy = organizationAccessPolicy;
    }

    public Organization currentOrganization() {
        return organizationMapper.selectById(tenantContext.organizationId());
    }

    public List<Organization> listOrganizations() {
        organizationAccessPolicy.requirePlatformAdmin();
        return organizationMapper.selectList(
                new LambdaQueryWrapper<Organization>()
                        .orderByAsc(Organization::getName)
                        .orderByAsc(Organization::getCreatedAt)
        );
    }

    @Transactional
    public Organization createOrganization(String code, String name, String description) {
        organizationAccessPolicy.requirePlatformAdmin();
        String normalizedCode = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (!normalizedCode.matches("[a-z0-9][a-z0-9_-]{1,39}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "公司代码需为 2–40 位小写字母、数字、下划线或短横线");
        }
        Long duplicate = organizationMapper.selectCount(
                new LambdaQueryWrapper<Organization>()
                        .eq(Organization::getCode, normalizedCode)
        );
        if (duplicate > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "公司代码已存在");
        }
        Organization organization = new Organization(
                normalizedCode,
                name.trim(),
                blankToNull(description)
        );
        organizationMapper.insert(organization);
        Department headquarters = new Department(
                organization.getId(), null, "公司总部", "HQ", 0
        );
        departmentMapper.insert(headquarters);
        provisionBuiltInRoles(organization.getId());
        return organization;
    }

    @Transactional
    public Organization updateOrganization(
            String name,
            String logoUrl,
            String description,
            String contactName,
            String contactPhone
    ) {
        Organization organization = currentOrganization();
        organization.updateProfile(name, logoUrl, description, contactName, contactPhone);
        organizationMapper.updateById(organization);
        return organization;
    }

    public List<Department> listDepartments() {
        return listDepartments(null);
    }

    public List<Department> listDepartments(String requestedOrganizationId) {
        String organizationId = organizationAccessPolicy.resolveManagedOrganizationId(requestedOrganizationId);
        return departmentMapper.selectList(
                new LambdaQueryWrapper<Department>()
                        .eq(Department::getOrganizationId, organizationId)
                        .orderByAsc(Department::getSortOrder)
                        .orderByAsc(Department::getCreatedAt)
        );
    }

    @Transactional
    public Department createDepartment(
            String requestedOrganizationId,
            String parentId,
            String name,
            String code,
            int sortOrder
    ) {
        String organizationId = organizationAccessPolicy.resolveManagedOrganizationId(requestedOrganizationId);
        if (parentId != null && !parentId.isBlank()) {
            requireDepartment(parentId, organizationId);
        }
        Long duplicate = departmentMapper.selectCount(
                new LambdaQueryWrapper<Department>()
                        .eq(Department::getOrganizationId, organizationId)
                        .eq(Department::getCode, code)
        );
        if (duplicate > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "部门编码已存在");
        }
        Department department = new Department(
                organizationId,
                blankToNull(parentId),
                name,
                code.toUpperCase(),
                sortOrder
        );
        departmentMapper.insert(department);
        return department;
    }

    @Transactional
    public Department createDepartment(
            String parentId,
            String name,
            String code,
            int sortOrder
    ) {
        return createDepartment(null, parentId, name, code, sortOrder);
    }

    @Transactional
    public void deleteDepartment(String departmentId) {
        Department department = requireManagedDepartment(departmentId);
        String organizationId = department.getOrganizationId();
        Long childCount = departmentMapper.selectCount(
                new LambdaQueryWrapper<Department>()
                        .eq(Department::getOrganizationId, organizationId)
                        .eq(Department::getParentId, department.getId())
        );
        if (childCount > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "请先删除该部门的下级部门");
        }
        Long userCount = userMapper.selectCount(
                new LambdaQueryWrapper<PlatformUser>()
                        .eq(PlatformUser::getOrganizationId, organizationId)
                        .eq(PlatformUser::getDepartmentId, department.getId())
        );
        if (userCount > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "请先将部门人员转移到其他部门");
        }
        departmentMapper.deleteById(department.getId());
    }

    @Transactional
    public Department updateDepartment(
            String departmentId,
            String parentId,
            String name,
            String code
    ) {
        Department department = requireManagedDepartment(departmentId);
        String organizationId = department.getOrganizationId();
        String normalizedParentId = blankToNull(parentId);
        if (departmentId.equals(normalizedParentId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "部门不能设置自己为上级");
        }
        if (normalizedParentId != null) {
            Department parent = requireDepartment(normalizedParentId, organizationId);
            while (parent != null) {
                if (departmentId.equals(parent.getId())) {
                    throw new BusinessException(HttpStatus.CONFLICT, "上级部门不能选择当前部门的下级");
                }
                parent = parent.getParentId() == null
                        ? null
                        : requireDepartment(parent.getParentId(), organizationId);
            }
        }
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        Long duplicate = departmentMapper.selectCount(
                new LambdaQueryWrapper<Department>()
                        .eq(Department::getOrganizationId, organizationId)
                        .eq(Department::getCode, normalizedCode)
                        .ne(Department::getId, departmentId)
        );
        if (duplicate > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "部门编码已存在");
        }
        department.updateDefinition(normalizedParentId, name.trim(), normalizedCode);
        departmentMapper.updateById(department);
        return department;
    }

    public List<RoleOverview> listRoles() {
        return listRoles(null);
    }

    public List<RoleOverview> listRoles(String requestedOrganizationId) {
        String organizationId = organizationAccessPolicy.resolveManagedOrganizationId(requestedOrganizationId);
        return roleMapper.selectList(
                new LambdaQueryWrapper<Role>()
                        .eq(Role::getOrganizationId, organizationId)
                        .orderByAsc(Role::getCreatedAt)
        ).stream()
                .map(this::roleOverview)
                .toList();
    }

    public List<Permission> listPermissions() {
        return permissionMapper.selectList(
                new LambdaQueryWrapper<Permission>()
                        .orderByAsc(Permission::getSortOrder)
                        .orderByAsc(Permission::getCode)
        );
    }

    @Transactional
    public RoleOverview createRole(
            String requestedOrganizationId,
            String code,
            String name,
            String description,
            String dataScope,
            List<String> permissionIds
    ) {
        validateDataScope(dataScope);
        String organizationId = organizationAccessPolicy.resolveManagedOrganizationId(requestedOrganizationId);
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        Long duplicate = roleMapper.selectCount(
                new LambdaQueryWrapper<Role>()
                        .eq(Role::getOrganizationId, organizationId)
                        .eq(Role::getCode, normalizedCode)
        );
        if (duplicate > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "角色编码已存在");
        }
        Role role = new Role(
                organizationId,
                normalizedCode,
                name.trim(),
                blankToNull(description),
                dataScope,
                false
        );
        roleMapper.insert(role);
        replaceRolePermissions(role, permissionIds);
        return roleOverview(role);
    }

    @Transactional
    public RoleOverview createRole(
            String code,
            String name,
            String description,
            String dataScope,
            List<String> permissionIds
    ) {
        return createRole(null, code, name, description, dataScope, permissionIds);
    }

    @Transactional
    public RoleOverview updateRole(
            String roleId,
            String name,
            String description,
            String dataScope,
            String status,
            List<String> permissionIds
    ) {
        validateDataScope(dataScope);
        if (!List.of("ACTIVE", "DISABLED").contains(status)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的角色状态");
        }
        Role role = requireManagedRole(roleId);
        if ("ADMIN".equals(role.getCode()) && !"ACTIVE".equals(status)) {
            throw new BusinessException(HttpStatus.CONFLICT, "企业管理员角色不能停用");
        }
        role.updateDefinition(name.trim(), blankToNull(description), dataScope, status);
        roleMapper.updateById(role);
        replaceRolePermissions(role, permissionIds);
        return roleOverview(role);
    }

    public List<UserOverview> listUsers(String departmentId) {
        return listUsers(departmentId, null);
    }

    public List<UserOverview> listUsers(String departmentId, String requestedOrganizationId) {
        String organizationId = organizationAccessPolicy.resolveManagedOrganizationId(requestedOrganizationId);
        var query = new LambdaQueryWrapper<PlatformUser>()
                .eq(PlatformUser::getOrganizationId, organizationId)
                .eq(departmentId != null && !departmentId.isBlank(),
                        PlatformUser::getDepartmentId, departmentId)
                .orderByDesc(PlatformUser::getCreatedAt);
        return userMapper.selectList(query).stream()
                .map(user -> new UserOverview(
                        user,
                        organizationName(user.getOrganizationId()),
                        departmentName(user.getDepartmentId()),
                        userRoleMapper.selectRoleIds(user.getId()),
                        userMapper.selectRoleNames(user.getId())
                ))
                .toList();
    }

    @Transactional
    public UserOverview createUser(
            String requestedOrganizationId,
            String departmentId,
            String displayName,
            String email,
            String phone,
            String jobTitle,
            String password,
            List<String> roleIds
    ) {
        String organizationId = organizationAccessPolicy.resolveManagedOrganizationId(requestedOrganizationId);
        requireDepartment(departmentId, organizationId);
        List<Role> assignedRoles = requireRoles(roleIds, organizationId);
        String normalizedPhone = normalizePhone(phone);
        Long duplicate = userMapper.selectCount(
                new LambdaQueryWrapper<PlatformUser>()
                        .eq(PlatformUser::getOrganizationId, organizationId)
                        .eq(PlatformUser::getUsername, normalizedPhone)
        );
        if (duplicate > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "该手机号已被其他人员使用");
        }
        PlatformUser user = new PlatformUser(
                organizationId,
                departmentId,
                normalizedPhone,
                displayName,
                null,
                blankToNull(email),
                normalizedPhone,
                blankToNull(jobTitle),
                passwordEncoder.encode(password)
        );
        userMapper.insert(user);
        assignedRoles.forEach(role -> userRoleMapper.insert(user.getId(), role.getId()));
        return userOverview(user);
    }

    @Transactional
    public UserOverview createUser(
            String departmentId,
            String displayName,
            String email,
            String phone,
            String jobTitle,
            String password,
            List<String> roleIds
    ) {
        return createUser(null, departmentId, displayName, email, phone, jobTitle, password, roleIds);
    }

    @Transactional
    public UserOverview updateUser(
            String userId,
            String departmentId,
            String displayName,
            String email,
            String phone,
            String jobTitle,
            List<String> roleIds
    ) {
        PlatformUser user = requireUser(userId);
        String organizationId = user.getOrganizationId();
        requireDepartment(departmentId, organizationId);
        List<Role> assignedRoles = requireRoles(roleIds, organizationId);
        String normalizedPhone = normalizePhone(phone);
        Long duplicate = userMapper.selectCount(
                new LambdaQueryWrapper<PlatformUser>()
                        .eq(PlatformUser::getOrganizationId, organizationId)
                        .eq(PlatformUser::getUsername, normalizedPhone)
                        .ne(PlatformUser::getId, userId)
        );
        if (duplicate > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "该手机号已被其他人员使用");
        }
        if (user.getId().equals(tenantContext.currentPrincipal().userId())) {
            List<String> currentRoleIds = userRoleMapper.selectRoleIds(userId);
            List<String> requestedRoleIds = assignedRoles.stream().map(Role::getId).sorted().toList();
            if (!currentRoleIds.stream().sorted().toList().equals(requestedRoleIds)) {
                throw new BusinessException(HttpStatus.CONFLICT, "不能修改当前登录账号的角色");
            }
        }
        user.updateProfile(
                departmentId,
                normalizedPhone,
                displayName.trim(),
                blankToNull(email),
                normalizedPhone,
                blankToNull(jobTitle)
        );
        userMapper.updateById(user);
        userRoleMapper.deleteByUserId(userId);
        assignedRoles.forEach(role -> userRoleMapper.insert(userId, role.getId()));
        return userOverview(user);
    }

    @Transactional
    public void resetUserPassword(String userId, String password) {
        PlatformUser user = requireUser(userId);
        user.resetPassword(passwordEncoder.encode(password));
        userMapper.updateById(user);
    }

    @Transactional
    public UserOverview changeUserStatus(String userId, String status) {
        PlatformUser user = requireUser(userId);
        if (!List.of("ACTIVE", "LOCKED", "DISABLED").contains(status)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的人员状态");
        }
        if (user.getId().equals(tenantContext.currentPrincipal().userId())
                && !"ACTIVE".equals(status)) {
            throw new BusinessException(HttpStatus.CONFLICT, "不能停用当前登录账号");
        }
        user.changeStatus(status);
        userMapper.updateById(user);
        return userOverview(user);
    }

    private void replaceRolePermissions(Role role, List<String> permissionIds) {
        List<String> normalizedIds = permissionIds == null
                ? List.of()
                : permissionIds.stream().distinct().toList();
        if ("ADMIN".equals(role.getCode())) {
            normalizedIds = listPermissions().stream().map(Permission::getId).toList();
        }
        for (String permissionId : normalizedIds) {
            if (permissionMapper.selectById(permissionId) == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "包含不存在的权限");
            }
        }
        rolePermissionMapper.deleteByRoleId(role.getId());
        normalizedIds.forEach(permissionId ->
                rolePermissionMapper.insert(role.getId(), permissionId)
        );
    }

    private Role requireManagedRole(String roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "角色不存在");
        }
        organizationAccessPolicy.resolveManagedOrganizationId(role.getOrganizationId());
        return role;
    }

    private List<Role> requireRoles(List<String> roleIds) {
        return requireRoles(roleIds, tenantContext.organizationId());
    }

    private List<Role> requireRoles(List<String> roleIds, String organizationId) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "至少分配一个角色");
        }
        List<Role> assignedRoles = roleIds.stream()
                .distinct()
                .map(roleId -> requireRole(roleId, organizationId))
                .filter(role -> "ACTIVE".equals(role.getStatus()))
                .toList();
        if (assignedRoles.size() != roleIds.stream().distinct().count()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "包含已停用的角色");
        }
        return assignedRoles;
    }

    private void provisionBuiltInRoles(String organizationId) {
        Map<String, RoleSeed> seeds = Map.of(
                "ADMIN", new RoleSeed("企业管理员", "管理本公司配置、人员、模型、自动化与全部业务数据", "ALL"),
                "OPERATOR", new RoleSeed("内容运营", "管理内容工作流、视频脚本、素材和 AI 视频生产", "DEPARTMENT_AND_CHILDREN"),
                "DESIGNER", new RoleSeed("视觉设计", "处理商品素材、视频脚本、分镜和视觉生成任务", "DEPARTMENT"),
                "REVIEWER", new RoleSeed("内容审核", "查看视频生产过程并审核内容与生成结果", "ALL"),
                "PUBLISHER", new RoleSeed("发布专员", "查看视频成片并管理店铺发布与促销任务", "DEPARTMENT")
        );
        Map<String, Set<String>> grants = Map.of(
                "OPERATOR", Set.of(
                        "dashboard:view", "workflow:view", "workflow:manage",
                        "asset:view", "asset:manage", "video:script:view", "video:script:manage",
                        "video:task:view", "video:task:manage", "automation:view", "automation:execute",
                        "product:collect", "product:view", "product:edit", "asset:watermark"
                ),
                "DESIGNER", Set.of(
                        "dashboard:view", "workflow:view", "asset:view", "asset:manage",
                        "video:script:view", "video:script:manage", "video:task:view", "video:task:manage",
                        "product:collect", "product:view", "product:edit", "asset:watermark"
                ),
                "REVIEWER", Set.of(
                        "dashboard:view", "workflow:view", "workflow:review", "asset:view",
                        "video:script:view", "video:task:view", "product:view", "product:edit"
                ),
                "PUBLISHER", Set.of(
                        "dashboard:view", "workflow:view", "publish:view", "publish:manage", "video:task:view",
                        "product:view", "product:publish"
                )
        );
        List<Permission> allPermissions = listPermissions();
        seeds.forEach((code, seed) -> {
            Role role = new Role(
                    organizationId, code, seed.name(), seed.description(), seed.dataScope(), true
            );
            roleMapper.insert(role);
            Set<String> permissionCodes = "ADMIN".equals(code)
                    ? allPermissions.stream().map(Permission::getCode).collect(java.util.stream.Collectors.toSet())
                    : grants.getOrDefault(code, Set.of());
            allPermissions.stream()
                    .filter(permission -> permissionCodes.contains(permission.getCode()))
                    .forEach(permission -> rolePermissionMapper.insert(role.getId(), permission.getId()));
        });
    }

    private Role requireRole(String roleId, String organizationId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null || !organizationId.equals(role.getOrganizationId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "角色不存在");
        }
        return role;
    }

    private void validateDataScope(String dataScope) {
        if (!List.of("ALL", "DEPARTMENT", "DEPARTMENT_AND_CHILDREN", "SELF")
                .contains(dataScope)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的数据范围");
        }
    }

    private RoleOverview roleOverview(Role role) {
        return new RoleOverview(
                role,
                rolePermissionMapper.selectPermissionIds(role.getId()),
                rolePermissionMapper.selectPermissionCodes(role.getId()),
                userRoleMapper.countUsersByRoleId(role.getId())
        );
    }

    private UserOverview userOverview(PlatformUser user) {
        return new UserOverview(
                user,
                organizationName(user.getOrganizationId()),
                departmentName(user.getDepartmentId()),
                userRoleMapper.selectRoleIds(user.getId()),
                userMapper.selectRoleNames(user.getId())
        );
    }

    private Department requireDepartment(String id, String organizationId) {
        Department department = departmentMapper.selectById(id);
        if (department == null || !organizationId.equals(department.getOrganizationId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "部门不属于当前企业");
        }
        return department;
    }

    private PlatformUser requireUser(String id) {
        PlatformUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "人员不存在");
        }
        organizationAccessPolicy.resolveManagedOrganizationId(user.getOrganizationId());
        return user;
    }

    private Department requireManagedDepartment(String id) {
        Department department = departmentMapper.selectById(id);
        if (department == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "部门不存在");
        }
        organizationAccessPolicy.resolveManagedOrganizationId(department.getOrganizationId());
        return department;
    }

    private String departmentName(String departmentId) {
        if (departmentId == null) {
            return "未分配";
        }
        Department department = departmentMapper.selectById(departmentId);
        return department == null ? "未分配" : department.getName();
    }

    private String organizationName(String organizationId) {
        Organization organization = organizationMapper.selectById(organizationId);
        return organization == null ? "未知公司" : organization.getName();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizePhone(String phone) {
        String normalized = phone == null ? "" : phone.trim().replace(" ", "");
        if (!normalized.matches("\\+?[0-9]{6,20}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请输入正确的手机号");
        }
        return normalized;
    }

    public record UserOverview(
            PlatformUser user,
            String organizationName,
            String departmentName,
            List<String> roleIds,
            List<String> roleNames
    ) {
    }

    public record RoleOverview(
            Role role,
            List<String> permissionIds,
            List<String> permissionCodes,
            long userCount
    ) {
    }

    private record RoleSeed(String name, String description, String dataScope) {
    }
}
