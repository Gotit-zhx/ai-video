package com.yingdao.aicommerce.saas.web;

import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.saas.application.SaasAdminService;
import com.yingdao.aicommerce.saas.domain.Department;
import com.yingdao.aicommerce.saas.domain.Organization;
import com.yingdao.aicommerce.saas.domain.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/system")
public class SaasAdminController {

    private final SaasAdminService service;

    public SaasAdminController(SaasAdminService service) {
        this.service = service;
    }

    @GetMapping("/organization")
    public ApiResponse<OrganizationResponse> organization() {
        return ApiResponse.ok(OrganizationResponse.from(service.currentOrganization()));
    }

    @GetMapping("/organizations")
    public ApiResponse<List<OrganizationResponse>> organizations() {
        return ApiResponse.ok(service.listOrganizations().stream()
                .map(OrganizationResponse::from)
                .toList());
    }

    @PostMapping("/organizations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrganizationResponse> createOrganization(
            @Valid @RequestBody CreateOrganizationRequest request
    ) {
        return ApiResponse.created(OrganizationResponse.from(service.createOrganization(
                request.code(), request.name(), request.description()
        )));
    }

    @PutMapping("/organization")
    public ApiResponse<OrganizationResponse> updateOrganization(
            @Valid @RequestBody UpdateOrganizationRequest request
    ) {
        return ApiResponse.ok(OrganizationResponse.from(service.updateOrganization(
                request.name(),
                request.logoUrl(),
                request.description(),
                request.contactName(),
                request.contactPhone()
        )));
    }

    @GetMapping("/departments")
    public ApiResponse<List<DepartmentResponse>> departments(
            @RequestParam(required = false) String organizationId
    ) {
        return ApiResponse.ok(service.listDepartments(organizationId).stream()
                .map(DepartmentResponse::from)
                .toList());
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DepartmentResponse> createDepartment(
            @Valid @RequestBody CreateDepartmentRequest request
    ) {
        return ApiResponse.created(DepartmentResponse.from(service.createDepartment(
                request.organizationId(),
                request.parentId(),
                request.name(),
                request.code(),
                request.sortOrder()
        )));
    }

    @DeleteMapping("/departments/{departmentId}")
    public ApiResponse<Void> deleteDepartment(@PathVariable String departmentId) {
        service.deleteDepartment(departmentId);
        return ApiResponse.ok(null);
    }

    @PutMapping("/departments/{departmentId}")
    public ApiResponse<DepartmentResponse> updateDepartment(
            @PathVariable String departmentId,
            @Valid @RequestBody UpdateDepartmentRequest request
    ) {
        return ApiResponse.ok(DepartmentResponse.from(service.updateDepartment(
                departmentId,
                request.parentId(),
                request.name(),
                request.code()
        )));
    }

    @GetMapping("/roles")
    public ApiResponse<List<RoleResponse>> roles(
            @RequestParam(required = false) String organizationId
    ) {
        return ApiResponse.ok(service.listRoles(organizationId).stream().map(RoleResponse::from).toList());
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RoleResponse> createRole(@Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.created(RoleResponse.from(service.createRole(
                request.organizationId(),
                request.code(),
                request.name(),
                request.description(),
                request.dataScope(),
                request.permissionIds()
        )));
    }

    @PutMapping("/roles/{roleId}")
    public ApiResponse<RoleResponse> updateRole(
            @PathVariable String roleId,
            @Valid @RequestBody UpdateRoleRequest request
    ) {
        return ApiResponse.ok(RoleResponse.from(service.updateRole(
                roleId,
                request.name(),
                request.description(),
                request.dataScope(),
                request.status(),
                request.permissionIds()
        )));
    }

    @GetMapping("/permissions")
    public ApiResponse<List<PermissionResponse>> permissions() {
        return ApiResponse.ok(service.listPermissions().stream()
                .map(PermissionResponse::from)
                .toList());
    }

    @GetMapping("/users")
    public ApiResponse<List<UserResponse>> users(
            @RequestParam(required = false) String departmentId,
            @RequestParam(required = false) String organizationId
    ) {
        return ApiResponse.ok(service.listUsers(departmentId, organizationId).stream()
                .map(UserResponse::from)
                .toList());
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> createUser(
            @Valid @RequestBody CreateUserRequest request
    ) {
        return ApiResponse.created(UserResponse.from(service.createUser(
                request.organizationId(),
                request.departmentId(),
                request.displayName(),
                request.email(),
                request.phone(),
                request.jobTitle(),
                request.password(),
                request.roleIds()
        )));
    }

    @PutMapping("/users/{userId}")
    public ApiResponse<UserResponse> updateUser(
            @PathVariable String userId,
            @Valid @RequestBody UpdateUserRequest request
    ) {
        return ApiResponse.ok(UserResponse.from(service.updateUser(
                userId,
                request.departmentId(),
                request.displayName(),
                request.email(),
                request.phone(),
                request.jobTitle(),
                request.roleIds()
        )));
    }

    @PostMapping("/users/{userId}/reset-password")
    public ApiResponse<Void> resetPassword(
            @PathVariable String userId,
            @Valid @RequestBody ResetPasswordRequest request
    ) {
        service.resetUserPassword(userId, request.password());
        return ApiResponse.ok(null);
    }

    @PatchMapping("/users/status")
    public ApiResponse<UserResponse> changeUserStatus(
            @Valid @RequestBody ChangeUserStatusRequest request
    ) {
        return ApiResponse.ok(UserResponse.from(
                service.changeUserStatus(request.userId(), request.status())
        ));
    }

    public record UpdateOrganizationRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 512) String logoUrl,
            @Size(max = 1000) String description,
            @Size(max = 80) String contactName,
            @Size(max = 40) String contactPhone
    ) {
    }

    public record CreateOrganizationRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{1,39}") String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 1000) String description
    ) {
    }

