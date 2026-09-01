package com.yingdao.aicommerce.auth;

import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AuthenticatedSessionService sessionService;

    public AuthController(
            AuthenticationManager authenticationManager,
            AuthenticatedSessionService sessionService
    ) {
        this.authenticationManager = authenticationManager;
        this.sessionService = sessionService;
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
        return ApiResponse.ok(sessionService.establish(authentication, servletRequest, servletResponse));
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me(Authentication authentication) {
        PlatformPrincipal principal = (PlatformPrincipal) authentication.getPrincipal();
        return ApiResponse.ok(sessionService.currentUser(principal));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        return ApiResponse.ok(null);
    }

    public record LoginRequest(
            @NotBlank String organizationCode,
            @NotBlank String phone,
            @NotBlank String password
    ) {
    }

}
