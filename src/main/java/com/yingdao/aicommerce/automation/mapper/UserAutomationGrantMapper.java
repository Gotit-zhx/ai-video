package com.yingdao.aicommerce.automation.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserAutomationGrantMapper {
    @Select("""
            SELECT automation_code
            FROM user_automation_grants
            WHERE organization_id = #{organizationId} AND user_id = #{userId}
            ORDER BY automation_code
            """)
    List<String> selectCodes(
            @Param("organizationId") String organizationId,
            @Param("userId") String userId
    );

    @Delete("""
            DELETE FROM user_automation_grants
            WHERE organization_id = #{organizationId} AND user_id = #{userId}
            """)
    int deleteForUser(
            @Param("organizationId") String organizationId,
            @Param("userId") String userId
    );

    @Delete("""
            DELETE FROM user_automation_grants
            WHERE automation_code = #{automationCode}
            """)
    int deleteForAutomation(@Param("automationCode") String automationCode);

    @Insert("""
            INSERT INTO user_automation_grants (
                organization_id, user_id, automation_code, granted_by
            ) VALUES (
                #{organizationId}, #{userId}, #{automationCode}, #{grantedBy}
            )
            """)
    int insertGrant(
            @Param("organizationId") String organizationId,
            @Param("userId") String userId,
            @Param("automationCode") String automationCode,
            @Param("grantedBy") String grantedBy
    );
}
