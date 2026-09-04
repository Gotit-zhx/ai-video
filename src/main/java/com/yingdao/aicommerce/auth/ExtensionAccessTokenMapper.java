package com.yingdao.aicommerce.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface ExtensionAccessTokenMapper extends BaseMapper<ExtensionAccessToken> {
    @Select("""
            SELECT * FROM extension_access_tokens
            WHERE token_hash = #{tokenHash}
              AND revoked_at IS NULL
              AND expires_at > #{now}
            LIMIT 1
            """)
    ExtensionAccessToken selectActive(
            @Param("tokenHash") String tokenHash,
            @Param("now") Instant now
    );

    @Update("""
            UPDATE extension_access_tokens
            SET last_used_at = #{usedAt}
            WHERE id = #{id}
              AND revoked_at IS NULL
              AND expires_at > #{usedAt}
            """)
    int touchIfActive(
            @Param("id") String id,
            @Param("usedAt") Instant usedAt
    );

    @Update("""
            UPDATE extension_access_tokens
            SET revoked_at = #{revokedAt}
            WHERE id = #{id}
              AND user_id = #{userId}
              AND organization_id = #{organizationId}
              AND revoked_at IS NULL
            """)
    int revokeIfActiveAndOwned(
            @Param("id") String id,
            @Param("userId") String userId,
            @Param("organizationId") String organizationId,
            @Param("revokedAt") Instant revokedAt
    );
}
