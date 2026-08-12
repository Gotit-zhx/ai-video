package com.yingdao.aicommerce.saas.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("agent_model_configs")
public class ModelConfig {

    @TableId
    private String id;
    private String organizationId;
    private String modelType;
    private String provider;
    private String baseUrl;
    private String modelId;
    private String apiFormat;
    private String apiKeyCiphertext;
    private String keyHint;
    private String status;
    private boolean defaultModel;
    private Instant createdAt;
    private Instant updatedAt;

    protected ModelConfig() {
    }

    public ModelConfig(
            String organizationId,
            String modelType,
            String provider,
            String baseUrl,
            String modelId,
            String apiKeyCiphertext,
            String keyHint,
            boolean defaultModel
    ) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.modelType = modelType;
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.modelId = modelId;
        this.apiFormat = "OPENAI_COMPATIBLE";
        this.apiKeyCiphertext = apiKeyCiphertext;
        this.keyHint = keyHint;
        this.status = "ACTIVE";
        this.defaultModel = defaultModel;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateConnection(
            String baseUrl,
            String modelId,
            String provider,
            String apiKeyCiphertext,
            String keyHint,
            boolean defaultModel
    ) {
        this.baseUrl = baseUrl;
        this.modelId = modelId;
        this.provider = provider;
        if (apiKeyCiphertext != null) {
            this.apiKeyCiphertext = apiKeyCiphertext;
            this.keyHint = keyHint;
        }
        this.defaultModel = defaultModel;
        this.updatedAt = Instant.now();
    }

    public void changeStatus(String status) {
        this.status = status;
        if ("DISABLED".equals(status)) {
            this.defaultModel = false;
        }
        this.updatedAt = Instant.now();
    }

    public void changeDefault(boolean defaultModel) {
        this.defaultModel = defaultModel;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getModelType() { return modelType; }
    public String getProvider() { return provider; }
    public String getBaseUrl() { return baseUrl; }
    public String getModelId() { return modelId; }
    public String getApiFormat() { return apiFormat; }
    public String getApiKeyCiphertext() { return apiKeyCiphertext; }
    public String getKeyHint() { return keyHint; }
    public String getStatus() { return status; }
    public boolean isDefaultModel() { return defaultModel; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