    public record CreateDepartmentRequest(
            String organizationId,
            String parentId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,60}") String code,
            @Min(0) int sortOrder
    ) {
    }

    public record UpdateDepartmentRequest(
            String parentId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,60}") String code
    ) {
    }

    public record CreateUserRequest(
            String organizationId,
            @NotBlank String departmentId,
            @NotBlank @Size(max = 120) String displayName,
            @Email @Size(max = 160) String email,
            @NotBlank @Pattern(regexp = "\\+?[0-9]{6,20}") String phone,
            @Size(max = 100) String jobTitle,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotEmpty List<@NotBlank String> roleIds
    ) {
    }

    public record UpdateUserRequest(
            @NotBlank String departmentId,
            @NotBlank @Size(max = 120) String displayName,
            @Email @Size(max = 160) String email,
            @NotBlank @Pattern(regexp = "\\+?[0-9]{6,20}") String phone,
            @Size(max = 100) String jobTitle,
            @NotEmpty List<@NotBlank String> roleIds
    ) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 8, max = 72) String password
    ) {
    }

    public record CreateRoleRequest(
            String organizationId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,60}") String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotBlank @Pattern(regexp = "ALL|DEPARTMENT|DEPARTMENT_AND_CHILDREN|SELF")
            String dataScope,
            List<@NotBlank String> permissionIds
    ) {
    }

    public record UpdateRoleRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotBlank @Pattern(regexp = "ALL|DEPARTMENT|DEPARTMENT_AND_CHILDREN|SELF")
            String dataScope,
            @NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status,
            List<@NotBlank String> permissionIds
    ) {
    }

    public record ChangeUserStatusRequest(
            @NotBlank String userId,
            @NotNull @Pattern(regexp = "ACTIVE|LOCKED|DISABLED") String status
    ) {
    }

    public record OrganizationResponse(
            String id,
            String code,
            String name,
            String status,
            String logoUrl,
            String description,
            String contactName,
            String contactPhone,
            String planCode
    ) {
        static OrganizationResponse from(Organization organization) {
            return new OrganizationResponse(
                    organization.getId(), organization.getCode(), organization.getName(),
                    organization.getStatus(), organization.getLogoUrl(),
                    organization.getDescription(), organization.getContactName(),
                    organization.getContactPhone(), organization.getPlanCode()
            );
        }
    }

    public record DepartmentResponse(
            String id,
            String parentId,
            String name,
            String code,
            int sortOrder,
            String status
    ) {
        static DepartmentResponse from(Department department) {
            return new DepartmentResponse(
                    department.getId(), department.getParentId(), department.getName(),
                    department.getCode(), department.getSortOrder(), department.getStatus()
            );
        }
    }

    public record RoleResponse(
            String id,
            String code,
            String name,
            String description,
            String dataScope,
            boolean builtIn,
            String status,
            List<String> permissionIds,
            List<String> permissionCodes,
            long userCount
    ) {
        static RoleResponse from(SaasAdminService.RoleOverview overview) {
            var role = overview.role();
            return new RoleResponse(
                    role.getId(), role.getCode(), role.getName(), role.getDescription(),
                    role.getDataScope(), role.isBuiltIn(), role.getStatus(),
                    overview.permissionIds(), overview.permissionCodes(), overview.userCount()
            );
        }
    }

    public record PermissionResponse(
            String id,
            String code,
            String name,
            String module,
            String resourceType,
            String description
    ) {
        static PermissionResponse from(Permission permission) {
            return new PermissionResponse(
                    permission.getId(),
                    permission.getCode(),
                    permission.getName(),
                    permission.getModule(),
                    permission.getResourceType(),
                    permission.getDescription()
            );
        }
    }

    public record UserResponse(
            String id,
            String organizationId,
            String organizationName,
            String departmentId,
            String departmentName,
            String username,
            String displayName,
            String email,
            String phone,
            String jobTitle,
            String status,
            List<String> roleIds,
            List<String> roleNames,
            Instant createdAt
    ) {
        static UserResponse from(SaasAdminService.UserOverview overview) {
            var user = overview.user();
            return new UserResponse(
                    user.getId(), user.getOrganizationId(), overview.organizationName(),
                    user.getDepartmentId(), overview.departmentName(),
                    user.getUsername(), user.getDisplayName(),
                    user.getEmail(), user.getPhone(), user.getJobTitle(), user.getStatus(),
                    overview.roleIds(), overview.roleNames(), user.getCreatedAt()
            );
        }
    }
}
