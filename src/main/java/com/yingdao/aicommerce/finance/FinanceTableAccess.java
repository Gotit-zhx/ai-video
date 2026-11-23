package com.yingdao.aicommerce.finance;

import com.yingdao.aicommerce.auth.DatabaseUserDetailsService;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Rechecks the authority behind a browser snapshot using current company membership. */
@Component
public class FinanceTableAccess {
    private static final Set<String> VIEWS=Set.of("orders","fees","fee-records","settlements");
    private final DatabaseUserDetailsService users;
    private final FinanceService finance;
    private final FinanceRepository repo;

    public FinanceTableAccess(DatabaseUserDetailsService users,FinanceService finance,FinanceRepository repo){
        this.users=users;
        this.finance=finance;
        this.repo=repo;
    }

    static boolean cachedView(String view){return view!=null&&VIEWS.contains(view);}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public String revision(PlatformPrincipal session){
        finance.authorize(session);
        PlatformPrincipal current;
        try{current=users.loadByUserId(session.userId());}
        catch(UsernameNotFoundException unavailable){throw forbidden();}
        if(!current.isEnabled()||!session.organizationId().equals(current.organizationId()))throw forbidden();
        finance.authorize(current);
        var shops=repo.jdbc().queryForList("""
            SELECT id,group_id FROM finance_connections
            WHERE organization_id=? AND status<>'REMOVED' ORDER BY id
            """,current.organizationId());
        return FinanceData.hash(repo.write(List.of(current.organizationId(),current.userId(),
            current.roleCodes().stream().distinct().sorted().toList(),
            current.permissionCodes().stream().distinct().sorted().toList(),shops)));
    }

    private static BusinessException forbidden(){
        return new BusinessException(HttpStatus.FORBIDDEN,"当前账号的财务访问权限已失效");
    }
}
