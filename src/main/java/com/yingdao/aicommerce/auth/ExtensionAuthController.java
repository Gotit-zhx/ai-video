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
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.security.authentication.AuthenticationManager authenticationManager;
    @org.springframework.beans.factory.annotation.Value("${app.default-organization-code:heyi}")
    private String defaultOrganizationCode;

    public ExtensionAuthController(ExtensionAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/session")
    public ApiResponse<ExtensionAuthService.ExchangeResult> session(@RequestBody DeviceRequest device,
            @AuthenticationPrincipal PlatformPrincipal principal,HttpServletRequest request) {
        ExtensionVersionPolicy.requireSupported(device.extensionVersion());
        String origin=request.getHeader("Origin");
        if(device.extensionId()==null || !device.extensionId().matches("[a-p]{32}")
                || !("chrome-extension://"+device.extensionId()).equals(origin))
            throw new com.yingdao.aicommerce.common.exception.BusinessException(org.springframework.http.HttpStatus.FORBIDDEN,"仅允许浏览器插件读取登录状态");
        var code=authService.createPairingCode(principal);
        return ApiResponse.ok(authService.exchange(new ExtensionAuthService.ExchangeRequest(code.code(),device.deviceName(),device.extensionId(),device.extensionVersion())));
    }
    public record DeviceRequest(String deviceName,String extensionId,String extensionVersion) {}

    @PostMapping("/login")
    public ApiResponse<ExtensionAuthService.ExchangeResult> login(@RequestBody DirectLogin request) {
        ExtensionVersionPolicy.requireSupported(request.extensionVersion());
        if (request.account() == null || request.password() == null || request.account().isBlank())
            throw new com.yingdao.aicommerce.common.exception.BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"账号格式为 企业代码/手机号");
        org.springframework.security.core.Authentication authenticated;
        try {
            authenticated = authenticationManager.authenticate(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.unauthenticated((request.account().contains("/") ? request.account().trim() : defaultOrganizationCode + "/" + request.account().trim()),request.password()));
        } catch (org.springframework.security.core.AuthenticationException e) {
            throw new com.yingdao.aicommerce.common.exception.BusinessException(org.springframework.http.HttpStatus.UNAUTHORIZED,"账号或密码不正确");
        }
        var principal = (PlatformPrincipal) authenticated.getPrincipal();
        var code = authService.createPairingCode(principal);
        return ApiResponse.ok(authService.exchange(new ExtensionAuthService.ExchangeRequest(code.code(),request.deviceName(),request.extensionId(),request.extensionVersion())));
    }
    public record DirectLogin(String account,String password,String deviceName,String extensionId,String extensionVersion) {}

    @PostMapping("/exchange")
    public ApiResponse<ExtensionAuthService.ExchangeResult> exchange(
            @RequestBody ExtensionAuthService.ExchangeRequest request
    ) {
        ExtensionVersionPolicy.requireSupported(request.extensionVersion());
        return ApiResponse.ok(authService.exchange(request));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ExtensionVersionPolicy.UpgradeRequired.class)
    public org.springframework.http.ResponseEntity<ExtensionVersionPolicy.UpgradeResponse> upgradeRequired() {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.UPGRADE_REQUIRED)
                .header("Cache-Control", "no-store").body(ExtensionVersionPolicy.response());
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
