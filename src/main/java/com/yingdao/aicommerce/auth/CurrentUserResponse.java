package com.yingdao.aicommerce.auth;

import java.util.List;

public record CurrentUserResponse(
        String userId,
        String username,
        String displayName,
        String organizationId,
        String organizationCode,
        String organizationName,
        List<String> roles,
        List<String> permissions,
        boolean platformAdmin
) {
}
