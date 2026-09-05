package com.yingdao.aicommerce.auth;

import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.mapper.OrganizationMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ExtensionAuthService {
    /** The extension can submit DOM captures only; review, image processing and publish stay web-session-only. */
    public static final List<String> EXTENSION_SCOPES = List.of("product:collect");
    private static final String PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExtensionPairingCodeMapper pairingCodeMapper;
    private final ExtensionAccessTokenMapper accessTokenMapper;
    private final DatabaseUserDetailsService userDetailsService;
    private final OrganizationMapper organizationMapper;
    private final ObjectMapper objectMapper;
    private final Duration pairingCodeTtl;
    private final Duration tokenTtl;

    public ExtensionAuthService(
            ExtensionPairingCodeMapper pairingCodeMapper,
            ExtensionAccessTokenMapper accessTokenMapper,
            DatabaseUserDetailsService userDetailsService,
            OrganizationMapper organizationMapper,
            ObjectMapper objectMapper,
            @Value("${app.product-capture.extension.pairing-code-ttl:5m}") Duration pairingCodeTtl,
            @Value("${app.product-capture.extension.token-ttl:8h}") Duration tokenTtl
    ) {
        this.pairingCodeMapper = pairingCodeMapper;
        this.accessTokenMapper = accessTokenMapper;
        this.userDetailsService = userDetailsService;
        this.organizationMapper = organizationMapper;
        this.objectMapper = objectMapper;
        this.pairingCodeTtl = positiveDuration(pairingCodeTtl, "插件绑定码有效期");
        this.tokenTtl = positiveDuration(tokenTtl, "插件令牌有效期");
    }

    @Transactional
    public PairingCodeResult createPairingCode(PlatformPrincipal principal) {
        List<String> scopes = currentScopes(principal.permissionCodes(), EXTENSION_SCOPES);
        if (!scopes.contains("product:collect")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "当前账号没有商品采集权限");
        }
        Instant expiresAt = now().plus(pairingCodeTtl);
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = randomPairingCode();
            ExtensionPairingCode entity = new ExtensionPairingCode(
                    principal.organizationId(), principal.userId(), hashPairingCode(code),
                    writeScopes(scopes), expiresAt
            );
            try {
                pairingCodeMapper.insert(entity);
                return new PairingCodeResult(code, expiresAt, scopes);
            } catch (DuplicateKeyException ignored) {
                // A 60-bit collision is extraordinarily unlikely; retry without exposing it.
            }
        }
        throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法生成插件绑定码，请稍后重试");
    }

    @Transactional
    public ExchangeResult exchange(ExchangeRequest request) {
        ExchangeRequest safe = validateExchangeRequest(request);
        ExtensionPairingCode pairingCode = pairingCodeMapper.selectUsableForUpdate(
                hashPairingCode(safe.code()), now()
        );
        if (pairingCode == null) {
            throw unauthorized("绑定码无效、已使用或已过期");
        }

        PlatformPrincipal current = loadCurrentUser(pairingCode.getUserId());
        if (!pairingCode.getOrganizationId().equals(current.organizationId())) {
            throw unauthorized("绑定码所属企业与账号不一致");
        }
        List<String> issuedScopes = readScopes(pairingCode.getScopesJson());
        List<String> scopes = currentScopes(current.permissionCodes(), issuedScopes);
        if (!scopes.contains("product:collect")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "当前账号已没有商品采集权限");
        }

        pairingCode.consume();
        if (pairingCodeMapper.updateById(pairingCode) == 0) {
            throw unauthorized("绑定码已被使用");
        }

        Instant expiresAt = now().plus(tokenTtl);
        for (int attempt = 0; attempt < 5; attempt++) {
            String accessToken = randomAccessToken();
            ExtensionAccessToken entity = new ExtensionAccessToken(
                    current.organizationId(), current.userId(), hashAccessToken(accessToken),
                    writeScopes(scopes), safe.deviceName(), safe.extensionId(),
                    safe.extensionVersion(), expiresAt
            );
            try {
                accessTokenMapper.insert(entity);
                return new ExchangeResult(accessToken, "Bearer", expiresAt,
                        profile(current, scopes), scopes);
            } catch (DuplicateKeyException ignored) {
                // Retry with a new 256-bit random token.
            }
        }
        throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法签发插件令牌，请稍后重试");
    }

    @Transactional
    public AuthenticatedToken authenticate(String rawAccessToken) {
        String token = requireAccessToken(rawAccessToken);
        ExtensionAccessToken entity = accessTokenMapper.selectActive(hashAccessToken(token), now());
        if (entity == null) throw unauthorized("插件登录已失效，请重新配对");

        PlatformPrincipal current = loadCurrentUser(entity.getUserId());
        if (!entity.getOrganizationId().equals(current.organizationId())) {
            throw unauthorized("插件令牌所属企业与账号不一致");
        }
        List<String> scopes = currentScopes(current.permissionCodes(), readScopes(entity.getScopesJson()));
        if (scopes.isEmpty()) throw unauthorized("插件令牌已没有可用权限");

        PlatformPrincipal scopedPrincipal = new PlatformPrincipal(
                current.userId(), current.organizationId(), current.organizationCode(),
                current.actualUsername(), current.displayName(), current.getPassword(),
                current.isEnabled(), List.of(), scopes
        );
        if (accessTokenMapper.touchIfActive(entity.getId(), now()) == 0) {
            throw unauthorized("插件登录已失效，请重新配对");
        }
        return new AuthenticatedToken(entity.getId(), scopedPrincipal, scopes,
                entity.getExpiresAt(), entity.getDeviceName(), entity.getExtensionId(),
                entity.getExtensionVersion());
    }

    @Transactional
    public void revoke(String rawAccessToken, String expectedUserId, String expectedOrganizationId) {
        String token = requireAccessToken(rawAccessToken);
        ExtensionAccessToken entity = accessTokenMapper.selectActive(hashAccessToken(token), now());
        if (entity == null) return;
        if (!entity.getUserId().equals(expectedUserId)
                || !entity.getOrganizationId().equals(expectedOrganizationId)) {
            throw unauthorized("插件令牌与当前账号不一致");
        }
        accessTokenMapper.revokeIfActiveAndOwned(
                entity.getId(), expectedUserId, expectedOrganizationId, now()
        );
    }

    public UserProfile profile(PlatformPrincipal principal, List<String> scopes) {
        var organization = organizationMapper.selectById(principal.organizationId());
        if (organization == null || !"ACTIVE".equals(organization.getStatus())) {
            throw unauthorized("企业已停用或不存在");
        }
        return new UserProfile(
                principal.userId(), principal.actualUsername(), principal.displayName(),
                principal.organizationId(), principal.organizationCode(), organization.getName(),
                List.copyOf(scopes)
        );
    }

    private PlatformPrincipal loadCurrentUser(String userId) {
        try {
            PlatformPrincipal principal = userDetailsService.loadByUserId(userId);
            if (!principal.isEnabled()) throw unauthorized("账号已停用");
            return principal;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unauthorized("账号已停用或不存在");
        }
    }

    private ExchangeRequest validateExchangeRequest(ExchangeRequest request) {
        if (request == null) throw new BusinessException(HttpStatus.BAD_REQUEST, "绑定请求不能为空");
        String code = normalizePairingCode(request.code());
        String extensionId = boundedRequired(request.extensionId(), 160, "插件 ID");
        String deviceName = boundedNullable(request.deviceName(), 160);
        String version = boundedNullable(request.extensionVersion(), 40);
        return new ExchangeRequest(code, deviceName, extensionId, version);
    }

    private List<String> currentScopes(List<String> currentPermissions, List<String> maximumScopes) {
        Set<String> current = new LinkedHashSet<>(currentPermissions == null ? List.of() : currentPermissions);
        Set<String> maximum = new LinkedHashSet<>(maximumScopes == null ? List.of() : maximumScopes);
        return EXTENSION_SCOPES.stream()
                .filter(current::contains)
                .filter(maximum::contains)
                .toList();
    }

    private String randomPairingCode() {
        StringBuilder raw = new StringBuilder(12);
        for (int index = 0; index < 12; index++) {
            raw.append(PAIRING_ALPHABET.charAt(RANDOM.nextInt(PAIRING_ALPHABET.length())));
        }
        return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12);
    }

    private String randomAccessToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "hxt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizePairingCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase()
                .replace("-", "").replace(" ", "");
        if (normalized.length() != 12 || normalized.chars()
                .anyMatch(character -> PAIRING_ALPHABET.indexOf(character) < 0)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "绑定码格式不正确");
        }
        return normalized.substring(0, 4) + "-" + normalized.substring(4, 8)
                + "-" + normalized.substring(8, 12);
    }

    private String requireAccessToken(String value) {
        String token = value == null ? "" : value.trim();
        if (!token.matches("hxt_[A-Za-z0-9_-]{43}")) throw unauthorized("插件令牌格式不正确");
        return token;
    }

    private String hashPairingCode(String code) {
        return sha256("pairing:" + normalizePairingCode(code));
    }

    private String hashAccessToken(String token) {
        return sha256("access:" + token);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private String writeScopes(List<String> scopes) {
        try {
            return objectMapper.writeValueAsString(scopes);
        } catch (Exception exception) {
            throw new IllegalStateException("无法保存插件权限", exception);
        }
    }

    private List<String> readScopes(String json) {
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return values == null ? List.of() : new ArrayList<>(values);
        } catch (Exception exception) {
            throw unauthorized("插件权限数据无效，请重新配对");
        }
    }

    private String boundedRequired(String value, int max, String label) {
        String result = boundedNullable(value, max);
        if (result == null) throw new BusinessException(HttpStatus.BAD_REQUEST, label + "不能为空");
        return result;
    }

    private String boundedNullable(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > max) throw new BusinessException(HttpStatus.BAD_REQUEST, "插件设备信息过长");
        return result;
    }

    private static Duration positiveDuration(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + "必须大于 0");
        }
        return value;
    }

    protected Instant now() { return Instant.now(); }

    private BusinessException unauthorized(String message) {
        return new BusinessException(HttpStatus.UNAUTHORIZED, message);
    }

    public record ExchangeRequest(String code, String deviceName, String extensionId,
                                  String extensionVersion) {}

    public record PairingCodeResult(String code, Instant expiresAt, List<String> permissions) {}

    public record ExchangeResult(String accessToken, String tokenType, Instant expiresAt,
                                 UserProfile user, List<String> permissions) {}

    public record UserProfile(String userId, String username, String displayName,
                              String organizationId, String organizationCode,
                              String organizationName, List<String> permissions) {}

    public record AuthenticatedToken(String tokenId, PlatformPrincipal principal,
                                     List<String> permissions, Instant expiresAt,
                                     String deviceName, String extensionId,
                                     String extensionVersion) {}
}
