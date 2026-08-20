package com.yingdao.aicommerce.saas.web;

import com.yingdao.aicommerce.common.api.ApiResponse;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.saas.domain.ModelConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/system/model-configs")
public class ModelConfigController {

    private final ModelConfigService service;

    public ModelConfigController(ModelConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ModelConfigResponse>> list() {
        return ApiResponse.ok(service.list().stream().map(ModelConfigResponse::from).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ModelConfigResponse> create(
            @Valid @RequestBody CreateModelConfigRequest request
    ) {
        return ApiResponse.created(ModelConfigResponse.from(service.create(
                request.modelType(),
                request.provider(),
                request.baseUrl(),
                request.modelId(),
                request.apiKey(),
                request.defaultModel()
        )));
    }

    @PutMapping("/{id}")
    public ApiResponse<ModelConfigResponse> update(
            @PathVariable String id,
            @Valid @RequestBody UpdateModelConfigRequest request
    ) {
        return ApiResponse.ok(ModelConfigResponse.from(service.update(
                id,
                request.baseUrl(),
                request.modelId(),
                request.provider(),
                request.apiKey(),
                request.defaultModel()
        )));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<ModelConfigResponse> changeStatus(
            @PathVariable String id,
            @Valid @RequestBody ChangeModelStatusRequest request
    ) {
        return ApiResponse.ok(ModelConfigResponse.from(
                service.changeStatus(id, request.status())
        ));
    }

    @PatchMapping("/{id}/default")
    public ApiResponse<ModelConfigResponse> setDefault(@PathVariable String id) {
        return ApiResponse.ok(ModelConfigResponse.from(service.setDefault(id)));
    }

    public record CreateModelConfigRequest(
            @NotBlank @Pattern(regexp = "DRIVER|VIDEO|IMAGE") String modelType,
            @Pattern(regexp = "AUTO|DAYAN|H3_MULTI") String provider,
            @NotBlank
            @Size(max = 512)
            @Pattern(regexp = "https?://.+", message = "必须是 http 或 https 地址")
            String baseUrl,
            @NotBlank @Size(max = 160) String modelId,
            @NotBlank @Size(max = 4096) String apiKey,
            boolean defaultModel
    ) {
    }

    public record UpdateModelConfigRequest(
            @NotBlank
            @Size(max = 512)
            @Pattern(regexp = "https?://.+", message = "必须是 http 或 https 地址")
            String baseUrl,
            @NotBlank @Size(max = 160) String modelId,
            @Pattern(regexp = "AUTO|DAYAN|H3_MULTI") String provider,
            @Size(max = 4096) String apiKey,
            boolean defaultModel
    ) {
    }

    public record ChangeModelStatusRequest(
            @NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status
    ) {
    }

    public record ModelConfigResponse(
            String id,
            String modelType,
            String provider,
            String baseUrl,
            String modelId,
            String maskedApiKey,
            String status,
            boolean defaultModel,
            Instant updatedAt
    ) {
        static ModelConfigResponse from(ModelConfig config) {
            return new ModelConfigResponse(
                    config.getId(),
                    config.getModelType(),
                    config.getProvider(),
                    config.getBaseUrl(),
                    config.getModelId(),
                    "••••" + config.getKeyHint(),
                    config.getStatus(),
                    config.isDefaultModel(),
                    config.getUpdatedAt()
            );
        }
    }
}
