package com.yingdao.aicommerce.saas.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.domain.ModelConfig;
import com.yingdao.aicommerce.saas.mapper.ModelConfigMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class ModelConfigService {

    private static final String COPYRIGHT_FALLBACK_MODEL_ID = "grok-imagine-image-2.0";
    private static final String BANANA_IMAGE_MODEL_ID = "gemini-nano-banana-2.1";
    private static final List<String> IMG2_ARTWORK_MODEL_IDS = List.of("gpt-image-2", "gpt-image-2-2026-04-21");
    private static final String IMG2_ARTWORK_UNAVAILABLE = "当前公司没有可用的 IMG2.0（gpt-image-2）图片模型；请在模型接入中配置并启用 IMG2.0 后重试，透明印花不会改用其他模型。";

    private final TenantContext tenantContext;
    private final ModelConfigMapper mapper;
    private final ModelSecretCipher secretCipher;
    private final OrganizationAccessPolicy organizationAccessPolicy;

    public ModelConfigService(
            TenantContext tenantContext,
            ModelConfigMapper mapper,
            ModelSecretCipher secretCipher,
            OrganizationAccessPolicy organizationAccessPolicy
    ) {
        this.tenantContext = tenantContext;
        this.mapper = mapper;
        this.secretCipher = secretCipher;
        this.organizationAccessPolicy = organizationAccessPolicy;
    }

    public List<ModelConfig> list() {
        return list(null);
    }

    public List<ModelConfig> list(String requestedOrganizationId) {
        String organizationId = organizationAccessPolicy.resolveManagedOrganizationId(requestedOrganizationId);
        return mapper.selectList(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getOrganizationId, organizationId)
                        .orderByDesc(ModelConfig::isDefaultModel)
                        .orderByDesc(ModelConfig::getUpdatedAt)
        );
    }

    public Optional<RuntimeModelConnection> defaultDriverConnection() {
        return driverConnection(tenantContext.organizationId());
    }

    public Optional<RuntimeModelConnection> driverConnection(String organizationId) {
        return modelConnection(organizationId, "DRIVER").map(primary -> {
            var fallbacks = mapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                    .eq(ModelConfig::getOrganizationId, organizationId)
                    .eq(ModelConfig::getModelType, "DRIVER")
                    .eq(ModelConfig::getStatus, "ACTIVE")
                    .ne(ModelConfig::getId, primary.id())
                    .orderByDesc(ModelConfig::getUpdatedAt).orderByAsc(ModelConfig::getId))
                    .stream().filter(candidate -> candidate != null && organizationId.equals(candidate.getOrganizationId())
                            && "DRIVER".equals(candidate.getModelType()) && "ACTIVE".equals(candidate.getStatus())
                            && !primary.id().equals(candidate.getId()))
                    .flatMap(candidate -> {
                        try { return java.util.stream.Stream.of(runtimeConnection(candidate)); }
                        catch (IllegalStateException unusableKey) { return java.util.stream.Stream.empty(); }
                    }).limit(2).toList();
            return new RuntimeModelConnection(primary.id(), primary.baseUrl(), primary.modelId(),
                    primary.apiFormat(), primary.provider(), primary.apiKey(), List.of(), fallbacks);
        });
    }

    public Optional<RuntimeModelConnection> defaultImageConnection() {
        return imageConnection(tenantContext.organizationId());
    }

    /** Download an already-paid legacy result with its own connection after a model switch. */
    public Optional<RuntimeModelConnection> imageResultConnection(String org, String modelId) {
        if (org == null || org.isBlank() || modelId == null || modelId.isBlank()) return Optional.empty();
        return mapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getOrganizationId, org).eq(ModelConfig::getModelType, "IMAGE")
                        .eq(ModelConfig::getStatus, "ACTIVE").eq(ModelConfig::getModelId, modelId)
                        .orderByDesc(ModelConfig::isDefaultModel).orderByDesc(ModelConfig::getUpdatedAt))
                .stream().filter(config -> config != null && modelId.equals(config.getModelId()))
                .map(config -> commerceMockupRuntime(org, config)).flatMap(Optional::stream).findFirst();
    }

    public Optional<RuntimeModelConnection> watermarkImageConnection(String organizationId) {
        return watermarkConnection(organizationId, "IMAGE", "grok-imagine-image-");
    }

    public Optional<RuntimeModelConnection> watermarkDriverConnection(String organizationId) {
        return watermarkConnection(organizationId, "DRIVER", "grok-");
    }

    private Optional<RuntimeModelConnection> watermarkConnection(String org, String type, String prefix) {
        if (org == null || org.isBlank()) return Optional.empty();
        return mapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getOrganizationId, org).eq(ModelConfig::getModelType, type)
                        .eq(ModelConfig::getStatus, "ACTIVE").likeRight(ModelConfig::getModelId, prefix)
                        .orderByDesc(ModelConfig::isDefaultModel).orderByDesc(ModelConfig::getUpdatedAt)
                        .orderByAsc(ModelConfig::getId))
                .stream().filter(candidate -> candidate != null && org.equals(candidate.getOrganizationId())
                        && type.equals(candidate.getModelType()) && "ACTIVE".equals(candidate.getStatus())
                        && candidate.getModelId() != null && candidate.getModelId().startsWith(prefix)
                        && "OPENAI_COMPATIBLE".equals(candidate.getApiFormat()))
                .flatMap(candidate -> {
                    try { return java.util.stream.Stream.of(runtimeConnection(candidate)); }
                    catch (IllegalStateException unusableKey) { return java.util.stream.Stream.empty(); }
                }).filter(connection -> connection.apiKey() != null && !connection.apiKey().isBlank()).findFirst();
    }

    public Optional<RuntimeModelConnection> imageConnection(String organizationId) {
        return modelConnection(organizationId, "IMAGE").map(primary -> {
            if (COPYRIGHT_FALLBACK_MODEL_ID.equals(primary.modelId()) || BANANA_IMAGE_MODEL_ID.equals(primary.modelId())) return primary;
            List<RuntimeModelConnection> fallbacks = mapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                            .eq(ModelConfig::getOrganizationId, organizationId)
                            .eq(ModelConfig::getModelType, "IMAGE")
                            .eq(ModelConfig::getStatus, "ACTIVE")
                            .eq(ModelConfig::getBaseUrl, primary.baseUrl())
                            .eq(ModelConfig::getModelId, COPYRIGHT_FALLBACK_MODEL_ID))
                    .stream().map(candidate -> copyrightFallbackConnection(organizationId, primary, candidate))
                    .flatMap(Optional::stream).toList();
            return fallbacks.isEmpty() ? primary : new RuntimeModelConnection(primary.id(), primary.baseUrl(), primary.modelId(),
                    primary.apiFormat(), primary.provider(), primary.apiKey(), fallbacks);
        });
    }

    /** A default Banana also owns new product mockups; legacy tenants retain their Grok preference. */
    public Optional<RuntimeModelConnection> selectCommerceMockupRuntimeConnection(PlatformPrincipal user) {
        if (user == null || user.organizationId() == null || user.organizationId().isBlank())
            throw new BusinessException(HttpStatus.BAD_REQUEST, "商品套版模型选择缺少公司信息");
        String org = user.organizationId();
        var preferred = mapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getOrganizationId, org).eq(ModelConfig::getModelType, "IMAGE")
                        .eq(ModelConfig::getStatus, "ACTIVE").in(ModelConfig::getModelId, List.of(BANANA_IMAGE_MODEL_ID, COPYRIGHT_FALLBACK_MODEL_ID))
                        .orderByDesc(ModelConfig::isDefaultModel).orderByDesc(ModelConfig::getUpdatedAt).orderByAsc(ModelConfig::getId))
                .stream().filter(config -> config != null && (COPYRIGHT_FALLBACK_MODEL_ID.equals(config.getModelId())
                        || BANANA_IMAGE_MODEL_ID.equals(config.getModelId()) && config.isDefaultModel()))
                .map(config -> commerceMockupRuntime(org, config)).flatMap(Optional::stream).findFirst();
        if (preferred.isPresent()) return preferred;
        var selected = mapper.selectOne(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getOrganizationId, org).eq(ModelConfig::getModelType, "IMAGE")
                .eq(ModelConfig::getStatus, "ACTIVE").orderByDesc(ModelConfig::isDefaultModel)
                .orderByDesc(ModelConfig::getUpdatedAt).last("LIMIT 1"));
        return commerceMockupRuntime(org, selected);
    }

    /** A frozen attempt must resolve its original connection, never reselect a newly preferred model. */
    public Optional<RuntimeModelConnection> commerceMockupConnectionById(String organizationId, String id) {
        if (organizationId == null || organizationId.isBlank() || id == null || id.isBlank()) return Optional.empty();
        var selected = mapper.selectOne(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getOrganizationId, organizationId).eq(ModelConfig::getId, id)
                .eq(ModelConfig::getModelType, "IMAGE").eq(ModelConfig::getStatus, "ACTIVE"));
        if (selected == null || !id.equals(selected.getId())) return Optional.empty();
        return commerceMockupRuntime(organizationId, selected);
    }

    private Optional<RuntimeModelConnection> commerceMockupRuntime(String org, ModelConfig config) {
        if (config == null || !org.equals(config.getOrganizationId()) || !"IMAGE".equals(config.getModelType())
                || !"ACTIVE".equals(config.getStatus()) || !"OPENAI_COMPATIBLE".equals(config.getApiFormat())
                || config.getId() == null || config.getId().isBlank() || config.getModelId() == null || config.getModelId().isBlank()
                || config.getBaseUrl() == null || config.getBaseUrl().isBlank()) return Optional.empty();
        try {
            var connection = runtimeConnection(config);
            // The fallback here is selection before submission, not a second provider POST.
            return connection.apiKey() == null || connection.apiKey().isBlank() ? Optional.empty() : Optional.of(connection);
        } catch (IllegalStateException unusableKey) {
            return Optional.empty();
        }
    }

    /** Uses a real tenant-owned IMG2.0 connection without borrowing keys or attaching alternate models. */
    public Optional<RuntimeModelConnection> transparentArtworkConnection(String organizationId) {
        if (organizationId == null || organizationId.isBlank())
            throw new BusinessException(HttpStatus.BAD_REQUEST, "透明印花模型选择缺少公司信息");
        // Match imageConnection's primary selection exactly, without decrypting an unrelated default
        // or reading its Grok routes. Existing IMG2.0 jobs keep their configured ID and fingerprint.
        var selected = mapper.selectOne(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getOrganizationId, organizationId)
                .eq(ModelConfig::getModelType, "IMAGE")
                .eq(ModelConfig::getStatus, "ACTIVE")
                .orderByDesc(ModelConfig::isDefaultModel)
                .orderByDesc(ModelConfig::getUpdatedAt).last("LIMIT 1"));
        var current = transparentArtworkRuntime(organizationId, selected);
        if (current.isPresent()) return current;

        return mapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getOrganizationId, organizationId)
                        .eq(ModelConfig::getModelType, "IMAGE")
                        .eq(ModelConfig::getStatus, "ACTIVE")
                        .in(ModelConfig::getModelId, IMG2_ARTWORK_MODEL_IDS)
                        .orderByDesc(ModelConfig::isDefaultModel)
                        .orderByDesc(ModelConfig::getUpdatedAt)
                        .orderByAsc(ModelConfig::getId))
                .stream().filter(candidate -> candidate != null && (selected == null || !Objects.equals(selected.getId(), candidate.getId())))
                .map(candidate -> transparentArtworkRuntime(organizationId, candidate))
                .flatMap(Optional::stream).findFirst();
    }

    public RuntimeModelConnection requireTransparentArtworkConnection(String organizationId) {
        return transparentArtworkConnection(organizationId)
                .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, IMG2_ARTWORK_UNAVAILABLE));
    }

    public static boolean isImg2ArtworkModel(String modelId) {
        return modelId != null && IMG2_ARTWORK_MODEL_IDS.contains(modelId);
    }

    /** Defense at a submission boundary; a name substitution is not a configured IMG2.0 connection. */
    public static void requireImg2ArtworkModel(RuntimeModelConnection connection) {
        if (connection == null || !isImg2ArtworkModel(connection.modelId())
                || !"OPENAI_COMPATIBLE".equals(connection.apiFormat())
                || connection.id() == null || connection.id().isBlank()
                || connection.baseUrl() == null || connection.baseUrl().isBlank()
                || connection.apiKey() == null || connection.apiKey().isBlank()
                || !connection.copyrightFallbackModels().isEmpty())
            throw new BusinessException(HttpStatus.CONFLICT,
                    "透明印花提取与裂变固定使用已配置的 IMG2.0（gpt-image-2），不允许使用其他模型或备用路由。");
    }

    private Optional<RuntimeModelConnection> transparentArtworkRuntime(String organizationId, ModelConfig config) {
        if (config == null || !organizationId.equals(config.getOrganizationId())
                || !"IMAGE".equals(config.getModelType()) || !"ACTIVE".equals(config.getStatus())
                || !isImg2ArtworkModel(config.getModelId()) || !"OPENAI_COMPATIBLE".equals(config.getApiFormat())
                || config.getId() == null || config.getId().isBlank()
                || config.getBaseUrl() == null || config.getBaseUrl().isBlank()) return Optional.empty();
        try {
            var connection = runtimeConnection(config);
            if (connection.apiKey() == null || connection.apiKey().isBlank()) return Optional.empty();
            return Optional.of(connection);
        } catch (IllegalStateException unusableKey) {
            return Optional.empty();
        }
    }

    private Optional<RuntimeModelConnection> copyrightFallbackConnection(String organizationId, RuntimeModelConnection primary, ModelConfig candidate) {
        if (!organizationId.equals(candidate.getOrganizationId())
                || !"IMAGE".equals(candidate.getModelType())
                || !"ACTIVE".equals(candidate.getStatus())
                || !COPYRIGHT_FALLBACK_MODEL_ID.equals(candidate.getModelId())
                || !Objects.equals(primary.baseUrl(), candidate.getBaseUrl())
                || !Objects.equals(primary.apiFormat(), candidate.getApiFormat())
                || !Objects.equals(primary.provider(), resolveProvider(candidate.getProvider(), candidate.getBaseUrl()))) return Optional.empty();
        try {
            RuntimeModelConnection fallback = runtimeConnection(candidate);
            return fallback.apiKey() == null || fallback.apiKey().isBlank() ? Optional.empty() : Optional.of(fallback);
        } catch (IllegalStateException exception) {
            // An unusable backup must not prevent requests through the primary connection.
            return Optional.empty();
        }
    }

    public List<RuntimeModelConnection> videoConnections(String organizationId) {
        return mapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getOrganizationId, organizationId)
                        .eq(ModelConfig::getModelType, "VIDEO")
                        .eq(ModelConfig::getStatus, "ACTIVE")
                        .orderByDesc(ModelConfig::isDefaultModel)
                        .orderByDesc(ModelConfig::getUpdatedAt))
                .stream().map(this::runtimeConnection)
                .filter(connection -> "DAYAN".equals(connection.provider()))
                .map(connection -> new RuntimeModelConnection(connection.id(), connection.baseUrl(),
                        com.yingdao.aicommerce.video.application.provider.DayanVideoGenerationProvider.MODEL,
                        connection.apiFormat(), connection.provider(), connection.apiKey()))
                .toList();
    }

    public Optional<RuntimeModelConnection> dayanConnection(String organizationId) {
        return videoConnections(organizationId).stream()
                .filter(connection -> "DAYAN".equals(connection.provider())
                        || connection.baseUrl() != null && connection.baseUrl().contains("dayan.design"))
                .findFirst();
    }

    public Optional<RuntimeModelConnection> connectionById(String organizationId, String id) {
        ModelConfig config = mapper.selectOne(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getOrganizationId, organizationId)
                .eq(ModelConfig::getId, id));
        return Optional.ofNullable(config).map(this::runtimeConnection);
    }

    private Optional<RuntimeModelConnection> modelConnection(String organizationId, String modelType) {
        ModelConfig config = mapper.selectOne(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getOrganizationId, organizationId)
                        .eq(ModelConfig::getModelType, modelType)
                        .eq(ModelConfig::getStatus, "ACTIVE")
                        .orderByDesc(ModelConfig::isDefaultModel)
                        .orderByDesc(ModelConfig::getUpdatedAt)
                        .last("LIMIT 1")
        );
        if (config == null) return Optional.empty();
        return Optional.of(runtimeConnection(config));
    }

    private RuntimeModelConnection runtimeConnection(ModelConfig config) {
        return new RuntimeModelConnection(
                config.getId(),
                config.getBaseUrl(),
                config.getModelId(),
                config.getApiFormat(),
                resolveProvider(config.getProvider(), config.getBaseUrl()),
                secretCipher.decrypt(config.getApiKeyCiphertext())
        );
    }

    @Transactional
    public ModelConfig create(
            String requestedOrganizationId,
            String modelType,
            String provider,
            String baseUrl,
            String modelId,
            String apiKey,
            boolean defaultModel
    ) {
        String organizationId = organizationAccessPolicy.resolveManagedOrganizationId(requestedOrganizationId);
        String normalizedType = normalizeModelType(modelType);
        String normalizedProvider = normalizeProvider(normalizedType, provider, baseUrl);
        String normalizedUrl = normalizeBaseUrl(baseUrl);
        String normalizedModelId = modelId.trim();
        ensureUnique(null, organizationId, normalizedUrl, normalizedModelId);
        boolean shouldDefault = defaultModel || mapper.selectCount(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getOrganizationId, organizationId)
                        .eq(ModelConfig::getModelType, normalizedType)
        ) == 0;
        if (shouldDefault) {
            clearDefault(organizationId, normalizedType);
        }
        ModelConfig config = new ModelConfig(
                organizationId,
                normalizedType,
                normalizedProvider,
                normalizedUrl,
                normalizedModelId,
                secretCipher.encrypt(apiKey),
                keyHint(apiKey),
                shouldDefault
        );
        mapper.insert(config);
        return config;
    }

    @Transactional
    public ModelConfig create(
            String modelType,
            String provider,
            String baseUrl,
            String modelId,
            String apiKey,
            boolean defaultModel
    ) {
        return create(null, modelType, provider, baseUrl, modelId, apiKey, defaultModel);
    }

    @Transactional
    public ModelConfig update(
            String id,
            String baseUrl,
            String modelId,
            String provider,
            String apiKey,
            boolean defaultModel
    ) {
        ModelConfig config = requireConfig(id);
        String normalizedUrl = normalizeBaseUrl(baseUrl);
        String normalizedProvider = normalizeProvider(config.getModelType(), provider, normalizedUrl);
        String normalizedModelId = modelId.trim();
        ensureUnique(id, config.getOrganizationId(), normalizedUrl, normalizedModelId);
        if (defaultModel) {
            clearDefault(config.getOrganizationId(), config.getModelType());
        }
        String encrypted = apiKey == null || apiKey.isBlank()
                ? null
                : secretCipher.encrypt(apiKey);
        String hint = encrypted == null ? null : keyHint(apiKey);
        config.updateConnection(
                normalizedUrl,
                normalizedModelId,
                normalizedProvider,
                encrypted,
                hint,
                defaultModel
        );
        mapper.updateById(config);
        return config;
    }

    @Transactional
    public ModelConfig changeStatus(String id, String status) {
        if (!List.of("ACTIVE", "DISABLED").contains(status)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的模型状态");
        }
        ModelConfig config = requireConfig(id);
        boolean wasDefault = config.isDefaultModel();
        config.changeStatus(status);
        if ("DISABLED".equals(status) && wasDefault) {
            config.changeDefault(false);
        }
        mapper.updateById(config);
        if ("DISABLED".equals(status) && wasDefault) {
            promoteFallback(config.getOrganizationId(), config.getModelType(), config.getId());
        } else if ("ACTIVE".equals(status) && !hasActiveDefault(config.getOrganizationId(), config.getModelType())) {
            config.changeDefault(true);
            mapper.updateById(config);
        }
        return config;
    }

    @Transactional
    public ModelConfig setDefault(String id) {
        ModelConfig config = requireConfig(id);
        if (!"ACTIVE".equals(config.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "已停用的模型不能设为默认");
        }
        clearDefault(config.getOrganizationId(), config.getModelType());
        config.changeDefault(true);
        mapper.updateById(config);
        return config;
    }

    private void clearDefault(String organizationId, String modelType) {
        List<ModelConfig> defaults = mapper.selectList(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getOrganizationId, organizationId)
                        .eq(ModelConfig::getModelType, modelType)
                        .eq(ModelConfig::isDefaultModel, true)
        );
        defaults.forEach(config -> {
            config.changeDefault(false);
            mapper.updateById(config);
        });
    }

    private boolean hasActiveDefault(String organizationId, String modelType) {
        return mapper.selectCount(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getOrganizationId, organizationId)
                .eq(ModelConfig::getModelType, modelType)
                .eq(ModelConfig::getStatus, "ACTIVE")
                .eq(ModelConfig::isDefaultModel, true)) > 0;
    }

    private void promoteFallback(String organizationId, String modelType, String excludedId) {
        ModelConfig fallback = mapper.selectOne(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getOrganizationId, organizationId)
                .eq(ModelConfig::getModelType, modelType)
                .eq(ModelConfig::getStatus, "ACTIVE")
                .ne(ModelConfig::getId, excludedId)
                .orderByDesc(ModelConfig::getUpdatedAt)
                .last("LIMIT 1"));
        if (fallback != null) {
            fallback.changeDefault(true);
            mapper.updateById(fallback);
        }
    }

    private void ensureUnique(
            String currentId,
            String organizationId,
            String baseUrl,
            String modelId
    ) {
        Long count = mapper.selectCount(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getOrganizationId, organizationId)
                        .eq(ModelConfig::getBaseUrl, baseUrl)
                        .eq(ModelConfig::getModelId, modelId)
                        .ne(currentId != null, ModelConfig::getId, currentId)
        );
        if (count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "这个模型连接已经存在");
        }
    }

    private ModelConfig requireConfig(String id) {
        ModelConfig config = mapper.selectById(id);
        if (config == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "模型连接不存在");
        }
        organizationAccessPolicy.resolveManagedOrganizationId(config.getOrganizationId());
        return config;
    }

    private String normalizeBaseUrl(String baseUrl) {
        return baseUrl.trim().replaceAll("/+$", "");
    }

    private String normalizeModelType(String modelType) {
        String normalized = modelType == null ? "" : modelType.trim().toUpperCase();
        if (!List.of("DRIVER", "VIDEO", "IMAGE").contains(normalized)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的模型类型");
        }
        return normalized;
    }

    private String normalizeProvider(String modelType, String provider, String baseUrl) {
        if (!"VIDEO".equals(modelType)) return "AUTO";
        String normalized = provider == null || provider.isBlank() ? "AUTO" : provider.trim().toUpperCase();
        if (!List.of("AUTO", "DAYAN", "H3_MULTI").contains(normalized)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "暂不支持这个视频厂商");
        }
        return resolveProvider(normalized, baseUrl);
    }

    private String resolveProvider(String provider, String baseUrl) {
        if (provider != null && !provider.isBlank() && !"AUTO".equalsIgnoreCase(provider)) {
            return provider.toUpperCase();
        }
        String value = baseUrl == null ? "" : baseUrl.toLowerCase();
        if (value.contains("ai.usmercari.com")) return "FAST";
        if (value.contains("h3.zhongbiao.cloud")) return "H3_MULTI";
        if (value.contains("dayan.design") || value.contains("dayan.cloud")) return "DAYAN";
        return "AUTO";
    }

    private String keyHint(String apiKey) {
        String trimmed = apiKey.trim();
        return trimmed.substring(Math.max(0, trimmed.length() - 4));
    }

    public record RuntimeModelConnection(
            String id,
            String baseUrl,
            String modelId,
            String apiFormat,
            String provider,
            String apiKey,
            List<RuntimeModelConnection> copyrightFallbackModels,
            List<RuntimeModelConnection> fallbackModels
    ) {
        public RuntimeModelConnection {
            fallbackModels = fallbackModels == null ? List.of() : List.copyOf(fallbackModels);
            copyrightFallbackModels = copyrightFallbackModels == null
                    ? List.of() : List.copyOf(copyrightFallbackModels);
        }

        public RuntimeModelConnection(String id, String baseUrl, String modelId, String apiFormat, String provider, String apiKey) {
            this(id, baseUrl, modelId, apiFormat, provider, apiKey, List.of());
        }

        public RuntimeModelConnection(String id, String baseUrl, String modelId, String apiFormat,
                                      String provider, String apiKey, List<RuntimeModelConnection> copyrightFallbackModels) {
            this(id, baseUrl, modelId, apiFormat, provider, apiKey, copyrightFallbackModels, List.of());
        }

        /** Internal only: credentials must never be returned to clients or logged. */
        public Map<String, Object> driverRequestModel() {
            return Map.of("baseUrl", baseUrl, "modelId", modelId, "apiFormat", apiFormat, "apiKey", apiKey,
                    "fallbackModels", fallbackModels.stream().map(RuntimeModelConnection::driverRequestModel).toList());
        }

        public List<String> copyrightFallbackModelIds() {
            return copyrightFallbackModels.stream().map(RuntimeModelConnection::modelId).toList();
        }

        /** Internal request body; contains credentials and must not be logged or returned to browsers. */
        public Map<String, Object> imageRequestModel() {
            return Map.of("baseUrl", baseUrl, "modelId", modelId, "apiFormat", apiFormat, "apiKey", apiKey,
                    "copyrightFallbackModels", copyrightFallbackModels.stream().map(RuntimeModelConnection::imageRequestModel).toList());
        }
    }
}
