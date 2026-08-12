package com.yingdao.aicommerce.saas.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("permissions")
public class Permission {

    @TableId
    private String id;
    private String code;
    private String name;
    private String module;
    private String resourceType;
    private String description;
    private int sortOrder;

    protected Permission() {
    }

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getModule() { return module; }
    public String getResourceType() { return resourceType; }
    public String getDescription() { return description; }
    public int getSortOrder() { return sortOrder; }
}
