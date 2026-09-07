package com.yingdao.aicommerce.product.domain;

import java.time.Instant;

/** Immutable snapshot; running batches must refer to this id, never to "latest". */
public record CommerceTemplateVersion(String id, String organizationId, String templateId,
                                      int version, String kind, String name, String configJson,
                                      String createdBy, Instant createdAt) {}
