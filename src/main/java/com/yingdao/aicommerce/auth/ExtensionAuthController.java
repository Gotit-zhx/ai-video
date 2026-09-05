package com.yingdao.aicommerce.auth;

import com.yingdao.aicommerce.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/extension-auth")
public class ExtensionAuthController {
    private final ExtensionAuthService authService;

    public ExtensionAuthController(ExtensionAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/exchange")
    public ApiResponse<ExtensionAuthService.ExchangeResult> exchange(
            @RequestBody ExtensionAuthService.ExchangeRequest request
    ) {
        return ApiResponse.ok(authService.exchange(request));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal PlatformPrincipal principal,
                                      Authentication authentication) {
        ExtensionAuthService.AuthenticatedToken token = tokenDetails(authentication);
        return ApiResponse.ok(new MeResponse(
                authService.profile(principal, token.permissions()), token.expiresAt(),
                token.permissions(), token.deviceName(), token.extensionId(), token.extensionVersion()
        ));
    }

    @PostMapping({"/logout", "/revoke"})
    public ApiResponse<Void> logout(HttpServletRequest request,
                                    @AuthenticationPrincipal PlatformPrincipal principal) {
        authService.revoke(ExtensionBearerAuthenticationFilter.bearerToken(request),
                principal.userId(), principal.organizationId());
        return ApiResponse.ok(null);
    }

    private ExtensionAuthService.AuthenticatedToken tokenDetails(Authentication authentication) {
        if (authentication != null
                && authentication.getDetails() instanceof ExtensionAuthService.AuthenticatedToken token) {
            return token;
        }
        throw new com.yingdao.aicommerce.common.exception.BusinessException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "该接口仅供已配对插件访问"
        );
    }

    public record MeResponse(ExtensionAuthService.UserProfile user, java.time.Instant expiresAt,
                             java.util.List<String> permissions, String deviceName,
                             String extensionId, String extensionVersion) {}
}
