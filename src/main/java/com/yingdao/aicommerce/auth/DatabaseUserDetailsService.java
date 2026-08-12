package com.yingdao.aicommerce.auth;

import com.yingdao.aicommerce.saas.mapper.OrganizationMapper;
import com.yingdao.aicommerce.saas.mapper.PlatformUserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final PlatformUserMapper userMapper;
    private final OrganizationMapper organizationMapper;
    private final String defaultOrganizationCode;

    public DatabaseUserDetailsService(
            PlatformUserMapper userMapper,
            OrganizationMapper organizationMapper,
            @Value("${app.default-organization-code:heyi}") String defaultOrganizationCode
    ) {
        this.userMapper = userMapper;
        this.organizationMapper = organizationMapper;
        this.defaultOrganizationCode = defaultOrganizationCode;
    }

    @Override
    public UserDetails loadUserByUsername(String loginName) {
        String[] parts = loginName.split("/", 2);
        String organizationCode = parts.length == 2 ? parts[0] : defaultOrganizationCode;
        String username = parts.length == 2 ? parts[1] : parts[0];
        var user = userMapper.selectForLogin(organizationCode, username);
        if (user == null) {
            throw new UsernameNotFoundException("Enterprise account not found");
        }
        var organization = organizationMapper.selectById(user.getOrganizationId());
        if (organization == null || !"ACTIVE".equals(organization.getStatus())) {
            throw new UsernameNotFoundException("Enterprise is not active");
        }
        return new PlatformPrincipal(
                user.getId(),
                user.getOrganizationId(),
                organizationCode,
                user.getUsername(),
                user.getDisplayName(),
                user.getPasswordHash(),
                "ACTIVE".equals(user.getStatus()),
                userMapper.selectRoleCodes(user.getId()),
                userMapper.selectPermissionCodes(user.getId())
        );
    }
}
