package com.yingdao.aicommerce.saas.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RolePermissionMapper {

    @Insert("INSERT IGNORE INTO role_permissions (role_id, permission_id) VALUES (#{roleId}, #{permissionId})")
    int insert(@Param("roleId") String roleId, @Param("permissionId") String permissionId);

    @Delete("DELETE FROM role_permissions WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") String roleId);

    @Select("""
            SELECT permission_id
            FROM role_permissions
            WHERE role_id = #{roleId}
            ORDER BY permission_id
            """)
    List<String> selectPermissionIds(@Param("roleId") String roleId);

    @Select("""
            SELECT p.code
            FROM permissions p
            JOIN role_permissions rp ON rp.permission_id = p.id
            WHERE rp.role_id = #{roleId}
            ORDER BY p.sort_order, p.code
            """)
    List<String> selectPermissionCodes(@Param("roleId") String roleId);
}
