package com.yingdao.aicommerce.saas.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserRoleMapper {

    @Insert("INSERT IGNORE INTO user_roles (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int insert(@Param("userId") String userId, @Param("roleId") String roleId);

    @Delete("DELETE FROM user_roles WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") String userId);

    @Select("SELECT role_id FROM user_roles WHERE user_id = #{userId} ORDER BY role_id")
    List<String> selectRoleIds(@Param("userId") String userId);

    @Select("SELECT COUNT(*) FROM user_roles WHERE role_id = #{roleId}")
    long countUsersByRoleId(@Param("roleId") String roleId);
}
