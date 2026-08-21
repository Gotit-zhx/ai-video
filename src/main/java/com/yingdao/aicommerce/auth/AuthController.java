package com.yingdao.aicommerce.auth;

import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy;
import com.yingdao.aicommerce.saas.mapper.OrganizationMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final OrganizationMapper organizationMapper;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final OrganizationAccessPolicy organizationAccessPolicy;

    public AuthController(
            OrganizationMapper organizationMapper,
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            FindByIndexNameSessionRepository<? extends Session> sessionRepository,
            OrganizationAccessPolicy organizationAccessPolicy
    ) {
        this.organizationMapper = organizationMapper;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionRepository = sessionRepository;
        this.organizationAccessPolicy = organizationAccessPolicy;
    }

    @PostMapping("/login")
    public ApiResponse<CurrentUserResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        String loginName = request.organizationCode().trim().toLowerCase()
                + "/" + request.phone().trim();
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            loginName,
                            request.password()
                    )
            );
        } catch (AuthenticationException exception) {
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "企业代码、手机号或密码不正确"
            );
        }
        PlatformPrincipal principal = (PlatformPrincipal) authentication.getPrincipal();

        HttpSession existing = servletRequest.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        sessionRepository.findByPrincipalName(principal.getUsername())
                .keySet()
                .forEach(sessionRepository::deleteById);

        HttpSession session = servletRequest.getSession(true);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, servletRequest, servletResponse);
        return ApiResponse.ok(currentUser(principal));
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me(Authentication authentication) {
        PlatformPrincipal principal = (PlatformPrincipal) authentication.getPrincipal();
        return ApiResponse.ok(currentUser(principal));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ApiResponse.ok(null);
    }

    private CurrentUserResponse currentUser(PlatformPrincipal principal) {
        var organization = organizationMapper.selectById(principal.organizationId());
        return new CurrentUserResponse(
                principal.userId(),
                principal.actualUsername(),
                principal.displayName(),
                organization.getId(),
                organization.getCode(),
                organization.getName(),
                principal.roleCodes(),
                principal.permissionCodes(),
                organizationAccessPolicy.isPlatformAdmin(principal)
        );
    }

    public record LoginRequest(
            @NotBlank String organizationCode,
            @NotBlank String phone,
            @NotBlank String password
    ) {
    }

    public record CurrentUserResponse(
            String userId,
            String username,
            String displayName,
            String organizationId,
            String organizationCode,
            String organizationName,
            java.util.List<String> roles,
            java.util.List<String> permissions,
            boolean platformAdmin
    ) {
    }
}
