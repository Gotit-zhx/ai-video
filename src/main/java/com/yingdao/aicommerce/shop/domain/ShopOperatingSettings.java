package com.yingdao.aicommerce.shop.domain;
import java.time.Instant;
public record ShopOperatingSettings(String shopId,String organizationId,String groupName,
                                    String environment,int dailyListingLimit,String defaultTemplateSnapshotId,
                                    long version,String updatedBy,Instant updatedAt) {}
