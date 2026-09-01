package com.yingdao.aicommerce.auth;

import com.yingdao.aicommerce.common.exception.BusinessException;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class SocialLoginService {

    private static final String STATE_PREFIX = "heyi:social-login:state:";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final String APPLE_ISSUER = "https://appleid.apple.com";
    private static final String APPLE_JWKS = "https://appleid.apple.com/auth/keys";

    private final Environment environment;
    private final StringRedisTemplate redis;
    private final RestClient restClient;

    public SocialLoginService(Environment environment, StringRedisTemplate redis) {
        this.environment = environment;
        this.redis = redis;
        this.restClient = RestClient.create();
    }

    public List<ProviderStatus> providerStatuses() {
        return List.of("wechat", "google", "apple").stream()
                .map(id -> {
                    ProviderConfig config = config(id);
                    return new ProviderStatus(
                            id,
                            config.label(),
                            config.enabled(),
                            config.enabled() ? "已配置" : "正式环境配置后启用"
                    );
                })
                .toList();
    }

    public String beginAuthorization(String provider, String organizationCode) {
        ProviderConfig config = requiredConfig(provider);
        String normalizedOrganization = normalizeOrganization(organizationCode);
        String state = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(
                STATE_PREFIX + state,
                config.id() + "|" + normalizedOrganization,
                STATE_TTL
        );
        String callback = callbackUrl(config.id());
        return switch (config.id()) {
            case "google" -> "https://accounts.google.com/o/oauth2/v2/auth"
                    + "?client_id=" + encode(config.clientId())
                    + "&redirect_uri=" + encode(callback)
                    + "&response_type=code&scope=" + encode("openid email profile")
                    + "&prompt=select_account&state=" + encode(state);
            case "wechat" -> "https://open.weixin.qq.com/connect/qrconnect"
                    + "?appid=" + encode(config.clientId())
                    + "&redirect_uri=" + encode(callback)
                    + "&response_type=code&scope=snsapi_login&state=" + encode(state)
                    + "#wechat_redirect";
            case "apple" -> "https://appleid.apple.com/auth/authorize"
                    + "?client_id=" + encode(config.clientId())
                    + "&redirect_uri=" + encode(callback)
                    + "&response_type=code&response_mode=form_post"
                    + "&scope=" + encode("name email")
                    + "&state=" + encode(state);
            default -> throw unsupportedProvider();
        };
    }

    public CallbackResult complete(String provider, String state, String code) {
        ProviderConfig config = requiredConfig(provider);
        if (state == null || state.isBlank() || code == null || code.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "第三方登录回调参数不完整");
        }
        String stored = redis.opsForValue().getAndDelete(STATE_PREFIX + state);
        if (stored == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "登录请求已失效，请重新发起");
        }
        String[] parts = stored.split("\\|", 2);
        if (parts.length != 2 || !config.id().equals(parts[0])) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "登录请求校验失败");
        }
        ExternalProfile profile = exchange(config, code);
        return new CallbackResult(parts[1], profile);
    }

    private ExternalProfile exchange(ProviderConfig config, String code) {
        try {
            return switch (config.id()) {
                case "google" -> exchangeGoogle(config, code);
                case "wechat" -> exchangeWechat(config, code);
                case "apple" -> exchangeApple(config, code);
                default -> throw unsupportedProvider();
            };
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "第三方账号服务暂时不可用，请稍后重试");
        }
    }

    @SuppressWarnings("unchecked")
    private ExternalProfile exchangeGoogle(ProviderConfig config, String code) {
        Map<String, Object> token = postForm(
                "https://oauth2.googleapis.com/token",
                Map.of(
                        "code", code,
                        "client_id", config.clientId(),
                        "client_secret", config.clientSecret(),
                        "redirect_uri", callbackUrl(config.id()),
                        "grant_type", "authorization_code"
                )
        );
        String accessToken = required(token, "access_token");
        Map<String, Object> user = restClient.get()
                .uri("https://openidconnect.googleapis.com/v1/userinfo")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);
        return new ExternalProfile(
                "google",
                required(user, "sub"),
                text(user, "email"),
                text(user, "name")
        );
    }

    @SuppressWarnings("unchecked")
    private ExternalProfile exchangeWechat(ProviderConfig config, String code) {
        Map<String, Object> token = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("api.weixin.qq.com")
                        .path("/sns/oauth2/access_token")
                        .queryParam("appid", config.clientId())
                        .queryParam("secret", config.clientSecret())
                        .queryParam("code", code)
                        .queryParam("grant_type", "authorization_code")
                        .build())
                .retrieve()
                .body(Map.class);
        rejectProviderError(token);
        String accessToken = required(token, "access_token");
        String openId = required(token, "openid");
        Map<String, Object> user = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("api.weixin.qq.com")
                        .path("/sns/userinfo")
                        .queryParam("access_token", accessToken)
                        .queryParam("openid", openId)
                        .queryParam("lang", "zh_CN")
                        .build())
                .retrieve()
                .body(Map.class);
        rejectProviderError(user);
        String subject = text(user, "unionid");
        if (subject == null || subject.isBlank()) subject = openId;
        return new ExternalProfile("wechat", subject, null, text(user, "nickname"));
    }

    private ExternalProfile exchangeApple(ProviderConfig config, String code) {
        Map<String, Object> token = postForm(
                "https://appleid.apple.com/auth/token",
                Map.of(
                        "code", code,
                        "client_id", config.clientId(),
                        "client_secret", config.clientSecret(),
                        "redirect_uri", callbackUrl(config.id()),
                        "grant_type", "authorization_code"
                )
        );
        String idToken = required(token, "id_token");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(APPLE_JWKS).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(config.clientId())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Apple audience mismatch", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(APPLE_ISSUER),
                audience
        ));
        Jwt jwt = decoder.decode(idToken);
        return new ExternalProfile(
                "apple",
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                null
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postForm(String url, Map<String, String> values) {
        var form = new LinkedMultiValueMap<String, String>();
        values.forEach(form::add);
        Map<String, Object> result = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        rejectProviderError(result);
        return result;
    }

    private void rejectProviderError(Map<String, Object> payload) {
        if (payload == null || payload.containsKey("error") || payload.containsKey("errcode")) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "第三方账号服务拒绝了登录请求");
        }
    }

    private String required(Map<String, Object> payload, String key) {
        String value = text(payload, key);
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "第三方账号服务返回内容不完整");
        }
        return value;
    }

    private String text(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private ProviderConfig requiredConfig(String provider) {
        ProviderConfig config = config(provider);
        if (!config.enabled()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, config.label() + "登录尚未配置");
        }
        return config;
    }

    private ProviderConfig config(String provider) {
        String id = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        String label = switch (id) {
            case "wechat" -> "微信";
            case "google" -> "Google";
            case "apple" -> "Apple";
            default -> throw unsupportedProvider();
        };
        String prefix = "app.social-login." + id + ".";
        String clientId = environment.getProperty(prefix + "client-id", "").trim();
        String clientSecret = environment.getProperty(prefix + "client-secret", "").trim();
        boolean requested = environment.getProperty(prefix + "enabled", Boolean.class, false);
        return new ProviderConfig(
                id,
                label,
                requested && !clientId.isBlank() && !clientSecret.isBlank(),
                clientId,
                clientSecret
        );
    }

    private String callbackUrl(String provider) {
        String base = environment.getProperty("app.public-base-url", "http://localhost:8080");
        return base.replaceAll("/+$", "") + "/api/v1/auth/social/" + provider + "/callback";
    }

    private String normalizeOrganization(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{2,40}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请先填写有效的公司代码");
        }
        return normalized;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private BusinessException unsupportedProvider() {
        return new BusinessException(HttpStatus.NOT_FOUND, "不支持的第三方登录方式");
    }

    public record ProviderStatus(String id, String label, boolean enabled, String message) {
    }

    private record ProviderConfig(
            String id,
            String label,
            boolean enabled,
            String clientId,
            String clientSecret
    ) {
    }

    public record ExternalProfile(
            String provider,
            String subject,
            String email,
            String displayName
    ) {
    }

    public record CallbackResult(String organizationCode, ExternalProfile profile) {
    }
}
