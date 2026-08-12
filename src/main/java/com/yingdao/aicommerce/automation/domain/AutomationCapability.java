package com.yingdao.aicommerce.automation.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("automation_capabilities")
public class AutomationCapability {
    @TableId
    private String code;
    private String name;
    private String description;
    private String version;
    private String commandType;
    private String supportedOs;
    private String entryPoint;
    private String inputSchema;
    private String runtimeConfig;
    private String packagePath;
    private String checksumSha256;
    private String sourceType;
    private String runnerType;
    private String conversionReport;
    private String status;
    private int sortOrder;
    private Instant createdAt;
    private Instant updatedAt;

    protected AutomationCapability() {
    }

    public AutomationCapability(
            String code,
            String name,
            String description,
            String version,
            String commandType,
            String supportedOs,
            String entryPoint,
            String inputSchema,
            String packagePath,
            String checksumSha256,
            String runnerType
    ) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.version = version;
        this.commandType = commandType;
        this.supportedOs = supportedOs;
        this.entryPoint = entryPoint;
        this.inputSchema = inputSchema;
        this.runtimeConfig = "YUE_BILL_EXPORT".equals(commandType)
                ? "{\"browserEngine\":\"FEIKUA\",\"proxyPolicy\":\"DIRECT\",\"allowedDomains\":[]}"
                : "{\"browserEngine\":\"BROWSER_ACT\",\"browserType\":\"chrome\",\"browserId\":\"\",\"proxyPolicy\":\"DIRECT\",\"requiredRegion\":\"\",\"allowedDomains\":[]}";
        this.packagePath = packagePath;
        this.checksumSha256 = checksumSha256;
        this.sourceType = "UPLOADED";
        this.runnerType = runnerType;
        this.conversionReport = "{\"converted\":true}";
        this.status = "DRAFT";
        this.sortOrder = 100;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void activate() {
        this.status = "ACTIVE";
        this.updatedAt = Instant.now();
    }

    public void updateInputSchema(String inputSchema) {
        this.inputSchema = inputSchema;
        this.updatedAt = Instant.now();
    }

    public void updateRuntimeConfig(String runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
        this.updatedAt = Instant.now();
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public String getCommandType() { return commandType; }
    public String getSupportedOs() { return supportedOs; }
    public String getEntryPoint() { return entryPoint; }
    public String getInputSchema() { return inputSchema; }
    public String getRuntimeConfig() { return runtimeConfig; }
    public String getPackagePath() { return packagePath; }
    public String getChecksumSha256() { return checksumSha256; }
    public String getSourceType() { return sourceType; }
    public String getRunnerType() { return runnerType; }
    public String getConversionReport() { return conversionReport; }
    public String getStatus() { return status; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
