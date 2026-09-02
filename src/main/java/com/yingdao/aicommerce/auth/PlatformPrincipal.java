package com.yingdao.aicommerce.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

public class PlatformPrincipal implements UserDetails, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String userId;
    private final String organizationId;
    private final String organizationCode;
    private final String loginName;
    private final String username;
    private final String displayName;
    private final String password;
    private final boolean enabled;
    private final String registrationSource;
    private final List<String> roleCodes;
    private final List<String> permissionCodes;
    private final List<GrantedAuthority> authorities;

    public PlatformPrincipal(
            String userId,
            String organizationId,
            String organizationCode,
            String username,
            String displayName,
            String password,
            boolean enabled,
            List<String> roleCodes,
            List<String> permissionCodes
    ) {
        this(userId, organizationId, organizationCode, username, displayName, password,
                enabled, "LOCAL", roleCodes, permissionCodes);
    }

    public PlatformPrincipal(
            String userId,
            String organizationId,
            String organizationCode,
            String username,
            String displayName,
            String password,
            boolean enabled,
            String registrationSource,
            List<String> roleCodes,
            List<String> permissionCodes
    ) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.organizationCode = organizationCode;
        this.loginName = organizationCode + "/" + username;
        this.username = username;
        this.displayName = displayName;
        this.password = password;
        this.enabled = enabled;
        this.registrationSource = registrationSource == null ? "LOCAL" : registrationSource;
        this.roleCodes = List.copyOf(roleCodes);
        this.permissionCodes = List.copyOf(permissionCodes);
        this.authorities = java.util.stream.Stream.concat(
                        roleCodes.stream().map(code -> "ROLE_" + code),
                        permissionCodes.stream()
                )
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    public String userId() { return userId; }
    public String organizationId() { return organizationId; }
    public String organizationCode() { return organizationCode; }
    public String actualUsername() { return username; }
    public String displayName() { return displayName; }
    public List<String> roleCodes() { return roleCodes; }
    public List<String> permissionCodes() { return permissionCodes; }
    public boolean externallyRegistered() { return !"LOCAL".equals(registrationSource); }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }

    @Override
    public String getPassword() { return password; }

    @Override
    public String getUsername() { return loginName; }

    @Override
    public boolean isEnabled() { return enabled; }
}
