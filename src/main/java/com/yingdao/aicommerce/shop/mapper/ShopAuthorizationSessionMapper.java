package com.yingdao.aicommerce.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yingdao.aicommerce.shop.domain.ShopAuthorizationSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

@Mapper
public interface ShopAuthorizationSessionMapper extends BaseMapper<ShopAuthorizationSession> {
    @Update("""
            UPDATE shop_authorization_sessions
               SET status = 'PROCESSING', consumed_at = #{now}, updated_at = #{now}
             WHERE id = #{id}
               AND status = 'PENDING'
               AND expires_at > #{now}
            """)
    int consume(@Param("id") String id, @Param("now") Instant now);
}
