package com.yingdao.aicommerce.saas.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.domain.ModelConfig;
import com.yingdao.aicommerce.saas.mapper.ModelConfigMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ModelConfigService {

    private final TenantContext tenantContext;
    private final ModelConfigMapper mapper;
    private final ModelSecretCipher secretCipher;

    public ModelConfigService(
            TenantContext tenantContext,
            ModelConfigMapper mapper,
            ModelSecretCipher secretCipher
    ) {
        this.tenantContext = tenantContext;
        this.mapper = mapper;
        this.secretCipher = secretCipher;
    }

    public List<ModelConfig> list() {
        return mapper.selectList(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getOrganizationId, tenantContext.organizationId())
                        .orderByDesc(ModelConfig::isDefaultModel)
                        .orderByDesc(ModelConfig::getUpdatedAt)
        );
    }

    public Optional<RuntimeModelConnection> defaultDriverConnection() {
        return driverConnection(tenantContext.organizationId());
    }

    public Optional<RuntimeModelConnection> driverConnection(String organizationId) {
        return modelConnection(organizationId, "DRIVER");
    }

    public Optional<RuntimeModelConnection> defaultImageConnection() {
        return imageConnection(tenantContext.organizationId());
    }

    public Optional<RuntimeModelConnection> imageConnection(String organizationId) {
        return modelConnection(organizationId, "IMAGE");
    }

    public List<RuntimeModelConnection> videoConnections(String organizationId) {
        return mapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getOrganizationId, organizationId)
                        .eq(ModelConfig::getModelType, "VIDEO")
                        .eq(ModelConfig::getStatus, "ACTIVE")
                        .orderByDesc(ModelConfig::isDefaultModel)
                        .orderByDesc(ModelConfig::getUpdatedAt))
                .stream().map(this::runtimeConnection)
                .filter(connection -> !"FAST".equals(connection.provider()))
                .toList();
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
            String modelType,
            String provider,
            String baseUrl,
            String modelId,
            String apiKey,
            boolean defaultModel
    ) {
        String organizationId = tenantContext.organizationId();
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
        if (config == null
                || !tenantContext.organizationId().equals(config.getOrganizationId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "模型连接不存在");
        }
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
        if (value.contains("dayan.design")) return "DAYAN";
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
            String apiKey
    ) {
    }
}
