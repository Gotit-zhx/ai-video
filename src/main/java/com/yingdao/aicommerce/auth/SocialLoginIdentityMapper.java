package com.yingdao.aicommerce.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SocialLoginIdentityMapper {

    @Select("""
            SELECT user_id
            FROM social_login_identities
            WHERE provider = #{provider}
              AND provider_subject = #{subject}
            LIMIT 1
            """)
    String selectUserId(
            @Param("provider") String provider,
            @Param("subject") String subject
    );

    @Insert("""
            INSERT INTO social_login_identities (
                id, provider, provider_subject, user_id, email, display_name,
                created_at, updated_at
            ) VALUES (
                #{id}, #{provider}, #{subject}, #{userId}, #{email}, #{displayName},
                CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
            )
            """)
    int insertIdentity(
            @Param("id") String id,
            @Param("provider") String provider,
            @Param("subject") String subject,
            @Param("userId") String userId,
            @Param("email") String email,
            @Param("displayName") String displayName
    );
}
