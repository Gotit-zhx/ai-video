package com.yingdao.aicommerce.saas.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class TenantContext {

    public PlatformPrincipal currentPrincipal() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof PlatformPrincipal platformPrincipal) {
            return platformPrincipal;
        }
        throw new IllegalStateException("No platform principal is available");
    }

    public String organizationId() {
        return currentPrincipal().organizationId();
    }
}
