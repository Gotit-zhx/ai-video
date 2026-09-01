package com.yingdao.aicommerce.auth;

import com.yingdao.aicommerce.saas.application.OrganizationAccessPolicy;
import com.yingdao.aicommerce.saas.mapper.OrganizationMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedSessionService {

    private final OrganizationMapper organizationMapper;
    private final SecurityContextRepository securityContextRepository;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final OrganizationAccessPolicy organizationAccessPolicy;

    public AuthenticatedSessionService(
            OrganizationMapper organizationMapper,
            SecurityContextRepository securityContextRepository,
            FindByIndexNameSessionRepository<? extends Session> sessionRepository,
            OrganizationAccessPolicy organizationAccessPolicy
    ) {
        this.organizationMapper = organizationMapper;
        this.securityContextRepository = securityContextRepository;
        this.sessionRepository = sessionRepository;
        this.organizationAccessPolicy = organizationAccessPolicy;
    }

    public CurrentUserResponse establish(
            PlatformPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                principal.getAuthorities()
        );
        return establish(authentication, request, response);
    }

    public CurrentUserResponse establish(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        PlatformPrincipal principal = (PlatformPrincipal) authentication.getPrincipal();
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        sessionRepository.findByPrincipalName(principal.getUsername())
                .keySet()
                .forEach(sessionRepository::deleteById);

        request.getSession(true);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return currentUser(principal);
    }

    public CurrentUserResponse currentUser(PlatformPrincipal principal) {
        var organization = organizationMapper.selectById(principal.organizationId());
        return new CurrentUserResponse(
                principal.userId(),
                principal.actualUsername(),
                principal.displayName(),
                organization.getId(),
                organization.getCode(),
                organization.getName(),
                principal.roleCodes(),
                principal.permissionCodes(),
                organizationAccessPolicy.isPlatformAdmin(principal)
        );
    }
}
