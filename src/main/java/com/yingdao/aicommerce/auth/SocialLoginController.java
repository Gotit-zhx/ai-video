package com.yingdao.aicommerce.auth;

import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth/social")
public class SocialLoginController {

    private static final String PENDING_KEY = "SOCIAL_LOGIN_PENDING";

    private final SocialLoginService socialLoginService;
    private final SocialLoginIdentityMapper identityMapper;
    private final DatabaseUserDetailsService userDetailsService;
    private final AuthenticationManager authenticationManager;
    private final AuthenticatedSessionService sessionService;

    public SocialLoginController(
            SocialLoginService socialLoginService,
            SocialLoginIdentityMapper identityMapper,
            DatabaseUserDetailsService userDetailsService,
            AuthenticationManager authenticationManager,
            AuthenticatedSessionService sessionService
    ) {
        this.socialLoginService = socialLoginService;
        this.identityMapper = identityMapper;
        this.userDetailsService = userDetailsService;
        this.authenticationManager = authenticationManager;
        this.sessionService = sessionService;
    }

    @GetMapping("/providers")
    public ApiResponse<List<SocialLoginService.ProviderStatus>> providers() {
        return ApiResponse.ok(socialLoginService.providerStatuses());
    }

    @GetMapping("/{provider}/start")
    public void start(
            @PathVariable String provider,
            @RequestParam String organizationCode,
            HttpServletResponse response
    ) throws IOException {
        response.sendRedirect(socialLoginService.beginAuthorization(provider, organizationCode));
    }

    @GetMapping("/{provider}/callback")
    public void callbackGet(
            @PathVariable String provider,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        callback(provider, state, code, error, request, response);
    }

    @PostMapping("/{provider}/callback")
    public void callbackPost(
            @PathVariable String provider,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        callback(provider, state, code, error, request, response);
    }

    @GetMapping("/pending")
    public ApiResponse<PendingResponse> pending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        PendingIdentity pending = session == null ? null : (PendingIdentity) session.getAttribute(PENDING_KEY);
        if (pending == null) return ApiResponse.ok(null);
        return ApiResponse.ok(new PendingResponse(
                pending.provider(),
                providerLabel(pending.provider()),
                pending.email(),
                pending.organizationCode()
        ));
    }

    @PostMapping("/bind")
    public ApiResponse<CurrentUserResponse> bind(
            @Valid @RequestBody BindRequest bindRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        HttpSession httpSession = request.getSession(false);
        PendingIdentity pending = httpSession == null
                ? null
                : (PendingIdentity) httpSession.getAttribute(PENDING_KEY);
        if (pending == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "第三方登录绑定请求已失效");
        }
        String organizationCode = bindRequest.organizationCode().trim().toLowerCase();
        if (!pending.organizationCode().equals(organizationCode)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "公司代码与登录请求不一致");
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            organizationCode + "/" + bindRequest.phone().trim(),
                            bindRequest.password()
                    )
            );
        } catch (AuthenticationException exception) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "企业账号或密码不正确，无法完成绑定");
        }
        PlatformPrincipal principal = (PlatformPrincipal) authentication.getPrincipal();
        String existingUserId = identityMapper.selectUserId(pending.provider(), pending.subject());
        if (existingUserId != null && !existingUserId.equals(principal.userId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "该第三方账号已绑定其他企业账号");
        }
        if (existingUserId == null) {
            try {
                identityMapper.insertIdentity(
                        UUID.randomUUID().toString(),
                        pending.provider(),
                        pending.subject(),
                        principal.userId(),
                        pending.email(),
                        pending.displayName()
                );
            } catch (DuplicateKeyException exception) {
                throw new BusinessException(HttpStatus.CONFLICT, "该第三方账号刚刚已完成绑定，请重新登录");
            }
        }
        httpSession.removeAttribute(PENDING_KEY);
        return ApiResponse.ok(sessionService.establish(authentication, request, response));
    }

    private void callback(
            String provider,
            String state,
            String code,
            String error,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        if (error != null && !error.isBlank()) {
            redirectError(response, "第三方登录已取消");
            return;
        }
        try {
            var result = socialLoginService.complete(provider, state, code);
            var profile = result.profile();
            String userId = identityMapper.selectUserId(profile.provider(), profile.subject());
            if (userId != null) {
                PlatformPrincipal principal = userDetailsService.loadByUserId(userId);
                sessionService.establish(principal, request, response);
                response.sendRedirect("/");
                return;
            }
            request.getSession(true).setAttribute(PENDING_KEY, new PendingIdentity(
                    result.organizationCode(),
                    profile.provider(),
                    profile.subject(),
                    profile.email(),
                    profile.displayName()
            ));
            response.sendRedirect("/#social-bind");
        } catch (Exception exception) {
            String message = exception instanceof BusinessException
                    ? exception.getMessage()
                    : "第三方登录暂时不可用，请稍后重试";
            redirectError(response, message);
        }
    }

    private void redirectError(HttpServletResponse response, String message) throws IOException {
        response.sendRedirect("/#social-error=" + URLEncoder.encode(message, StandardCharsets.UTF_8));
    }

    private String providerLabel(String provider) {
        return switch (provider) {
            case "wechat" -> "微信";
            case "google" -> "Google";
            case "apple" -> "Apple";
            default -> provider;
        };
    }

    public record BindRequest(
            @NotBlank String organizationCode,
            @NotBlank String phone,
            @NotBlank String password
    ) {
    }

    public record PendingResponse(
            String provider,
            String providerLabel,
            String email,
            String organizationCode
    ) {
    }

    private record PendingIdentity(
            String organizationCode,
            String provider,
            String subject,
            String email,
            String displayName
    ) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }
}
