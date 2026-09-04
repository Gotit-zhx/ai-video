package com.yingdao.aicommerce.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yingdao.aicommerce.shop.domain.ShopCredential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ShopCredentialMapper extends BaseMapper<ShopCredential> {
    @Select("""
            SELECT shop_id, organization_id, access_token_ciphertext,
                   refresh_token_ciphertext, access_token_expires_at,
                   refresh_token_expires_at, created_at, updated_at, version
              FROM shop_credentials
             WHERE shop_id = #{shopId}
             FOR UPDATE
            """)
    ShopCredential selectForUpdate(@Param("shopId") String shopId);
}
