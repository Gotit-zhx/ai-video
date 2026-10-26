package com.yingdao.aicommerce.finance;

import java.util.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

/** Company business groups; never used as credential/token rotation groups. */
final class FinanceGroups {
    private final FinanceRepository repo;
    FinanceGroups(FinanceRepository repo){this.repo=repo;}
    List<Map<String,Object>> list(Scope s){
        return repo.jdbc().queryForList("""
            SELECT g.id,g.name,COUNT(c.id) shop_count FROM finance_groups g
            LEFT JOIN finance_connections c ON c.organization_id=g.organization_id AND c.group_id=g.id AND c.status<>'REMOVED'
            WHERE g.organization_id=? GROUP BY g.id,g.name ORDER BY g.created_at,g.id
            """,s.organizationId());
    }
    String require(Scope s,String id,boolean lock){
        if(text(id).isBlank())throw bad("请选择店铺分组");
        var found=repo.jdbc().queryForList("SELECT id FROM finance_groups WHERE organization_id=? AND id=?"+(lock?" FOR UPDATE":""),s.organizationId(),id);
        if(found.isEmpty())throw bad("店铺分组不存在，请重新选择");
        return id;
    }
    String save(Scope s,String id,String name){
        name=text(name).trim();
        if(name.isBlank()||name.length()>80)throw bad("分组名称需要 1～80 个字符");
        if(id!=null)require(s,id,true);
        String key=id==null?UUID.randomUUID().toString():id;
        try{
            if(id==null)repo.jdbc().update("INSERT INTO finance_groups(id,organization_id,name) VALUES(?,?,?)",key,s.organizationId(),name);
            else repo.jdbc().update("UPDATE finance_groups SET name=?,updated_at=UTC_TIMESTAMP(6) WHERE organization_id=? AND id=?",name,s.organizationId(),id);
        }catch(org.springframework.dao.DuplicateKeyException e){throw bad("分组名称已存在");}
        return key;
    }
    void delete(Scope s,String id){
        require(s,id,true);
        if(repo.jdbc().queryForObject("SELECT COUNT(*) FROM finance_connections WHERE organization_id=? AND group_id=? AND status<>'REMOVED'",Long.class,s.organizationId(),id)>0)
            throw bad("此分组还有店铺，请先调整店铺分组再删除");
        if(repo.jdbc().queryForObject("SELECT (SELECT COUNT(*) FROM finance_cost_sources WHERE organization_id=? AND group_id=?)+(SELECT COUNT(*) FROM finance_manual_costs WHERE organization_id=? AND group_id=?)",
                Long.class,s.organizationId(),id,s.organizationId(),id)>0)throw bad("此分组还有成本文件或手动成本，请先在成本管理中移除");
        // An unused authorization link must not silently attach to a deleted group.
        repo.jdbc().update("DELETE FROM finance_groups WHERE organization_id=? AND id=?",s.organizationId(),id);
    }
    static boolean matches(Map<String,Object> shop,Filter filter){
        return (filter.shopId().isBlank()||filter.shopId().equals(text(shop.get("id"))))
                &&(filter.platform().isBlank()||filter.platform().equals(text(shop.get("platform"))))
                &&(filter.groupId().isBlank()||("UNGROUPED".equals(filter.groupId())?text(shop.get("group_id")).isBlank():filter.groupId().equals(text(shop.get("group_id")))));
    }
}
