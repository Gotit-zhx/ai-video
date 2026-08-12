package com.yingdao.aicommerce.saas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yingdao.aicommerce.saas.domain.PlatformUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PlatformUserMapper extends BaseMapper<PlatformUser> {

    @Select("""
            SELECT u.*
            FROM app_users u
            JOIN organizations o ON o.id = u.organization_id
            WHERE o.code = #{organizationCode}
              AND u.username = #{username}
            LIMIT 1
            """)
    PlatformUser selectForLogin(
            @Param("organizationCode") String organizationCode,
            @Param("username") String username
    );

    @Select("""
            SELECT r.code
            FROM roles r
            JOIN user_roles ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
              AND r.status = 'ACTIVE'
            ORDER BY r.code
            """)
    List<String> selectRoleCodes(@Param("userId") String userId);

    @Select("""
            SELECT r.name
            FROM roles r
            JOIN user_roles ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
              AND r.status = 'ACTIVE'
            ORDER BY r.name
            """)
    List<String> selectRoleNames(@Param("userId") String userId);

    @Select("""
            SELECT DISTINCT p.code
            FROM permissions p
            JOIN role_permissions rp ON rp.permission_id = p.id
            JOIN roles r ON r.id = rp.role_id AND r.status = 'ACTIVE'
            JOIN user_roles ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            ORDER BY p.code
            """)
    List<String> selectPermissionCodes(@Param("userId") String userId);
}
